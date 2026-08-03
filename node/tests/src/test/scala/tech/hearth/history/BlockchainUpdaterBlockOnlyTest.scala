package tech.hearth.history

import tech.hearth.db.WithDomain
import tech.hearth.db.WithState.AddrWithBalance
import tech.hearth.state.diffs.ENOUGH_AMT
import tech.hearth.test.*
import tech.hearth.transaction.TxHelpers
import tech.hearth.crypto.SigningKey

class BlockchainUpdaterBlockOnlyTest extends PropSpec, WithDomain {

  private val master: SigningKey             = TxHelpers.signer(200)
  private def balances: Seq[AddrWithBalance] = Seq(AddrWithBalance(master.toAddress, ENOUGH_AMT))

  property("can apply valid blocks") {
    withDomain(balances = balances) { d =>
      d.appendKeyBlock()
      d.appendMicroBlockE(TxHelpers.transfer(master)) should beRight
    }
  }

  property("can apply, rollback and reprocess valid blocks") {
    withDomain(balances = balances) { d =>
      val genesisId = d.lastBlockId
      val block1    = d.appendKeyBlock()
      d.blockchain.height shouldBe 2
      val block2 = d.appendKeyBlock()
      d.blockchain.height shouldBe 3

      d.blockchainUpdater.removeAfter(genesisId) should beRight
      d.blockchain.height shouldBe 1

      d.appendBlockE(block1) should beRight
      d.appendBlockE(block2) should beRight
    }
  }

  property("can't apply block with invalid signature") {
    withDomain(balances = balances) { d =>
      val block = d.createBlock(Nil, ref = Some(d.lastBlockId))
      d.appendBlockE(spoilSignature(block)) should produce("invalid signature")
    }
  }

  property("can't apply block with invalid signature after rollback") {
    withDomain(balances = balances) { d =>
      val block1 = d.appendKeyBlock()
      val block2 = d.appendKeyBlock()
      d.blockchainUpdater.removeAfter(block1.id()) should beRight
      d.appendBlockE(spoilSignature(block2)) should produce("invalid signature")
    }
  }

  property("can process 10 blocks and then rollback to genesis") {
    withDomain(balances = balances) { d =>
      val genesisId = d.lastBlockId
      (1 to 10).foreach(_ => d.appendKeyBlock())
      d.blockchain.height shouldBe 11

      d.blockchainUpdater.removeAfter(genesisId) should beRight
      d.blockchain.height shouldBe 1
    }
  }
}
