package tech.hearth

import com.google.common.primitives.UnsignedBytes
import com.google.protobuf.ByteString
import tech.hearth.common.state.ByteStr
import tech.hearth.common.utils.Base16
import play.api.libs.json.*

import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import scala.annotation.tailrec
import scala.util.{Failure, Success}

package object utils {

  def base64Length(byteArrayLength: Int): Int = math.ceil(byteArrayLength * 4 / 3.0).toInt
  def base16Length(byteArrayLength: Int): Int = byteArrayLength * 2

  def forceStopApplication(reason: ApplicationStopReason = Default): Unit =
    System.exit(reason.code)

  def humanReadableSize(bytes: Long, si: Boolean = true): String = {
    val (baseValue, unitStrings) =
      if (si)
        (1000, Vector("B", "kB", "MB", "GB", "TB", "PB", "EB", "ZB", "YB"))
      else
        (1024, Vector("B", "KiB", "MiB", "GiB", "TiB", "PiB", "EiB", "ZiB", "YiB"))

    @tailrec
    def getExponent(curBytes: Long, baseValue: Int, curExponent: Int = 0): Int =
      if (curBytes < baseValue) curExponent
      else {
        val newExponent = 1 + curExponent
        getExponent(curBytes / (baseValue * newExponent), baseValue, newExponent)
      }

    val exponent   = getExponent(bytes, baseValue)
    val divisor    = Math.pow(baseValue, exponent)
    val unitString = unitStrings(exponent)

    f"${bytes / divisor}%.1f $unitString"
  }

  def randomBytes(howMany: Int = 32): Array[Byte] = {
    val r = new Array[Byte](howMany)
    new SecureRandom().nextBytes(r) // overrides r
    r
  }

  def byteArrayFromString[T](v: String, onSuccess: Array[Byte] => T, onFailure: String => T): T =
    Base16.tryDecodeWithLimit(v) match {
      case Success(bytes) => onSuccess(bytes)
      case Failure(_)     => onFailure(s"Can't parse '$v' as base16 encoded byte array")
    }

  val arrayReads: Reads[Array[Byte]] = Reads {
    case JsString(v) => byteArrayFromString(v, JsSuccess(_), JsError(_))
    case _           => JsError("Expected JsString")
  }

  // Always base16, never ByteStr.toString's "base64:" form at 1024+ bytes: the reads side is base16-only, so a large
  // field (a DCAP quote, a collateral blob) written as base64 could not be read back by /transactions/broadcast.
  implicit val byteStrFormat: Format[ByteStr] = new Format[ByteStr] {
    override def writes(o: ByteStr): JsValue = JsString(Base16.encode(o.arr))
    override def reads(json: JsValue): JsResult[ByteStr] = json match {
      case JsString(v) => byteArrayFromString(v, xs => JsSuccess(ByteStr(xs)), JsError(_))
      case _           => JsError("Expected JsString")
    }
  }

  implicit class StringBytes(val s: String) extends AnyVal {
    def utf8Bytes: Array[Byte]   = s.getBytes(StandardCharsets.UTF_8)
    def toByteString: ByteString = ByteString.copyFromUtf8(s)
  }

  implicit val byteStrOrdering: Ordering[ByteStr] = (x, y) => UnsignedBytes.lexicographicalComparator().compare(x.arr, y.arr)
}
