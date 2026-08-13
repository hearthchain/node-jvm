package tech.hearth.history

import tech.hearth.db.WithDomain
import tech.hearth.db.WithState.AddrWithBalance
import tech.hearth.state.diffs.ENOUGH_AMT
import tech.hearth.test.*
import tech.hearth.transaction.TxHelpers
import tech.hearth.crypto.SigningKey

class BlockchainUpdaterInMemoryDiffTest extends PropSpec, WithDomain {

  private val master: SigningKey             = TxHelpers.signer(200)
  private def balances: Seq[AddrWithBalance] = Seq(AddrWithBalance(master.toAddress, ENOUGH_AMT))

  property("compaction with liquid block doesn't make liquid block affect state once") {
    withDomain(balances = balances) { d =>
      val payment1 = TxHelpers.transfer(master)
      val payment2 = TxHelpers.transfer(master)

      // Empty key blocks up to the compaction threshold, then payment1 in a microblock on the last liquid block
      (1 until MaxTransactionsPerBlockDiff * 2).foreach(_ => d.appendKeyBlock())
      d.appendKeyBlock()
      d.appendMicroBlock(payment1)

      d.balance(master.toAddress) shouldBe (ENOUGH_AMT - payment1.transfers.head.amount.value - payment1.fee.value)
      d.blockchain.height shouldBe MaxTransactionsPerBlockDiff * 2 + 1

      // The next key block hardens the liquid block (with payment1) and triggers compaction
      d.appendKeyBlock()
      d.appendMicroBlock(payment2)

      d.blockchain.height shouldBe MaxTransactionsPerBlockDiff * 2 + 2
      d.balance(master.toAddress) shouldBe
        (ENOUGH_AMT - payment1.transfers.head.amount.value - payment1.fee.value - payment2.transfers.head.amount.value - payment2.fee.value)
    }
  }

  property("compaction without liquid block doesn't make liquid block affect state once") {
    withDomain(balances = balances) { d =>
      val payment1 = TxHelpers.transfer(master)
      val payment2 = TxHelpers.transfer(master)

      (1 until MaxTransactionsPerBlockDiff * 2 - 1).foreach(_ => d.appendKeyBlock())
      d.appendKeyBlock()
      d.appendMicroBlock(payment1)
      val payment1BlockId = d.lastBlockId

      d.appendKeyBlock() // hardens the payment1 block, leaving an empty liquid block on top
      d.balance(master.toAddress) shouldBe (ENOUGH_AMT - payment1.transfers.head.amount.value - payment1.fee.value)

      // Discard that liquid block, so compaction happens without one
      d.blockchainUpdater.removeAfter(payment1BlockId) should beRight
      d.appendKeyBlock()
      d.appendMicroBlock(payment2)

      d.blockchain.height shouldBe MaxTransactionsPerBlockDiff * 2 + 1
      d.balance(master.toAddress) shouldBe
        (ENOUGH_AMT - payment1.transfers.head.amount.value - payment1.fee.value - payment2.transfers.head.amount.value - payment2.fee.value)
    }
  }
}
