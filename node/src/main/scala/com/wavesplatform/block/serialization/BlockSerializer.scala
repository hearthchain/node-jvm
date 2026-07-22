package com.wavesplatform.block.serialization

import com.wavesplatform.account.PublicKey
import com.wavesplatform.block.Block.ProtoBlockVersion
import com.wavesplatform.block.{Block, BlockHeader, FinalizationVoting}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.protobuf.block.PBBlocks
import com.wavesplatform.protobuf.utils.PBUtils
import com.wavesplatform.state.{GeneratorIndex, Height}
import com.wavesplatform.transaction.Asset.Waves
import com.wavesplatform.transaction.Transaction
import play.api.libs.json.{JsArray, JsNumber, JsObject, Json}

object BlockHeaderSerializer {
  def toBytes(header: BlockHeader): Array[Byte] =
    PBUtils.encodeDeterministic(PBBlocks.protobuf(header))

  def toJson(blockHeader: BlockHeader): JsObject = {
    def createFeaturesJson(featureVotes: Seq[Short]): JsObject =
      Json.obj("features" -> JsArray(featureVotes.map(id => JsNumber(id.toInt))))

    def createGeneratorJson(generator: PublicKey): JsObject =
      Json.obj("generator" -> generator.toAddress.toString, "generatorPublicKey" -> generator)

    def createRewardVoteJson(rewardVote: Long): JsObject =
      Json.obj("desiredReward" -> JsNumber(rewardVote))

    def createStateHashJson(stateHash: Option[ByteStr]): JsObject =
      stateHash match {
        case Some(sh) => Json.obj("stateHash" -> sh.toString)
        case None     => JsObject.empty
      }

    def createFinalizationJson(finalizationVoting: Option[FinalizationVoting]): JsObject = finalizationVoting match {
      case None => JsObject.empty
      case Some(fv) =>
        val builder = Json.newBuilder
        if (fv.valid.nonEmpty) builder += "endorserIndexes" -> GeneratorIndex.toInts(fv.valid)
        fv.aggregatedEndorsement.foreach(s => builder += "aggregatedEndorsementSignature" -> s.base16)
        if (fv.finalizedHeight > Height(0)) builder += "finalizedHeight" -> fv.finalizedHeight
        if (fv.conflict.nonEmpty) builder += "conflictEndorsements" -> fv.conflict.map { c =>
          Json.obj(
            "endorserIndex"    -> c.endorserIndex.toInt,
            "finalizedBlockId" -> c.finalizedId.toString,
            "finalizedHeight"  -> c.finalizedHeight.toInt,
            "signature"        -> c.signature.base16
          )
        }

        Json.obj("finalizationVoting" -> builder.result())
    }

    val consensusJson =
      Json.obj(
        "nxt-consensus" -> Json.obj(
          "base-target"          -> blockHeader.baseTarget,
          "generation-signature" -> blockHeader.generationSignature.toString
        )
      ) ++ (if (blockHeader.version >= ProtoBlockVersion)
              Json.obj("transactionsRoot" -> blockHeader.transactionsRoot.toString, "id" -> Block.protoHeaderHash(blockHeader).toString)
            else Json.obj())

    val featuresJson  = createFeaturesJson(blockHeader.featureVotes)
    val rewardJson    = createRewardVoteJson(blockHeader.rewardVote)
    val generatorJson = createGeneratorJson(blockHeader.generator)
    val stateHashJson = createStateHashJson(blockHeader.stateHash)

    val challengedHeaderJson =
      blockHeader.challengedHeader match {
        case Some(ch) =>
          Json.obj(
            "challengedHeader" -> {
              Json.obj(
                "headerSignature" -> ch.headerSignature.toString
              ) ++ createFeaturesJson(ch.featureVotes) ++ createGeneratorJson(ch.generator) ++ createRewardVoteJson(
                ch.rewardVote
              ) ++ createStateHashJson(ch.stateHash) ++ createFinalizationJson(ch.finalizationVoting)
            }
          )
        case None => JsObject.empty
      }

    val finalizationHeaderJson = createFinalizationJson(blockHeader.finalizationVoting)

    Json.obj(
      "version"   -> blockHeader.version,
      "timestamp" -> blockHeader.timestamp,
      "reference" -> blockHeader.reference.toString
    ) ++ consensusJson ++ featuresJson ++ rewardJson ++ generatorJson ++ stateHashJson ++ challengedHeaderJson ++ finalizationHeaderJson
  }

  def toJson(header: BlockHeader, blockSize: Int, transactionCount: Int, signature: ByteStr): JsObject =
    toJson(header) ++ Json.obj(
      "signature"        -> signature.toString,
      "blocksize"        -> blockSize,
      "transactionCount" -> transactionCount
    ) ++ (if (header.version < ProtoBlockVersion) Json.obj("id" -> signature.toString) else Json.obj())
}

object BlockSerializer {
  def toBytes(block: Block): Array[Byte] =
    PBUtils.encodeDeterministic(PBBlocks.protobuf(block))

  def transactionField(transactions: Seq[Transaction]): JsObject = Json.obj(
    "fee"          -> transactions.map(_.assetFee).collect { case (Waves, feeAmt) => feeAmt }.sum,
    "transactions" -> JsArray(transactions.map(_.json()))
  )

  def toJson(block: Block): JsObject =
    BlockHeaderSerializer.toJson(block.header, block.bytes().length, block.transactionData.length, block.signature) ++
      transactionField(block.transactionData)
}
