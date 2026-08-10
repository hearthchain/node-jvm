package tech.hearth.api.http

import cats.Applicative
import tech.hearth.common.state.ByteStr
import tech.hearth.crypto.{DigestLength, SignatureLength}
import tech.hearth.lang.ValidationError
import tech.hearth.transaction.Asset.{IssuedAsset, Hearth}
import tech.hearth.transaction.TxValidationError.{GenericError, Validation}
import tech.hearth.transaction.{Asset, AssetIdStringLength, Proofs, TxValidationError}
import tech.hearth.utils.base16Length
import play.api.libs.json.*

package object requests {
  import cats.instances.list.*
  import cats.syntax.either.*
  import cats.syntax.traverse.*

  val SignatureStringLength: Int = base16Length(SignatureLength)
  val DigestStringLength: Int    = base16Length(DigestLength)

  def parseBase16(v: String, error: String, maxLength: Int): Validation[ByteStr] =
    if (v.length > maxLength) Left(TxValidationError.GenericError(error))
    else ByteStr.decodeBase16(v).toOption.toRight(TxValidationError.GenericError(error))

  def parseBase16(v: Option[String], error: String, maxLength: Int): Validation[ByteStr] =
    v.fold[Either[ValidationError, ByteStr]](Right(ByteStr(Array.emptyByteArray)))(_v => parseBase16(_v, error, maxLength))

  def parseBase16ToOption(v: Option[String], error: String, maxLength: Int): Validation[Option[ByteStr]] =
    v.fold[Either[ValidationError, Option[ByteStr]]](Right(None)) { s =>
      parseBase16(s, error, maxLength).map(b => Option(b))
    }

  def parseBase16ToIssuedAsset(v: String): Validation[IssuedAsset] =
    parseBase16(v, "invalid.assetId", AssetIdStringLength)
      .map(IssuedAsset(_))

  def parseBase16ToAsset(v: Option[String], err: String): Validation[Asset] =
    parseBase16ToOption(v.filter(_.nonEmpty), err, AssetIdStringLength)
      .map {
        case Some(str) => IssuedAsset(str)
        case None      => Hearth
      }

  def toProofs(maybeSignature: Option[ByteStr], maybeProofs: Option[Proofs]): Validation[Proofs] =
    (maybeSignature, maybeProofs) match {
      case (Some(sig), Some(proofs)) if proofs.nonEmpty && proofs.head != sig =>
        Left(GenericError("Both proofs and signature are provided, but proofs do not match signature"))
      case _ =>
        maybeProofs
          .orElse(maybeSignature.map(s => Proofs(List(s))))
          .fold[Either[ValidationError, Proofs]](Proofs.empty.asRight)(p => Proofs.create(p))
    }

  implicit val jsResultApplicative: Applicative[JsResult] = new Applicative[JsResult] {
    override def pure[A](x: A): JsResult[A] = JsSuccess(x)

    override def ap[A, B](ff: JsResult[A => B])(fa: JsResult[A]): JsResult[B] = (ff, fa) match {
      case (JsSuccess(f, _), JsSuccess(a, _)) => JsSuccess(f(a))
      case (JsError(e1), JsError(e2))         => JsError(JsError.merge(e1, e2))
      case (JsError(e), _)                    => JsError(e)
      case (_, JsError(e))                    => JsError(e)
    }
  }

  implicit val proofsReads: Reads[Proofs] = Reads {
    case JsArray(values) =>
      values.toList
        .traverse {
          case JsString(v) =>
            JsSuccess(v).flatMap(s => ByteStr.decodeBase16(s).fold(e => JsError(JsonValidationError("invalid.base16", e.getMessage)), JsSuccess(_)))
          case _ => JsError("expected.string")
        }
        .flatMap(Proofs.create(_) match {
          case Right(value) => JsSuccess(value)
          case Left(err)    => JsError(JsonValidationError("invalid.proofs", err.toString))
        })
    case JsNull => JsSuccess(Proofs.empty)
    case _      => JsError("invalid.proofs")
  }

  implicit val proofsWrites: Writes[Proofs] = Writes { proofs =>
    JsArray(proofs.map(s => JsString(s.toString)))
  }

  implicit val byteStrFormat: Format[ByteStr] = tech.hearth.utils.byteStrFormat

  private[requests] def defaultTimestamp = System.currentTimeMillis()
}
