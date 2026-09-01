package tech.hearth

import tech.hearth.account.PublicKey
import tech.hearth.common.state.ByteStr
import tech.hearth.transaction.Proofs
import org.scalatest.enablers.Emptiness
import org.scalatest.matchers.{HavePropertyMatchResult, HavePropertyMatcher}
import play.api.libs.functional.syntax.*
import play.api.libs.json.*

import scala.util.{Failure, Success}

package object http {
  def sameSignature(target: Array[Byte])(actual: Array[Byte]): Boolean = target sameElements actual

  implicit def tuple2ToHPM(v: (String, JsValue)): HavePropertyMatcher[JsValue, JsValue] =
    HavePropertyMatcher[JsValue, JsValue] { json =>
      val actualFieldValue = (json \ v._1).as[JsValue]
      HavePropertyMatchResult(actualFieldValue == v._2, v._1, v._2, actualFieldValue)
    }

  implicit val byteStrFormat: Format[ByteStr] = Format(
    Reads {
      case JsString(str) =>
        ByteStr.decodeBase16(str) match {
          case Success(x) => JsSuccess(x)
          case Failure(e) => JsError(e.getMessage)
        }

      case _ => JsError("Can't read PublicKey")
    },
    Writes(tech.hearth.utils.byteStrFormat.writes)
  )

  implicit val PublicKeyFormat: Format[PublicKey] = byteStrFormat.inmap[PublicKey](
    x => PublicKey(x.arr),
    x => ByteStr(x.arr)
  )

  implicit val proofsFormat: Format[Proofs] = Format(
    Reads {
      case JsArray(xs) =>
        xs.foldLeft[JsResult[Proofs]](JsSuccess(Proofs.empty)) {
          case (r: JsError, _) => r
          case (JsSuccess(r, _), JsString(rawProof)) =>
            ByteStr.decodeBase16(rawProof) match {
              case Failure(e) => JsError(e.toString)
              case Success(x) => JsSuccess(Proofs(r.proofs :+ x))
            }
          case _ => JsError("Can't parse proofs")
        }
      case _ => JsError("Can't parse proofs")
    },
    Writes { proofs =>
      JsArray(proofs.proofs.map(byteStrFormat.writes))
    }
  )

  implicit val emptyJsLookupResult: Emptiness[JsLookupResult] = r => r.isEmpty
}
