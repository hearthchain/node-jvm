package com.wavesplatform.history

import com.wavesplatform.db.WithDomain
import com.wavesplatform.db.WithState.AddrWithBalance
import com.wavesplatform.state.diffs.ENOUGH_AMT
import com.wavesplatform.test.*
import com.wavesplatform.transaction.TxHelpers
import com.wavesplatform.transaction.transfer.TransferTransaction
import tech.hearth.crypto.SigningKey

class BlockchainUpdaterBadReferencesTest extends PropSpec, WithDomain {

  private val master: SigningKey             = TxHelpers.signer(200)
  private def balances: Seq[AddrWithBalance] = Seq(AddrWithBalance(master.toAddress, ENOUGH_AMT))
  // Fresh each call, so repeated uses within a test do not collide on transaction id
  private def payment: TransferTransaction = TxHelpers.transfer(master)

  property("microBlock: referenced (micro)block doesn't exist") {
    withDomain(balances = balances) { d =>
      d.appendBlock(payment)          // liquid base block
      d.appendMicroBlock(payment)     // good micro
      val badMicroRef = d.createMicroBlock()(payment).copy(reference = randomSig)
      d.appendMicroBlockE(badMicroRef) should produce("doesn't reference last known microBlock")
    }
  }

  property("microblock: first micro doesn't reference base block (references nothing)") {
    withDomain(balances = balances) { d =>
      d.appendBlock(payment)
      val badMicroRef = d.createMicroBlock()(payment).copy(reference = randomSig)
      d.appendMicroBlockE(badMicroRef) should produce("doesn't reference base block")
    }
  }

  property("microblock: first micro doesn't reference base block (references firm block)") {
    withDomain(balances = balances) { d =>
      val genesisId = d.lastBlockId
      d.appendBlock(payment)
      // The first micro on a liquid block has to reference that block, not the firm one below it
      val badMicroRef = d.createMicroBlock()(payment).copy(reference = genesisId)
      d.appendMicroBlockE(badMicroRef) should produce("doesn't reference base block")
    }
  }

  property("microblock: no base block at all") {
    withDomain(balances = balances) { d =>
      val genesisId = d.lastBlockId
      d.appendBlock(payment)
      val micro = d.createMicroBlock()(payment)
      d.blockchainUpdater.removeAfter(genesisId) should beRight // drop the base block the micro extends
      d.appendMicroBlockE(micro) should produce("No base block exists")
    }
  }

  property("microblock: follow-up micro doesn't reference last known micro") {
    withDomain(balances = balances) { d =>
      d.appendBlock(payment)
      val baseId = d.lastBlockId
      d.appendMicroBlock(payment) // good micro
      // The follow-up micro references the base block instead of the previous micro
      val badRefMicro = d.createMicroBlock()(payment).copy(reference = baseId)
      d.appendMicroBlockE(badRefMicro) should produce("doesn't reference last known microBlock")
    }
  }

  property("block: doesn't reference the last block") {
    withDomain(balances = balances) { d =>
      d.appendBlock(payment)
      val badBlock = d.createBlock(Seq(payment), ref = Some(randomSig))
      d.appendBlockE(badBlock) should produce("References incorrect or non-existing block")
    }
  }

  property("block: incorrect or non-existing block when liquid is empty") {
    withDomain(balances = balances) { d =>
      d.appendBlock()
      val block0Id = d.lastBlockId
      d.appendBlock()
      d.blockchainUpdater.removeAfter(block0Id) should beRight // hardens block0, drops the liquid block
      val badBlock = d.createBlock(Seq(payment), ref = Some(randomSig))
      d.appendBlockE(badBlock) should produce("References incorrect or non-existing block")
    }
  }

  property("block: incorrect or non-existing block when liquid exists") {
    withDomain(balances = balances) { d =>
      val genesisId = d.lastBlockId
      d.appendBlock()
      d.appendBlock()
      // References the genesis block, i.e. two blocks back, so it is neither the liquid block's parent nor known
      val badBlock = d.createBlock(Seq(payment), ref = Some(genesisId))
      d.appendBlockE(badBlock) should produce("References incorrect or non-existing block")
    }
  }
}
