package tech.hearth.state

import cats.syntax.either.*
import tech.hearth.common.state.ByteStr
import tech.hearth.common.utils.Base16
import tech.hearth.crypto.dcap.{IntelPki, JsonRawValue}
import play.api.libs.json.{JsValue, Json}

import java.security.PublicKey
import java.time.Instant
import scala.util.Try

/** Verification shared by every path that can set DCAP collateral: the permissionless UpdateCollateralTransaction
  * (see the StartBoost consensus plan) and PredefinedSnapshot, which seeds a network's initial collateral at
  * genesis - everything required to run the chain (including the Root CA CRL later issuer-chain verifications
  * depend on for revocation checking) is set up there, not left to arrive from the first block onward.
  *
  * Each function is pure given the blockchain to read the currently-stored value from: parse, verify the Intel
  * signature, and reject a payload that is not at least as fresh as what's already stored (a stale resubmission
  * downgrading, say, a revoked TCB Info back to one that reads UpToDate).
  */
object DcapCollateral {

  /** Root CA CRL is self-issued by the pinned Intel Root CA directly - no issuer chain needed, unlike every other
    * field below.
    */
  def verifyRootCaCrl(payload: ByteStr, blockchain: Blockchain, atTime: Long): Either[String, ByteStr] =
    for {
      newNumber <- IntelPki.verifyCrl(payload.arr, IntelPki.rootCaPublicKey, atTime)
      storedNumber = blockchain.dcapRootCaCrl.flatMap(stored => IntelPki.crlNumberOf(stored.arr))
      _ <- rejectDowngrade(newNumber, storedNumber)
    } yield payload

  /** pckCrl is signed by whichever PCK Platform/Processor CA issued it, an intermediate under Root CA - so its
    * issuer's public key has to come from issuerChainPayload (verified up to the pinned Root CA, checked for
    * revocation against the already-on-chain Root CA CRL) rather than from a fixed constant.
    */
  def verifyPckCrl(
      payload: ByteStr,
      issuerChainPayload: Option[ByteStr],
      blockchain: Blockchain,
      atTime: Long
  ): Either[String, ByteStr] =
    for {
      issuerKey <- resolvePckCaKey(issuerChainPayload, blockchain, atTime)
      newNumber <- IntelPki.verifyCrl(payload.arr, issuerKey, atTime)
      storedNumber = blockchain.dcapPckCrl.flatMap(stored => IntelPki.crlNumberOf(stored.arr))
      _ <- rejectDowngrade(newNumber, storedNumber)
    } yield payload

  /** The PCK CA issuer chain itself: verified and stored so a pckCrl update on its own (without resubmitting the
    * chain every time) can still resolve an issuer, the same way tcbSigningIssuerChain does for tcbInfo/qeIdentity.
    */
  def verifyPckCaIssuerChain(payload: ByteStr, blockchain: Blockchain, atTime: Long): Either[String, ByteStr] =
    verifyIssuerChainPayload(payload, blockchain, atTime)

  /** tcbInfo and qeIdentity are both signed by the TCB Signing CA, an intermediate under Root CA like the PCK CA -
    * this is that chain, submitted/stored the same way pckCaIssuerChain is for pckCrl.
    */
  def verifyTcbSigningIssuerChain(payload: ByteStr, blockchain: Blockchain, atTime: Long): Either[String, ByteStr] =
    verifyIssuerChainPayload(payload, blockchain, atTime)

  private def verifyIssuerChainPayload(payload: ByteStr, blockchain: Blockchain, atTime: Long): Either[String, ByteStr] =
    resolveRootCaCrlForRevocation(blockchain).flatMap { crls =>
      IntelPki.verifyIssuerChain(payload.arr, atTime, crls).map(_ => payload)
    }

  /** tcbInfo is signed JSON (Intel PCS' "TCB Info V3" format): `{"tcbInfo":{... "fmspc":.., "tcbEvaluationDataNumber":
    * .., "issueDate":.., "nextUpdate":..},"signature":<raw r||s, hex>}`. The signature covers the exact raw bytes of
    * the nested "tcbInfo" object as served (see JsonRawValue), not a reserialized copy. Storage is keyed by fmspc
    * (read out of the payload itself, not a separate field - see the StartBoost consensus plan), so freshness is
    * compared against whatever is already stored for that specific platform model, not any other one.
    */
  def verifyTcbInfo(
      payload: ByteStr,
      issuerChainPayload: Option[ByteStr],
      blockchain: Blockchain,
      atTime: Long
  ): Either[String, (ByteStr, ByteStr)] =
    for {
      json      <- parseJson(payload.arr)
      tcbInfo   <- field(json, "tcbInfo")
      fmspcHex  <- stringField(tcbInfo, "fmspc")
      fmspc     <- Base16.tryDecode(fmspcHex).toEither.left.map(e => s"invalid fmspc: ${e.getMessage}").map(ByteStr(_))
      newNumber <- verifySignedJson(payload, "tcbInfo", issuerChainPayload, blockchain, atTime, parsedJson = Some(json))
      storedNumber = blockchain.dcapTcbInfo(fmspc).flatMap(evalNumberOf(_, "tcbInfo"))
      _ <- rejectDowngrade(Some(newNumber), storedNumber)
    } yield (fmspc, payload)

  /** qeIdentity is the same signed-JSON shape as tcbInfo (Intel PCS' "Enclave Identity V2" format, top-level key
    * "enclaveIdentity"), a single slot rather than per-fmspc.
    */
  def verifyQeIdentity(
      payload: ByteStr,
      issuerChainPayload: Option[ByteStr],
      blockchain: Blockchain,
      atTime: Long
  ): Either[String, ByteStr] =
    for {
      newNumber <- verifySignedJson(payload, "enclaveIdentity", issuerChainPayload, blockchain, atTime)
      storedNumber = blockchain.dcapQeIdentity.flatMap(evalNumberOf(_, "enclaveIdentity"))
      _ <- rejectDowngrade(Some(newNumber), storedNumber)
    } yield payload

