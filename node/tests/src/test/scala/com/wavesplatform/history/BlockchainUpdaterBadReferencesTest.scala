package com.wavesplatform.history

import com.wavesplatform.block.Block
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2.*
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

  /** Straight to the updater, the way the micro block properties go through `processMicroBlock`. `Domain.appendBlockE`
    * resolves the reference itself first, to verify the VRF proof against the parent's hit source, and fails with
    * `history does not contain parent` before the updater is ever asked.
    */
  private def process(d: Domain, block: Block) =
    d.blockchainUpdater.processBlock(block, block.header.generationSignature, snapshot = None, generatorSet = Seq.empty)

  /** A block cannot be built with a bad reference in the first place: `d.createBlock` computes the state hash against
    * the referenced block, and `BlockDiffer` refuses a reference that is not the head of the blockchain it is given.
    * Build a valid block and retarget it instead - the updater checks the reference before the differ ever runs, so the
    * block only has to be well formed and signed.
    */
  private def retargetedTo(reference: ByteStr)(block: Block): Block =
    Block
      .buildAndSign(
        block.header.timestamp,
        reference,
        block.header.baseTarget,
        block.header.generationSignature,
        block.transactionData,
        TxHelpers.defaultSigner,
        block.header.featureVotes,
        block.header.stateHash,
        block.header.challengedHeader,
        block.header.finalizationVoting
      )
      .explicitGet()

  property("microBlock: referenced (micro)block doesn't exist") {
    withDomain(balances = balances) { d =>
      d.appendBlock(payment)      // liquid base block
      d.appendMicroBlock(payment) // good micro
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
      val badBlock = retargetedTo(randomSig)(d.createBlock(Seq(payment)))
      process(d, badBlock) should produce("References incorrect or non-existing block")
    }
  }

  property("block: incorrect or non-existing block when liquid is empty") {
    withDomain(balances = balances) { d =>
      d.appendBlock()
      val block0Id = d.lastBlockId
      d.appendBlock()
      d.blockchainUpdater.removeAfter(block0Id) should beRight // hardens block0, drops the liquid block
      val badBlock = retargetedTo(randomSig)(d.createBlock(Seq(payment)))
      process(d, badBlock) should produce("References incorrect or non-existing block")
    }
  }

  property("block: incorrect or non-existing block when liquid exists") {
    withDomain(balances = balances) { d =>
      val genesisId = d.lastBlockId
      d.appendBlock()
      d.appendBlock()
      // References the genesis block, i.e. two blocks back, so it is neither the liquid block's parent nor known
      val badBlock = retargetedTo(genesisId)(d.createBlock(Seq(payment)))
      process(d, badBlock) should produce("References incorrect or non-existing block")
    }
  }
}
