package tech.hearth.state.reader

import tech.hearth.TestValues
import tech.hearth.db.WithDomain
import tech.hearth.db.WithState.AddrWithBalance
import tech.hearth.lagonaki.mocks.TestBlock.create as block
import tech.hearth.settings.TestFunctionalitySettings.Enabled
import tech.hearth.state.diffs.*
import tech.hearth.state.{BalanceSnapshot, Height, LeaseBalance}
import tech.hearth.test.*
import tech.hearth.transaction.TxHelpers.*
import tech.hearth.transaction.{CommitToGenerationTransaction, Transaction, TxHelpers}

class StateReaderEffectiveBalancePropertyTest extends PropSpec with WithDomain {
  import DomainPresets.*

  property("No-interactions genesis account's effectiveBalance doesn't depend on depths") {
    val master = TxHelpers.signer(1)

    val emptyBlocksAmt = 10
    val confirmations  = 20

    // The master is credited by the genesis snapshot, which is applied to the block at height 1
    val genesisBlock = block(Seq.empty)
    val nextBlocks   = List.fill(emptyBlocksAmt - 1)(block(Seq.empty))
    assertDiffAndState(genesisBlock +: nextBlocks, block(Seq.empty), balances = Seq(AddrWithBalance(master.toAddress))) { (_, newState) =>
      newState.effectiveBalance(master.toAddress, confirmations) shouldBe ENOUGH_AMT
    }
  }

  property("Negative generating balance case") {
    val fs  = Enabled
    val Fee = 100000
    val setup = {
      val master = TxHelpers.signer(1)
      val leaser = TxHelpers.signer(2)

      val xfer1  = TxHelpers.transfer(master, leaser.toAddress, ENOUGH_AMT / 3)
      val lease1 = TxHelpers.lease(leaser, master.toAddress, xfer1.amount.value - Fee, fee = Fee)
      val xfer2  = TxHelpers.transfer(master, leaser.toAddress, ENOUGH_AMT / 3)
      val lease2 = TxHelpers.lease(leaser, master.toAddress, xfer2.amount.value - Fee, fee = Fee)

      (master, leaser, xfer1, lease1, xfer2, lease2)
    }

    val (master, leaser, xfer1, lease1, xfer2, lease2) = setup
    assertDiffAndState(
      Seq(block(Seq()), block(Seq(xfer1, lease1))), // Height 1: carries the genesis snapshot
      block(Seq(xfer2, lease2)),
      fs,
      Seq(AddrWithBalance(master.toAddress))
    ) { (_, state) =>
      val portfolio       = state.hearthPortfolio(lease1.sender.toAddress)
      val expectedBalance = xfer1.amount.value + xfer2.amount.value - 2 * Fee
      portfolio.balance shouldBe expectedBalance
      state.generatingBalance(leaser.toAddress, state.lastBlockId) shouldBe 0
      portfolio.lease shouldBe LeaseBalance(0, expectedBalance)
      portfolio.effectiveBalance(false) shouldBe Right(0)
    }
  }

