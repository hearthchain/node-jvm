package com.wavesplatform.mining

import com.wavesplatform.block.serialization.{BlockHeaderSerializer, BlockSerializer}
import com.wavesplatform.block.validation.Validators
import com.wavesplatform.block.{Block, BlockHeader}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2.*
import com.wavesplatform.crypto.DigestLength
import com.wavesplatform.db.WithState.AddrWithBalance
import com.wavesplatform.history.defaultSigner
import com.wavesplatform.lagonaki.mocks.TestBlock
import com.wavesplatform.protobuf.block.PBBlocks
import com.wavesplatform.state.BlockchainUpdaterImpl.BlockApplyResult.Applied
import com.wavesplatform.state.diffs
import com.wavesplatform.test.{FlatSpec, *}
import com.wavesplatform.transaction.TxHelpers
import com.wavesplatform.{BlocksTransactionsHelpers, crypto, protobuf}
import org.scalatest.*
import tech.hearth.crypto.SigningKey

import scala.concurrent.duration.*

class BlockV5Test extends FlatSpec with WithMiner with OptionValues with EitherValues with BlocksTransactionsHelpers {
  private def shiftTime(miner: MinerImpl, minerAcc: SigningKey, time: TestTime): Unit = {
    val offset = miner.nextBlockGenerationOffsets.getOrElse(minerAcc.toAddress, Left("No delay")).explicitGet()
    time.advance(offset + 1.milli)
  }

  "Proto block" should "be serialized" in {
    val stateHash = ByteStr.fill(DigestLength)(1)
    val features  = Seq(534, 3, 33, 5, 1, 0, 12343242).map(_.toShort)
    val block =
      Block
        .buildAndSign(
          Block.ProtoBlockVersion,
          System.currentTimeMillis(),
          TestBlock.randomSignature(),
          2L,
          ByteStr(Array.fill(Block.GenerationVRFSignatureLength)(0: Byte)),
          Seq.empty,
          defaultSigner,
          features.sorted,
          rewardVote = -1,
          Some(stateHash),
          challengedHeader = None,
          finalizationVoting = None
        )
        .explicitGet()

    def updateHeader(block: Block, f: BlockHeader => BlockHeader): Block =
      block.copy(header = f(block.header))

    val blockWithBadVotes = updateHeader(block, _.copy(featureVotes = features))

    withClue("validations") {
      Validators.validateBlock(blockWithBadVotes).left.value
      Validators.validateBlock(block).explicitGet()
      Validators
        .validateBlock(
          updateHeader(block, _.copy(generationSignature = ByteStr(new Array[Byte](32))))
        )
        .left
        .value
      Validators
        .validateBlock(
          updateHeader(block, _.copy(featureVotes = Seq[Short](1, 1, 1)))
        )
        .left
        .value
      Validators.validateBlock(updateHeader(block, _.copy(stateHash = Some(ByteStr.fill(DigestLength - 1)(1))))).left.value
    }

    withClue("preserve feature order") {
      val serialized1        = BlockSerializer.toBytes(blockWithBadVotes)
      val deserialized1      = PBBlocks.vanilla(protobuf.block.PBBlock.parseFrom(serialized1)).get
      val serialized2        = PBBlocks.protobuf(blockWithBadVotes).toByteArray
      val deserialized2      = PBBlocks.vanilla(protobuf.block.PBBlock.parseFrom(serialized2)).get
      val serializedHeader   = BlockHeaderSerializer.toBytes(blockWithBadVotes.header)
      val deserializedHeader = PBBlocks.vanilla(protobuf.block.PBBlockHeader.parseFrom(serializedHeader))
      serialized1 shouldBe serialized2
      all(Seq(deserialized1, deserialized2)) should matchPattern {
        case b: Block if !b.signatureValid() => // Pass
      }
      all(Seq(deserialized1, deserialized2)) shouldBe blockWithBadVotes
      deserializedHeader shouldBe blockWithBadVotes.header
    }

    withClue("signature valid") {
      val serialized1        = BlockSerializer.toBytes(block)
      val deserialized1      = PBBlocks.vanilla(protobuf.block.PBBlock.parseFrom(serialized1)).get
      val serialized2        = PBBlocks.protobuf(block).toByteArray
      val deserialized2      = PBBlocks.vanilla(protobuf.block.PBBlock.parseFrom(serialized2)).get
      val serializedHeader   = BlockHeaderSerializer.toBytes(block.header)
      val deserializedHeader = PBBlocks.vanilla(protobuf.block.PBBlockHeader.parseFrom(serializedHeader))
      serialized1 shouldBe serialized2
      all(Seq(deserialized1, deserialized2)) should matchPattern {
        case b: Block if b.signatureValid() => // Pass
      }
      all(Seq(deserialized1, deserialized2)) shouldBe block
      deserializedHeader shouldBe block.header
    }
  }

