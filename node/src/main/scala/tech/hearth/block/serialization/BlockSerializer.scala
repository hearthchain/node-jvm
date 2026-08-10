package tech.hearth.block.serialization

import tech.hearth.account.PublicKey
import tech.hearth.block.{Block, BlockHeader, FinalizationVoting}
import tech.hearth.common.state.ByteStr
import tech.hearth.protobuf.block.PBBlocks
import tech.hearth.protobuf.utils.PBUtils
import tech.hearth.state.{GeneratorIndex, Height}
import tech.hearth.transaction.Asset.Hearth
import tech.hearth.transaction.Transaction
import play.api.libs.json.{JsArray, JsNumber, JsObject, Json}

object BlockHeaderSerializer {
  def toBytes(header: BlockHeader): Array[Byte] =
    PBUtils.encodeDeterministic(PBBlocks.protobuf(header))

  def toJson(blockHeader: BlockHeader): JsObject = {
    def createFeaturesJson(featureVotes: Seq[Short]): JsObject =
      Json.obj("features" -> JsArray(featureVotes.map(id => JsNumber(id.toInt))))

    def createGeneratorJson(generator: PublicKey): JsObject =
      Json.obj("generator" -> generator.toAddress.toString, "generatorPublicKey" -> generator)

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
        "transactionsRoot"    -> blockHeader.transactionsRoot.toString,
        "id"                  -> Block.protoHeaderHash(blockHeader).toString,
        "baseTarget"          -> blockHeader.baseTarget,
        "generationSignature" -> blockHeader.generationSignature.toString
      )

    val featuresJson  = createFeaturesJson(blockHeader.featureVotes)
    val generatorJson = createGeneratorJson(blockHeader.generator)
    val stateHashJson = createStateHashJson(blockHeader.stateHash)

    val challengedHeaderJson =
      blockHeader.challengedHeader match {
        case Some(ch) =>
          Json.obj(
            "challengedHeader" -> {
              Json.obj(
                "headerSignature" -> ch.headerSignature.toString
              ) ++ createFeaturesJson(ch.featureVotes) ++
                createGeneratorJson(ch.generator) ++
                createStateHashJson(ch.stateHash) ++
                createFinalizationJson(ch.finalizationVoting)
            }
          )
        case None => JsObject.empty
      }

    val finalizationHeaderJson = createFinalizationJson(blockHeader.finalizationVoting)

    Json.obj(
      "timestamp" -> blockHeader.timestamp,
      "reference" -> blockHeader.reference.toString
    ) ++ consensusJson ++ featuresJson ++ generatorJson ++ stateHashJson ++ challengedHeaderJson ++ finalizationHeaderJson
  }

  def toJson(header: BlockHeader, blockSize: Int, transactionCount: Int, signature: ByteStr): JsObject =
    toJson(header) ++ Json.obj(
      "signature"        -> signature.toString,
      "blocksize"        -> blockSize,
      "transactionCount" -> transactionCount
    )
}

object BlockSerializer {
  def toBytes(block: Block): Array[Byte] =
    PBUtils.encodeDeterministic(PBBlocks.protobuf(block))

  def transactionField(transactions: Seq[Transaction]): JsObject = Json.obj(
    "fee"          -> transactions.map(_.assetFee).collect { case (Hearth, feeAmt) => feeAmt }.sum,
    "transactions" -> JsArray(transactions.map(_.json()))
  )

  def toJson(block: Block): JsObject =
    BlockHeaderSerializer.toJson(block.header, block.bytes().length, block.transactionData.length, block.signature) ++
      transactionField(block.transactionData)
}
