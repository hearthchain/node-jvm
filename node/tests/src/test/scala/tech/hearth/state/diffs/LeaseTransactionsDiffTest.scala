package tech.hearth.state.diffs

import tech.hearth.account.Address
import tech.hearth.common.utils.EitherExt2.*
import tech.hearth.db.WithDomain
import tech.hearth.db.WithState.AddrWithBalance
import tech.hearth.lagonaki.mocks.TestBlock
import tech.hearth.settings.TestFunctionalitySettings
import tech.hearth.state.*
import tech.hearth.test.*
import tech.hearth.test.DomainPresets.*
import tech.hearth.transaction.Asset.Waves
import tech.hearth.transaction.lease.{LeaseCancelTransaction, LeaseTransaction}
import tech.hearth.transaction.transfer.*
import tech.hearth.transaction.TxHelpers

import scala.collection.immutable.VectorMap

class LeaseTransactionsDiffTest extends PropSpec with WithDomain {

  private val allowMultipleLeaseCancelTransactionUntilTimestamp = Long.MaxValue / 2
  private val settings =
    TestFunctionalitySettings.Enabled

  // TxHelpers.signer(1) is the master/sender throughout: it is credited by the genesis snapshot, which is applied
  // to the block at height 1
  private val masterBalance: Seq[AddrWithBalance] = Seq(AddrWithBalance(TxHelpers.signer(1).toAddress))

  def total(l: LeaseBalance): Long = l.in - l.out

  property("can lease/cancel lease preserving waves invariant") {
    val sender        = TxHelpers.signer(2)
    val recipient     = TxHelpers.signer(3)
    val miner         = TestBlock.defaultSigner.toAddress
    val senderBalance = Seq(AddrWithBalance(sender.toAddress))
    for {
      lease       <- Seq(TxHelpers.lease(sender, recipient.toAddress), TxHelpers.lease(sender, recipient.toAddress))
      leaseCancel <- Seq(TxHelpers.leaseCancel(lease.id(), sender), TxHelpers.leaseCancel(lease.id(), sender))
    } {
      assertDiffAndState(Seq(TestBlock.create(Seq())), TestBlock.create(Seq(lease)), balances = senderBalance) { case (snapshot, b) =>
        snapshot.balances shouldBe VectorMap(
          (sender.toAddress, Waves) -> (ENOUGH_AMT - lease.fee.value),
          (miner, Waves)            -> (BlockDiffer.CurrentBlockFeePart(lease.fee.value) + b.settings.rewardsSettings.initial)
        )
        snapshot.leaseBalances shouldBe Map(
          sender.toAddress    -> LeaseBalance(0, lease.amount.value),
          recipient.toAddress -> LeaseBalance(lease.amount.value, 0)
        )
      }
      assertDiffAndState(Seq(TestBlock.create(Seq(lease))), TestBlock.create(Seq(leaseCancel)), balances = senderBalance) { case (snapshot, b) =>
        snapshot.balances shouldBe VectorMap(
          // The whole fee of the lease block: 40% credited there, the 60% carry credited here - plus 40% of this
          // block's own fee, and the reward
          (miner, Waves) ->
            (lease.fee.value + BlockDiffer.CurrentBlockFeePart(leaseCancel.fee.value) + b.settings.rewardsSettings.initial),
          (sender.toAddress, Waves) -> (ENOUGH_AMT - lease.fee.value - leaseCancel.fee.value)
        )
        snapshot.leaseBalances shouldBe Map(
          sender.toAddress    -> LeaseBalance.empty,
          recipient.toAddress -> LeaseBalance.empty
        )
      }
    }
  }

  private val repeatedCancelForbidden = allowMultipleLeaseCancelTransactionUntilTimestamp + 1

  def cancelLeaseTwice(ts: Long): Seq[(TransferTransaction, LeaseTransaction, LeaseCancelTransaction, LeaseCancelTransaction)] = {
    val master    = TxHelpers.signer(1)
    val recipient = TxHelpers.signer(2)

    for {
      lease <- Seq(
        TxHelpers.lease(master, recipient.toAddress, timestamp = ts),
        TxHelpers.lease(master, recipient.toAddress, timestamp = ts)
      )
      leaseCancel <- Seq(
        TxHelpers.leaseCancel(lease.id(), master, timestamp = ts + 1),
        TxHelpers.leaseCancel(lease.id(), master, timestamp = ts + 1)
      )
      leaseCancel2 <- Seq(
        TxHelpers.leaseCancel(lease.id(), master, fee = leaseCancel.fee.value + 1, timestamp = ts + 1),
        TxHelpers.leaseCancel(lease.id(), master, fee = leaseCancel.fee.value + 1, timestamp = ts + 1)
      )
    } yield {
      // ensure recipient has enough effective balance
      val transfer = TxHelpers.transfer(master, recipient.toAddress, 20.waves, timestamp = ts)

      (transfer, lease, leaseCancel, leaseCancel2)
    }
  }

  private val disallowCancelTwice = {
    val ts = repeatedCancelForbidden

    cancelLeaseTwice(ts).map { case (payment, lease, unlease, unlease2) =>
      (Seq(TestBlock.create(ts, Seq(payment, lease, unlease))), TestBlock.create(ts, Seq(unlease2)))
    }
  }

  property("cannot cancel lease twice after allowMultipleLeaseCancelTransactionUntilTimestamp") {
    disallowCancelTwice.foreach { case (preconditions, block) =>
      assertDiffEi(preconditions, block, settings, masterBalance) { snapshotEi =>
        snapshotEi should produce("Cannot cancel already cancelled lease")
      }
    }
  }