  // Pre-v5 blocks no longer exist: the consensus data always carries a VRF proof, so every forged block is a proto
  // (v5) block from genesis onwards. The old tests that walked the chain across BlockV5/FairPoS/NG activation heights,
  // or produced legacy block versions, tested a timeline that cannot happen any more and were removed.
  "Miner" should "generate valid proto blocks" in {
    val minerAcc = TxHelpers.defaultSigner
    val settings = DomainPresets.DeterministicFinality
    withDomainAndMiner(
      settings.copy(minerSettings = settings.minerSettings.copy(quorum = 0)),
      Seq(AddrWithBalance(minerAcc.toAddress, diffs.ENOUGH_AMT)),
      miningAccounts = Seq(MiningAccount(minerAcc, TxHelpers.defaultVrfKey))
    ) { (d, miner, append) =>
      (1 to 10).foreach { _ =>
        shiftTime(miner, minerAcc, d.testTime)
        val block = miner.forgeBlock(minerAcc, TxHelpers.defaultVrfKey).toEither.explicitGet().newBlock
        block.header.version shouldBe Block.ProtoBlockVersion
        append(block).explicitGet() shouldBe an[Applied]
      }
      d.blockchain.height shouldBe 11
    }
  }

  "BlockchainUpdater" should "accept a key block and its microblocks" in
    // DeterministicFinality is active in every preset now, so the miner has to be a committed generator
    withDomain(
      DomainPresets.TransactionStateSnapshot,
      Seq(AddrWithBalance(TxHelpers.defaultAddress, diffs.ENOUGH_AMT)),
      generators = Seq(TxHelpers.defaultSigner)
    ) { d =>
      val keyBlock = d.appendKeyBlock()
      keyBlock.header.version shouldBe Block.ProtoBlockVersion

      val transfer1 = TxHelpers.transfer()
      val transfer2 = TxHelpers.transfer()
      d.appendMicroBlock(transfer1)
      d.appendMicroBlock(transfer2)

      d.blockchain.transactionInfo(transfer1.id()) should not be empty
      d.blockchain.transactionInfo(transfer2.id()) should not be empty
    }

  "blockId" should "be the proto header hash" in
    // DeterministicFinality is active in every preset now, so the miner has to be a committed generator
    withDomain(
      DomainPresets.TransactionStateSnapshot,
      Seq(AddrWithBalance(TxHelpers.defaultAddress, diffs.ENOUGH_AMT)),
      generators = Seq(TxHelpers.defaultSigner)
    ) { d =>
      // Every block is a proto block, so its id is the hash of its (protobuf-serialized) header, DigestLength long,
      // rather than the block signature
      val block1 = d.appendKeyBlock()
      block1.id().arr.length shouldBe crypto.DigestLength
      block1.id() shouldBe Block.protoHeaderHash(block1.header)

      val block2 = d.appendKeyBlock()
      block2.header.reference shouldBe block1.id()
    }
}
