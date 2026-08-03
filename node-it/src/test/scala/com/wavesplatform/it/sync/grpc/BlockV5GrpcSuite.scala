package com.wavesplatform.it.sync.grpc

import com.google.protobuf.ByteString
import com.typesafe.config.Config
import com.wavesplatform.account.PublicKey
import com.wavesplatform.api.grpc.BlockRangeRequest
import com.wavesplatform.block.Block
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.crypto
import com.wavesplatform.it.api.SyncGrpcApi.*
import com.wavesplatform.it.sync.activation.ActivationStatusRequest
import com.wavesplatform.it.{GrpcIntegrationSuiteWithThreeAddress, NodeConfigs}
import org.scalatest.*

import scala.concurrent.duration.*

class BlockV5GrpcSuite extends freespec.AnyFreeSpec with ActivationStatusRequest with OptionValues with GrpcIntegrationSuiteWithThreeAddress {

  import NodeConfigs.{Default, notMiner, quorum}

  // withDefault(1).withSpecial(1, _.nonMiner).buildNonConflicting() always assigns the lowest-index
  // NonConflictingNodes entry (node01) as the miner - the lowest-balance miner-eligible account in the whole
  // fixture (see CLAUDE.md's node-it fixtures notes), whose PoS delay for the very first block intermittently
  // exceeded GrpcIntegrationSuiteWithThreeAddress's 50s beforeAll waitForHeight(2) timeout. Default(6) (node07) is
  // far enough ahead in balance to reach it comfortably.
  override def nodeConfigs: Seq[Config] =
    Seq(
      Default(6).quorum(0),
      Default(0).quorum(0).notMiner
    )

  "block v5 appears and blockchain grows" - {
    "when feature activation happened" in {
      sender.waitForHeight(sender.height + 1, 2.minutes)
      val currentHeight = sender.height

      val blockV5     = sender.blockAt(currentHeight)
      val blockV5ById = sender.blockById(ByteString.copyFrom(blockV5.id().arr))

      blockV5.id().arr.length shouldBe crypto.DigestLength
      blockV5.signature.arr.length shouldBe crypto.SignatureLength
      blockV5.header.generationSignature.arr.length shouldBe Block.GenerationVRFSignatureLength
      assert(blockV5.signatureValid(), "transactionsRoot is not valid")
      blockV5ById.header.generationSignature.arr.length shouldBe Block.GenerationVRFSignatureLength
      assert(blockV5ById.signatureValid(), "transactionsRoot is not valid")

      sender.waitForHeight(currentHeight + 1, 2.minutes)

      val blockAfterVRFUsing     = sender.blockAt(currentHeight + 1)
      val blockAfterVRFUsingById = sender.blockById(ByteString.copyFrom(blockAfterVRFUsing.id().arr))

      blockAfterVRFUsing.header.generationSignature.arr.length shouldBe Block.GenerationVRFSignatureLength
      ByteStr(sender.blockHeaderAt(currentHeight + 1).reference.toByteArray) shouldBe blockV5.id()
      blockAfterVRFUsingById.header.generationSignature.arr.length shouldBe Block.GenerationVRFSignatureLength
      assert(blockAfterVRFUsingById.signatureValid(), "transactionsRoot is not valid")

      val blockSeqOfBlocksV5 = sender.blockSeq(currentHeight, currentHeight + 2)

      for (blockV5 <- blockSeqOfBlocksV5) {
        blockV5.header.generationSignature.arr.length shouldBe Block.GenerationVRFSignatureLength
        assert(blockV5.signatureValid(), "transactionsRoot is not valid")
      }

      val blockSeqOfBlocksV5ByAddress = sender.blockSeqByAddress(miner.address, currentHeight, currentHeight + 2)

      for (blockV5 <- blockSeqOfBlocksV5ByAddress) {
        blockV5.header.generator shouldBe PublicKey(miner.keyPair.publicKey())
        blockV5.header.generationSignature.arr.length shouldBe Block.GenerationVRFSignatureLength
        assert(blockV5.signatureValid(), "transactionsRoot is not valid")
      }

      val blockSeqOfBlocksV5ByPKGrpc = NodeExtGrpc(sender).blockSeq(
        currentHeight,
        currentHeight + 2,
        BlockRangeRequest.Filter.GeneratorPublicKey(ByteString.copyFrom(miner.keyPair.publicKey()))
      )

      for (blockV5 <- blockSeqOfBlocksV5ByPKGrpc) {
        blockV5.header.generator shouldBe PublicKey(miner.keyPair.publicKey())
        blockV5.header.generationSignature.arr.length shouldBe Block.GenerationVRFSignatureLength
        assert(blockV5.signatureValid(), "transactionsRoot is not valid")
      }
    }
  }
}
