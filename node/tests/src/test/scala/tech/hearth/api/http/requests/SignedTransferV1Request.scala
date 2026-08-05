package tech.hearth.api.http.requests

import tech.hearth.account.{Address, PublicKey}
import tech.hearth.lang.ValidationError
import tech.hearth.transaction.Proofs
import tech.hearth.transaction.transfer.*
import play.api.libs.functional.syntax.*
import play.api.libs.json.*

object SignedTransferV1Request {
  implicit val reads: Reads[SignedTransferV1Request] = (
    (JsPath \ "senderPublicKey").read[String] and
      (JsPath \ "assetId").readNullable[String] and
      (JsPath \ "recipient").read[String] and
      (JsPath \ "amount").read[Long] and
      (JsPath \ "fee").read[Long] and
      (JsPath \ "feeAssetId").read[String].map(Option.apply).orElse((JsPath \ "feeAsset").readNullable[String]) and
      (JsPath \ "timestamp").read[Long] and
      (JsPath \ "attachment").readNullable[String] and
      (JsPath \ "signature").read[String]
  )(SignedTransferV1Request.apply)

  implicit val writes: Writes[SignedTransferV1Request] = Json.writes[SignedTransferV1Request]
}

case class SignedTransferV1Request(
    senderPublicKey: String,
    assetId: Option[String],
    recipient: String,
    amount: Long,
    fee: Long,
    feeAssetId: Option[String],
    timestamp: Long,
    attachment: Option[String],
    signature: String
) {
  def toTx: Either[ValidationError, TransferTransaction] =
    for {
      _sender <- PublicKey.fromBase16String(senderPublicKey)
      _assetId <- parseBase16ToAsset(
        assetId,
        "invalid.assetId"
      ) // parseBase16ToOption(assetId.filter(_.length > 0), "invalid.assetId", transaction.AssetIdStringLength).map(AssetId.fromCompatId)
      _feeAssetId <- parseBase16ToAsset(
        feeAssetId,
        "invalid.feeAssetId"
      ) // parseBase16ToOption(feeAssetId.filter(_.length > 0), "invalid.feeAssetId", transaction.AssetIdStringLength).map(AssetId.fromCompatId)
      _signature  <- parseBase16(signature, "invalid.signature", SignatureStringLength)
      _attachment <- parseBase16(attachment.filter(_.length > 0), "invalid.attachment", TransferTransaction.MaxAttachmentStringSize)
      _account    <- Address.fromString(recipient)
      tx <- TransferTransaction.create(
        _sender,
        _account,
        _assetId,
        amount,
        _feeAssetId,
        fee,
        _attachment,
        timestamp,
        Proofs(_signature)
      )
    } yield tx
}
