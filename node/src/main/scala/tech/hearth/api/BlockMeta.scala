package tech.hearth.api

import tech.hearth.account.Address
import tech.hearth.block.Block.protoHeaderHash
import tech.hearth.block.serialization.BlockHeaderSerializer
import tech.hearth.block.{Block, BlockHeader, SignedBlockHeader}
import tech.hearth.common.state.ByteStr
import tech.hearth.protobuf.block.PBBlocks
import monix.eval.Coeval
import play.api.libs.json.{JsObject, Json}
import tech.hearth.protobuf.toByteStr

case class BlockMeta(
    header: BlockHeader,
    signature: ByteStr,
    headerHash: Option[ByteStr],
    height: Int,
    size: Int,
    transactionCount: Int,
    totalFeeInHearth: Long,
    reward: Option[Long],
    rewardShares: Seq[(Address, Long)],
    vrf: Option[ByteStr]
) {
  def toSignedHeader: SignedBlockHeader = SignedBlockHeader(header, signature)
  def id: ByteStr                       = headerHash.getOrElse(signature)

  val json: Coeval[JsObject] = Coeval.evalOnce {
    BlockHeaderSerializer.toJson(header, size, transactionCount, signature) ++
      Json.obj("height" -> height, "totalFee" -> totalFeeInHearth) ++
      reward.fold(Json.obj())(r =>
        Json.obj(
          "reward" -> r,
          "rewardShares" -> Json.obj(rewardShares.map[(String, Json.JsValueWrapper)] { case (addrName, reward) =>
            addrName.toString -> reward
          }*)
        )
      ) ++
      vrf.fold(Json.obj())(v => Json.obj("VRF" -> v.toString)) ++
      headerHash.fold(Json.obj())(h => Json.obj("id" -> h.toString))
  }
}

object BlockMeta {
  def fromBlock(block: Block, height: Int, totalFee: Long, reward: Option[Long], vrf: Option[ByteStr]): BlockMeta =
    BlockMeta(
      block.header,
      block.signature,
      Some(protoHeaderHash(block.header)),
      height,
      block.bytes().length,
      block.transactionData.length,
      totalFee,
      reward,
      Seq.empty,
      vrf
    )

  def fromPb(pbMeta: tech.hearth.database.protobuf.BlockMeta): Option[BlockMeta] = {
    pbMeta.header.map { pbHeader =>
      BlockMeta(
        PBBlocks.vanilla(pbHeader),
        pbMeta.signature.toByteStr,
        if (pbMeta.headerHash.isEmpty) None else Some(pbMeta.headerHash.toByteStr),
        pbMeta.height,
        pbMeta.size,
        pbMeta.transactionCount,
        pbMeta.totalFee.collectFirst { case a if a.assetId.isEmpty => a.amount }.getOrElse(0L),
        Some(pbMeta.reward),
        Seq(),
        if (pbMeta.vrf.isEmpty) None
        else Some(pbMeta.vrf.toByteStr)
      )
    }
  }
}
