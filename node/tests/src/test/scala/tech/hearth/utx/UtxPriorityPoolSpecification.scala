package tech.hearth.utx

import tech.hearth.db.WithState
import tech.hearth.mining.MultiDimensionalMiningConstraint
import tech.hearth.settings.HearthSettings
import tech.hearth.test.*
import tech.hearth.transaction.TxHelpers
import tech.hearth.utx.UtxPool.PackStrategy

class UtxPriorityPoolSpecification extends FreeSpec with SharedDomain {
  private val alice = TxHelpers.signer(100)

  private var lastKeyPair = 0
  private def nextKeyPair = {
    lastKeyPair += 1
    TxHelpers.signer(lastKeyPair)
  }

  override val genesisBalances: Seq[WithState.AddrWithBalance] = Seq(alice -> 10000.hearth)

  override def settings: HearthSettings = DomainPresets.RideV3

  private def pack() = domain.utxPool.packUnconfirmed(MultiDimensionalMiningConstraint.Unlimited, None, PackStrategy.Unlimited)._1

  "priority pool" - {
    "preserves correct order of transactions" in {
      val id = domain.appendKeyBlock().id()
      val t1 = TxHelpers.transfer(alice, nextKeyPair.toAddress, fee = 0.001.hearth)
      val t2 = TxHelpers.transfer(alice, nextKeyPair.toAddress, fee = 0.01.hearth, timestamp = t1.timestamp - 10000)

      domain.appendMicroBlock(t1)
      domain.appendMicroBlock(t2)
      domain.appendKeyBlock(ref = Some(id))

      val expectedTransactions = Seq(t1, t2)
      domain.utxPool.all shouldBe expectedTransactions
      pack() shouldBe Some(expectedTransactions)
    }

    "tx from last microblock is placed on next height ahead of new txs after appending key block" in {
      domain.utxPool.removeAll(domain.utxPool.nonPriorityTransactions)
      val blockId = domain.appendKeyBlock().id()
      val tx      = TxHelpers.transfer(alice)

      domain.appendMicroBlock(tx)
      domain.blockchain.transactionInfo(tx.id()) shouldBe defined
      domain.utxPool.all shouldBe Nil

      domain.appendKeyBlock(ref = Some(blockId))
      domain.blockchain.transactionInfo(tx.id()) shouldBe None
      domain.utxPool.all shouldBe Seq(tx)

      val secondTx = TxHelpers.transfer(alice, fee = 2.hearth)
      domain.utxPool.putIfNew(secondTx)
      pack() shouldBe Some(List(tx, secondTx))
    }
  }
}