  property("correct balance snapshots at height = 2") {
    withDomain(RideV6) { d =>
      d.appendBlock()
      d.blockchain.balanceSnapshots(defaultAddress, 1, None) shouldBe List(
        bs(Height(2), regularBalance = 100004.hearth, deposits = 1),
        bs(Height(1), regularBalance = 100000.hearth, deposits = 1)
      )

      // Each round below appends a micro block carrying a 1-ember transfer and then a key block. The transfer and
      // 60% of its fee - the carry the next block collects - land on the liquid block the micro block extends, so the
      // height that was liquid loses `1 + fee * 3 / 5` when the next key block makes it solid.
      //
      // `from` reaches the inner blockchain only from `liquid height - 2` down: asking from the height just below the
      // liquid one gives the liquid snapshot alone. Height 2 is the exception - see `SnapshotBlockchain`.
      val transfer1 = transfer(amount = 1)
      d.appendMicroBlock(transfer1)
      d.appendKeyBlock()
      val bAt2 = 100004.hearth - 1 - transfer1.fee.value * 3 / 5
      d.blockchain.balanceSnapshots(defaultAddress, 1, None) shouldBe List(
        bs(Height(3), regularBalance = 100008.hearth - 1, deposits = 1),
        bs(Height(2), regularBalance = bAt2, deposits = 1),
        bs(Height(1), regularBalance = 100000.hearth, deposits = 1)
      )
      d.blockchain.balanceSnapshots(defaultAddress, 2, None) shouldBe List(
        bs(Height(3), regularBalance = 100008.hearth - 1, deposits = 1)
      )
      d.blockchain.balanceSnapshots(defaultAddress, 3, None) shouldBe List(
        bs(Height(3), regularBalance = 100008.hearth - 1, deposits = 1)
      )

      val transfer2 = transfer(amount = 1)
      d.appendMicroBlock(transfer2)
      d.appendKeyBlock()
      val bAt3 = 100008.hearth - 2 - transfer2.fee.value * 3 / 5
      d.blockchain.balanceSnapshots(defaultAddress, 1, None) shouldBe List(
        bs(Height(4), regularBalance = 100012.hearth - 2, deposits = 1),
        bs(Height(3), regularBalance = bAt3, deposits = 1),
        bs(Height(2), regularBalance = bAt2, deposits = 1),
        bs(Height(1), regularBalance = 100000.hearth, deposits = 1)
      )
      d.blockchain.balanceSnapshots(defaultAddress, 2, None) shouldBe List(
        bs(Height(4), regularBalance = 100012.hearth - 2, deposits = 1),
        bs(Height(3), regularBalance = bAt3, deposits = 1),
        bs(Height(2), regularBalance = bAt2, deposits = 1)
      )
      d.blockchain.balanceSnapshots(defaultAddress, 3, None) shouldBe List(
        bs(Height(4), regularBalance = 100012.hearth - 2, deposits = 1)
      )

      val transfer3 = transfer(amount = 1)
      d.appendMicroBlock(transfer3)
      d.appendKeyBlock()
      val bAt4 = 100012.hearth - 3 - transfer3.fee.value * 3 / 5
      d.blockchain.balanceSnapshots(defaultAddress, 1, None) shouldBe List(
        bs(Height(5), regularBalance = 100016.hearth - 3, deposits = 1),
        bs(Height(4), regularBalance = bAt4, deposits = 1),
        bs(Height(3), regularBalance = bAt3, deposits = 1),
        bs(Height(2), regularBalance = bAt2, deposits = 1),
        bs(Height(1), regularBalance = 100000.hearth, deposits = 1)
      )
      d.blockchain.balanceSnapshots(defaultAddress, 3, None) shouldBe List(
        bs(Height(5), regularBalance = 100016.hearth - 3, deposits = 1),
        bs(Height(4), regularBalance = bAt4, deposits = 1),
        bs(Height(3), regularBalance = bAt3, deposits = 1)
      )
      d.blockchain.balanceSnapshots(defaultAddress, 4, None) shouldBe List(
        bs(Height(5), regularBalance = 100016.hearth - 3, deposits = 1)
      )
    }
  }

