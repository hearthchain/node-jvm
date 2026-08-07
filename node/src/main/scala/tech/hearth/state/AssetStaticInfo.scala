package tech.hearth.state

import com.google.protobuf.ByteString
import tech.hearth.account.PublicKey
import tech.hearth.common.state.ByteStr
import play.api.libs.json.{Format, JsString, Json, OWrites, Writes}

case class AssetStaticInfo(id: ByteStr, issuer: PublicKey, decimals: Int, nft: Boolean, name: ByteString, description: ByteString)

object AssetStaticInfo {
  implicit val byteStrFormat: Format[ByteStr]       = tech.hearth.utils.byteStrFormat
  implicit val byteStringWrites: Writes[ByteString] = Writes(bs => JsString(bs.toStringUtf8))
  implicit val format: OWrites[AssetStaticInfo]     = Json.writes[AssetStaticInfo]
}