  /** Verifies the signature and validity window of a `{"<topKey>":{...},"signature":<hex>}` payload and returns the
    * nested object's own tcbEvaluationDataNumber - Intel's counter for exactly this purpose, incremented on every
    * republish including a pure TCB-recovery event with no other visible change. `parsedJson` lets a caller that
    * already parsed `payload` for its own purposes (verifyTcbInfo, reading fmspc) pass that along instead of this
    * function parsing the same bytes a second time.
    */
  private def verifySignedJson(
      payload: ByteStr,
      topKey: String,
      issuerChainPayload: Option[ByteStr],
      blockchain: Blockchain,
      atTime: Long,
      parsedJson: Option[JsValue] = None
  ): Either[String, BigInt] =
    for {
      raw          <- JsonRawValue.extractObject(payload.arr, topKey)
      json         <- parsedJson.map(_.asRight[String]).getOrElse(parseJson(payload.arr))
      obj          <- field(json, topKey)
      evalNumber   <- longField(obj, "tcbEvaluationDataNumber")
      _            <- checkValidityWindow(obj, atTime)
      signatureHex <- stringField(json, "signature")
      signature    <- Base16.tryDecode(signatureHex).toEither.left.map(e => s"invalid signature encoding: ${e.getMessage}")
      issuerKey    <- resolveTcbSigningKey(issuerChainPayload, blockchain, atTime)
      _            <- IntelPki.verifyRawSignature(raw, signature, issuerKey)
    } yield BigInt(evalNumber)

  private def evalNumberOf(stored: ByteStr, topKey: String): Option[BigInt] =
    (for {
      json       <- parseJson(stored.arr)
      obj        <- field(json, topKey)
      evalNumber <- longField(obj, "tcbEvaluationDataNumber")
    } yield BigInt(evalNumber)).toOption

  private def checkValidityWindow(obj: JsValue, atTime: Long): Either[String, Unit] =
    for {
      issueDate  <- instantField(obj, "issueDate")
      nextUpdate <- instantField(obj, "nextUpdate")
      at = Instant.ofEpochMilli(atTime)
      _ <- Either.raiseWhen(at.isBefore(issueDate))(s"not valid yet: issueDate $issueDate is after $at")
      _ <- Either.raiseWhen(at.isAfter(nextUpdate))(s"expired: nextUpdate $nextUpdate is before $at")
    } yield ()

  private def resolveTcbSigningKey(
      issuerChainPayload: Option[ByteStr],
      blockchain: Blockchain,
      atTime: Long
  ): Either[String, PublicKey] =
    resolveIssuerKey(issuerChainPayload, blockchain.dcapTcbSigningIssuerChain, blockchain, atTime)

  private def resolvePckCaKey(
      issuerChainPayload: Option[ByteStr],
      blockchain: Blockchain,
      atTime: Long
  ): Either[String, PublicKey] =
    resolveIssuerKey(issuerChainPayload, blockchain.dcapPckCaIssuerChain, blockchain, atTime)

  private def resolveIssuerKey(
      issuerChainPayload: Option[ByteStr],
      storedChain: Option[ByteStr],
      blockchain: Blockchain,
      atTime: Long
  ): Either[String, PublicKey] =
    issuerChainPayload
      .orElse(storedChain)
      .toRight("no issuer chain submitted or already on chain")
      .flatMap(chain => resolveRootCaCrlForRevocation(blockchain).flatMap(crls => IntelPki.verifyIssuerChain(chain.arr, atTime, crls)))

  private def resolveRootCaCrlForRevocation(blockchain: Blockchain): Either[String, Seq[Array[Byte]]] =
    blockchain.dcapRootCaCrl
      .toRight("Root CA CRL must be set (in genesis or by an earlier UpdateCollateral) before an issuer chain can be verified")
      .map(crl => Seq(crl.arr))

  /** Fails closed when the submission's own freshness counter can't be determined - a CRL missing its (RFC 5280
    * optional, but always present on a real Intel CRL) CRL Number extension can't be proven at least as fresh as
    * whatever is already stored, so it must be rejected rather than silently accepted as if it were fresher.
    * `storedNumber` staying `None` is not the same case: that means nothing is stored yet, and the very first
    * submission for a field is never rejected as a "downgrade".
    */
  private def rejectDowngrade(newNumber: Option[BigInt], storedNumber: Option[BigInt]): Either[String, Unit] =
    (newNumber, storedNumber) match {
      case (None, _)                   => Left("cannot verify freshness: submitted collateral has no freshness counter")
      case (Some(n), Some(s)) if n < s => Left(s"stale update: number $n is behind the stored $s")
      case _                           => Right(())
    }

  private def parseJson(bytes: Array[Byte]): Either[String, JsValue] =
    Try(Json.parse(bytes)).toEither.left.map(e => s"invalid JSON: ${e.getMessage}")

  private def field(json: JsValue, key: String): Either[String, JsValue] =
    (json \ key).toOption.toRight(s"missing '$key'")

  private def stringField(json: JsValue, key: String): Either[String, String] =
    (json \ key).asOpt[String].toRight(s"missing or invalid '$key'")

  private def longField(json: JsValue, key: String): Either[String, Long] =
    (json \ key).asOpt[Long].toRight(s"missing or invalid '$key'")

  private def instantField(json: JsValue, key: String): Either[String, Instant] =
    for {
      s <- stringField(json, key)
      i <- Try(Instant.parse(s)).toEither.left.map(_ => s"invalid '$key': $s")
    } yield i
}