  property("correct balance snapshots") {
    val transferTx   = transfer(to = signer(1).toAddress, amount = 3.hearth, fee = 0.1.hearth)
    val leaseTx      = lease(recipient = signer(1).toAddress, amount = 2.hearth, fee = 0.1.hearth)
    val startBalance = 7.hearth
    // defaultSigner generates the blocks below, so it is a committed generator: its deposit is locked on top of what
    // it spends, and shows up in the snapshots as `deposits` rather than as a deduction from the regular balance.
    val genesisBalance = startBalance + CommitToGenerationTransaction.DepositInEmbers

    // 2 txs in 1 a non-genesis block. The miner keeps 4 of the 6 hearth a block pays - the DAO share is deducted.
    val minerReward = 4.hearth
    val feeReward   = (transferTx.fee.value + leaseTx.fee.value) * 2 / 5
    val feeCost     = transferTx.fee.value + leaseTx.fee.value

    withDomain(RideV6, Seq(AddrWithBalance(defaultAddress, genesisBalance))) { d =>
      d.appendBlock(transferTx, leaseTx)
      d.blockchain.balanceSnapshots(defaultAddress, 1, None) shouldBe Seq(
        bs(
          height = Height(2),
          regularBalance = genesisBalance + minerReward + feeReward - feeCost - transferTx.amount.value,
          leaseOut = leaseTx.amount.value,
          deposits = 1
        ),
        bs(
          height = Height(1),
          regularBalance = genesisBalance,
          deposits = 1
        )
      )
    }

    // 1 tx in each of 2 non-genesis blocks, from = 0..1
    (0 to 1).foreach { from =>
      withDomain(RideV6, Seq(AddrWithBalance(defaultAddress, genesisBalance))) { d =>
        d.appendBlock(transferTx)
        d.appendBlock(leaseTx)
        d.blockchain.balanceSnapshots(defaultAddress, from, None) shouldBe Seq(
          bs(
            height = Height(3),
            regularBalance = genesisBalance + 2 * minerReward + leaseTx.fee.value * 2 / 5 - leaseTx.fee.value - transferTx.amount.value,
            // leaseIn = 0, transfer fee is fully compensated by reward ↑
            leaseOut = leaseTx.amount.value,
            deposits = 1
          ),
          bs(
            height = Height(2),
            regularBalance = genesisBalance + minerReward + transferTx.fee.value * 2 / 5 - transferTx.fee.value - transferTx.amount.value,
            deposits = 1
          ),
          bs(
            height = Height(1),
            regularBalance = genesisBalance,
            deposits = 1
          )
        )
      }
    }
  }

  property("correct balance snapshots with deposits") {
    val account1 = TxHelpers.signer(1)
    val address1 = account1.toAddress

    val account2 = TxHelpers.signer(2)

    val initBalance = ENOUGH_AMT

    val settings = DeterministicFinality.configure(_.copy(generationPeriodLength = 3))
    withDomain(settings, balances = AddrWithBalance.enoughBalances(account1, account2), generators = Seq(account2)) { d =>
      def appendBlock(txs: Transaction*): Unit = {
        val block = d.createBlock(txs, strictTime = true, generator = account2)
        d.appender.appendBlock(block)
      }

      appendBlock() // 2

      val generationPeriod1 = d.rocksDBWriter.currentGenerationPeriod.value.next
      appendBlock( // 3
        commitToGeneration(generationPeriodStart = generationPeriod1.start, sender = account1),
        commitToGeneration(generationPeriodStart = generationPeriod1.start, sender = account2)
      )
      appendBlock() // 4

      val generationPeriod2 = generationPeriod1.next
      appendBlock( // 5
        commitToGeneration(generationPeriodStart = generationPeriod2.start, sender = account1),
        commitToGeneration(generationPeriodStart = generationPeriod2.start, sender = account2)
      )
      (6 to 8).foreach(_ => appendBlock()) // 8 in memory

      val inDB = Seq(
        bs(Height(7), regularBalance = initBalance - TestValues.commitToGenerationFee * 2, deposits = 1), // Released the first deposit
        // 6 - Not changed
        bs(Height(5), regularBalance = initBalance - TestValues.commitToGenerationFee * 2, deposits = 2), // CommitToGenerationTransaction
        // 4 - A first block of a new period, not changed
        bs(Height(3), regularBalance = initBalance - TestValues.commitToGenerationFee, deposits = 1), // CommitToGenerationTransaction
        // 2 - Empty block
        bs(Height(1), regularBalance = initBalance) // Genesis
      )

      d.rocksDBWriter.balanceSnapshots(address1, 1, None) shouldBe inDB
      d.blockchain.balanceSnapshots(address1, 1, None) shouldBe
        bs(Height(8), regularBalance = initBalance - TestValues.commitToGenerationFee * 2, deposits = 1) +: // Same as on 7
        inDB
    }
  }

  private def bs(height: Height, regularBalance: Long, leaseIn: Long = 0, leaseOut: Long = 0, deposits: Int = 0): BalanceSnapshot =
    BalanceSnapshot(height, regularBalance, leaseIn, leaseOut, CommitToGenerationTransaction.DepositInEmbers * deposits)
}
