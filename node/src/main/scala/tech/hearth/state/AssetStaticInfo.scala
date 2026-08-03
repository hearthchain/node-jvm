package tech.hearth.state

import tech.hearth.account.PublicKey
import tech.hearth.common.state.ByteStr
import play.api.libs.json.{Format, Json, OWrites}

case class AssetStaticInfo(id: ByteStr, source: TransactionId, issuer: PublicKey, decimals: Int, nft: Boolean)

object AssetStaticInfo {
  implicit val byteStrFormat: Format[ByteStr]   = tech.hearth.utils.byteStrFormat
  implicit val format: OWrites[AssetStaticInfo] = Json.writes[AssetStaticInfo]
}