  property("cannot lease more than actual balance(cannot lease forward)") {
    val setup: Seq[(LeaseTransaction, LeaseTransaction, Long)] = {
      val master    = TxHelpers.signer(1)
      val recipient = TxHelpers.signer(2)
      val forward   = TxHelpers.signer(3)

      for {
        lease        <- Seq(TxHelpers.lease(master, recipient.toAddress), TxHelpers.lease(master, recipient.toAddress))
        leaseForward <- Seq(TxHelpers.lease(recipient, forward.toAddress), TxHelpers.lease(recipient, forward.toAddress))
      } yield (lease, leaseForward, leaseForward.timestamp)
    }

    setup.foreach { case (lease, leaseForward, ts) =>
      // The recipient can pay for the lease it is forwarding, but owns nothing beyond that: what was leased *to* it is
      // what it must not be able to lease on. Without the fee it never reaches that check, failing on its own balance.
      val balances = masterBalance :+ AddrWithBalance(TxHelpers.signer(2).toAddress, leaseForward.fee.value)
      assertDiffEi(Seq(TestBlock.create(ts, Seq(lease))), TestBlock.create(ts, Seq(leaseForward)), settings, balances) { snapshotEi =>
        snapshotEi should produce("Cannot lease more than own")
      }
    }
  }

  def cancelLeaseOfAnotherSender(
      unleaseByRecipient: Boolean,
      timestamp: Long
  ): Seq[(Seq[AddrWithBalance], LeaseTransaction, LeaseCancelTransaction, Long)] = {
    val master    = TxHelpers.signer(1)
    val recipient = TxHelpers.signer(2)
    val other     = TxHelpers.signer(3)
    val unleaser  = if (unleaseByRecipient) recipient else other

    val genesis = AddrWithBalance.enoughBalances(TxHelpers.defaultSigner, master, unleaser)

    for {
      lease <- Seq(
        TxHelpers.lease(master, recipient.toAddress, timestamp = timestamp),
        TxHelpers.lease(master, recipient.toAddress, timestamp = timestamp)
      )
      unleaseOtherOrRecipient <- Seq(
        TxHelpers.leaseCancel(lease.id(), unleaser, timestamp = timestamp + 1),
        TxHelpers.leaseCancel(lease.id(), unleaser, timestamp = timestamp + 1)
      )
    } yield (genesis, lease, unleaseOtherOrRecipient, timestamp)
  }

  property("cannot cancel lease of another sender after allowMultipleLeaseCancelTransactionUntilTimestamp") {
    for {
      unleaseByRecipient                                   <- Seq(true, false)
      (genesis, lease, unleaseOtherOrRecipient, blockTime) <- cancelLeaseOfAnotherSender(unleaseByRecipient, repeatedCancelForbidden)
    } yield {
      assertDiffEi(
        Seq(TestBlock.create(blockTime, Seq(lease))),
        TestBlock.create(blockTime, Seq(unleaseOtherOrRecipient)),
        settings,
        genesis
      ) { snapshotEi =>
        snapshotEi should produce("LeaseTransaction was leased by other sender")
      }
    }
  }

  property(s"can pay for cancel lease from the returning funds (before and after BlockV5)") {
    val scenario = {
      val master    = TxHelpers.signer(1)
      val recipient = TxHelpers.signer(2)

      val fee    = 400000L
      val amount = 1000.waves

      val genesis = Seq(AddrWithBalance(master.toAddress, fee + amount))

      for {
        lease <- Seq(
          TxHelpers.lease(master, recipient.toAddress, amount, fee = fee),
          TxHelpers.lease(master, recipient.toAddress, amount, fee = fee)
        )
        leaseCancel <- Seq(
          TxHelpers.leaseCancel(lease.id(), master, fee = fee),
          TxHelpers.leaseCancel(lease.id(), master, fee = fee)
        )
      } yield (genesis, lease, leaseCancel, leaseCancel.timestamp + 1)
    }

    scenario.foreach { case (genesis, lease, leaseCancel, ts) =>
      val beforeFailedTxs = TestFunctionalitySettings.Enabled
      val afterFailedTxs = beforeFailedTxs.copy(
        preActivatedFeatures = beforeFailedTxs.preActivatedFeatures
      )

      assertDiffEi(Seq(TestBlock.create(ts, Seq(lease))), TestBlock.create(ts + 1, Seq(leaseCancel)), beforeFailedTxs, genesis) { ei =>
        ei.explicitGet()
      }

      assertDiffEi(Seq(TestBlock.create(ts, Seq(lease))), TestBlock.create(ts + 1, Seq(leaseCancel)), afterFailedTxs, genesis) { ei =>
        ei.explicitGet()
      }
    }
  }

  private val totalBalance = 1000.waves
  private val scenario: (Seq[AddrWithBalance], LeaseTransaction) = {
    val sender    = TxHelpers.signer(1)
    val recipient = TxHelpers.signer(2)

    val balances = Seq(AddrWithBalance(TxHelpers.defaultSigner.toAddress), AddrWithBalance(sender.toAddress, totalBalance))
    val lease    = TxHelpers.lease(sender, recipient.toAddress, totalBalance)

    (balances, lease)
  }

  property(s"fee is required") {
    val (balances, lt) = scenario

    withDomain(RideV4, balances) { d =>
      d.appendBlockE(lt) should produce("Cannot lease more than own")
    }
  }
}
