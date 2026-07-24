package com.wavesplatform.state.diffs

import com.wavesplatform.account.Address
import com.wavesplatform.block.Block
import com.wavesplatform.common.utils.EitherExt2.*
import com.wavesplatform.db.WithDomain
import com.wavesplatform.db.WithState.AddrWithBalance
import com.wavesplatform.features.BlockchainFeatures
import com.wavesplatform.history.Domain.*
import com.wavesplatform.lagonaki.mocks.TestBlock
import com.wavesplatform.settings.TestFunctionalitySettings
import com.wavesplatform.state.*
import com.wavesplatform.test.*
import com.wavesplatform.test.DomainPresets.*
import com.wavesplatform.transaction.Asset.Waves
import com.wavesplatform.transaction.lease.{LeaseCancelTransaction, LeaseTransaction}
import com.wavesplatform.transaction.transfer.*
import com.wavesplatform.transaction.{TxHelpers, TxVersion}

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
    val sender    = TxHelpers.signer(2)
    val recipient = TxHelpers.signer(3)
    val miner     = TestBlock.defaultSigner.toAddress
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
      assertDiffAndState(Seq(TestBlock.create(Seq(lease))), TestBlock.create(Seq(leaseCancel)), balances = senderBalance) { case (snapshot, _) =>
        snapshot.balances shouldBe VectorMap(
          (miner, Waves)            -> (lease.fee.value + leaseCancel.fee.value),
          (sender.toAddress, Waves) -> (ENOUGH_AMT - lease.fee.value - leaseCancel.fee.value)
        )
        snapshot.leaseBalances shouldBe Map(
          sender.toAddress    -> LeaseBalance.empty,
          recipient.toAddress -> LeaseBalance.empty
        )
      }
    }
  }

  private val repeatedCancelAllowed   = allowMultipleLeaseCancelTransactionUntilTimestamp - 1
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
      assertDiffEi(Seq(TestBlock.create(ts, Seq(lease))), TestBlock.create(ts, Seq(leaseForward)), settings, masterBalance) { snapshotEi =>
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

  property("can cancel lease of another sender and acquire leasing power before allowMultipleLeaseCancelTransactionUntilTimestamp") {
    cancelLeaseOfAnotherSender(unleaseByRecipient = false, repeatedCancelAllowed).foreach { case (genesis, lease, unleaseOther, blockTime) =>
      withDomain(ScriptsAndSponsorship, genesis) { d =>
        d.appendBlock(lease)
        d.appendBlock(TestBlock.create(blockTime, d.lastBlockId, Seq(unleaseOther)).block)
        d.liquidSnapshot.balances.get((lease.sender.toAddress, Waves)) shouldBe None
        val recipient = lease.recipient.asInstanceOf[Address]
        val unleaser  = unleaseOther.sender.toAddress
        total(d.liquidSnapshot.leaseBalances(recipient)) shouldBe total(d.rocksDBWriter.leaseBalance(recipient)) - lease.amount.value
        total(d.liquidSnapshot.leaseBalances(unleaser)) shouldBe total(d.rocksDBWriter.leaseBalance(unleaser)) + lease.amount.value
      }
    }
  }

  property(s"can pay for cancel lease from the returning funds (before and after BlockV5)") {
    fail()
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
      d.blockchainUpdater.processBlock(d.createBlock(Seq(lt))) should produce("Cannot lease more than own")
    }
  }
}
