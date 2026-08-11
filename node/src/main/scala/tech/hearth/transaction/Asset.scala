package tech.hearth.transaction

import com.google.common.collect.Interners
import tech.hearth.common.state.ByteStr
import tech.hearth.common.utils.Base16
import tech.hearth.transaction.assets.exchange.AssetPair
import play.api.libs.json.*
import pureconfig.ConfigReader
import pureconfig.error.CannotConvert

import scala.util.Success

sealed trait Asset
object Asset {
  final case class IssuedAsset(id: ByteStr) extends Asset {
    override def toString: String = id.toString
    override def hashCode(): Int  = id.hashCode()
  }

  object IssuedAsset {
    private val interner                = Interners.newWeakInterner[IssuedAsset]()
    def apply(id: ByteStr): IssuedAsset = interner.intern(new IssuedAsset(id))
    def fromString[T](str: String, onSuccess: IssuedAsset => T, onFailure: String => T): T =
      Base16.tryDecodeWithLimit(str) match {
        case Success(arr) if arr.length != AssetIdLength => onFailure(s"Invalid validation. Size of asset id $str not equal $AssetIdLength bytes")
        case Success(arr)                                => onSuccess(IssuedAsset(ByteStr(arr)))
        case _                                           => onFailure("Expected base16-encoded assetId")
      }
  }

  case object Hearth extends Asset

  val HearthName = "HRTH"

  implicit val assetReads: Reads[IssuedAsset] = Reads {
    case JsString(str) => IssuedAsset.fromString(str, JsSuccess(_), JsError(_))
    case _             => JsError("Expected base16-encoded assetId")
  }
  implicit val assetWrites: Writes[IssuedAsset] = Writes { asset =>
    JsString(asset.id.toString)
  }

  implicit val assetIdReads: Reads[Asset] = assetReads(false)
  implicit val assetIdWrites: Writes[Asset] = Writes {
    case Hearth          => JsNull
    case IssuedAsset(id) => JsString(id.toString)
  }

  object Formats {
    implicit val assetJsonFormat: Format[IssuedAsset] = Format(assetReads, assetWrites)
    implicit val assetIdJsonFormat: Format[Asset]     = Format(assetIdReads, assetIdWrites)
  }

  implicit val assetConfigReader: ConfigReader[Asset] =
    ConfigReader[String].emap(s => AssetPair.extractAssetId(s).fold(ex => Left(CannotConvert(s, "Asset", ex.getMessage)), Right(_)))

  def fromString(maybeStr: Option[String]): Asset = {
    maybeStr.map(x => IssuedAsset(ByteStr.decodeBase16(x).get)).getOrElse(Hearth)
  }

  def fromCompatId(maybeBStr: Option[ByteStr]): Asset = {
    maybeBStr.fold[Asset](Hearth)(IssuedAsset(_))
  }

  implicit class AssetIdOps(private val ai: Asset) extends AnyVal {
    def byteRepr: Array[Byte] = ai match {
      case Hearth          => Array(0: Byte)
      case IssuedAsset(id) => (1: Byte) +: id.arr
    }

    def compatId: Option[ByteStr] = ai match {
      case Hearth          => None
      case IssuedAsset(id) => Some(id)
    }

    def maybeBase16Repr: Option[String] = ai match {
      case Hearth          => None
      case IssuedAsset(id) => Some(id.toString)
    }

    def fold[A](onHearth: => A)(onAsset: IssuedAsset => A): A = ai match {
      case Hearth                 => onHearth
      case asset @ IssuedAsset(_) => onAsset(asset)
    }
  }

  def assetReads(allowHearthStr: Boolean): Reads[Asset] = Reads {
    case json: JsString =>
      if (json.value.isEmpty || (allowHearthStr && json.value == HearthName)) JsSuccess(Hearth) else assetReads.reads(json)
    case JsNull => JsSuccess(Hearth)
    case _      => JsError("Expected base16-encoded assetId or null")
  }
}
