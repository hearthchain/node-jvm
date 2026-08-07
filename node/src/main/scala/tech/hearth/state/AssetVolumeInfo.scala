package tech.hearth.state

import play.api.libs.json.{Json, OFormat}

case class AssetVolumeInfo(volume: BigInt)

object AssetVolumeInfo {
  implicit val format: OFormat[AssetVolumeInfo] = Json.format
}
