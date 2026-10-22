package tech.hearth.crypto.dcap

import cats.syntax.either.*
import cats.syntax.traverse.*
import tech.hearth.common.state.ByteStr
import tech.hearth.common.utils.Base16
import tech.hearth.crypto.dcap.TdxTcbInfo.{TcbLevel, TcbStatus}
import play.api.libs.json.{JsLookupResult, JsValue, Json, Reads}

import java.time.Instant
import scala.util.Try

/** Reading Intel's signed-JSON collateral (TCB Info, QE Identity) for evaluation. The payloads were verified when
  * they were put on chain (DcapCollateral), so this checks their structure only.
  */
private[dcap] object DcapJson {
  def parse(payload: Array[Byte], topKey: String): Either[String, JsLookupResult] =
    Try(Json.parse(payload)).toEither.bimap(e => s"invalid JSON: ${e.getMessage}", _ \ topKey)

  def read[T: Reads](json: JsLookupResult, name: String): Either[String, T] =
    json.validate[T].asEither.leftMap(_ => s"missing or invalid '$name'")

  def hex(json: JsLookupResult, name: String): Either[String, ByteStr] =
    read[String](json, name).flatMap(s => Base16.tryDecode(s).toEither.bimap(_ => s"invalid '$name': $s", ByteStr(_)))

  def instant(json: JsLookupResult, name: String): Either[String, Instant] =
    read[String](json, name).flatMap(s => Try(Instant.parse(s)).toEither.leftMap(_ => s"invalid '$name': $s"))

  def array(json: JsLookupResult, name: String): Either[String, Seq[JsValue]] = read[Seq[JsValue]](json, name)

  /** A TCB level's status, which has to be one `valid` lists for the kind of level it rates (QVL fails closed on any
    * other), and its advisories.
    */
  def tcbLevel(json: JsValue, valid: Set[TcbStatus]): Either[String, TcbLevel] =
    for {
      name   <- read[String](json \ "tcbStatus", "tcbStatus")
      status <- TcbStatus.parse(name).filterOrElse(valid.contains, s"Unexpected TCB status $name")
    } yield TcbLevel(status, (json \ "advisoryIDs").asOpt[Seq[String]].getOrElse(Seq.empty))

  def components(json: JsLookupResult, name: String, count: Int): Either[String, Seq[Int]] =
    array(json, name)
      .flatMap(_.traverse(c => read[Int](c \ "svn", s"$name svn")))
      .filterOrElse(_.size == count, s"'$name' must have $count components")

  def masked(value: ByteStr, mask: ByteStr): ByteStr = ByteStr(value.arr.zip(mask.arr).map((v, m) => (v & m).toByte))
}
