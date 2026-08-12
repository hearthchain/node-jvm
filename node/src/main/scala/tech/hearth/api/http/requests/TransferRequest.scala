package tech.hearth.api.http.requests

import tech.hearth.account.PublicKey
import tech.hearth.common.state.ByteStr
import tech.hearth.lang.ValidationError
import tech.hearth.transaction.transfer.*
import tech.hearth.transaction.transfer.TransferTransaction.Transfer
import tech.hearth.transaction.{Asset, Proofs}
import play.api.libs.functional.syntax.*
import play.api.libs.json.*

object TransferRequest {
  given Format[TransferRequest] = Format(
    (
      (JsPath \ "senderPublicKey").read[String] and
        (JsPath \ "assetId").readNullable[Asset] and
        (JsPath \ "transfers").read[List[Transfer]] and
        (JsPath \ "fee").read[Long] and
        (JsPath \ "feeAssetId").readNullable[Asset] and
        (JsPath \ "timestamp").read[Long] and
        (JsPath \ "attachment").readWithDefault(ByteStr.empty) and
        (JsPath \ "proofs").readWithDefault(Proofs.empty)
    )(TransferRequest.apply),
    Json.writes[TransferRequest].transform((jsobj: JsObject) => jsobj + ("type" -> JsNumber(TransferTransaction.typeId.toInt)))
  )
}

case class TransferRequest(
    senderPublicKey: String,
    assetId: Option[Asset],
    transfers: List[Transfer],
    fee: Long,
    feeAssetId: Option[Asset],
    timestamp: Long,
    attachment: ByteStr = ByteStr.empty,
    proofs: Proofs
) extends TxBroadcastRequest[TransferTransaction] {
  def toTx: Either[ValidationError, TransferTransaction] =
    for {
      _sender    <- PublicKey.fromBase16String(senderPublicKey)
      _transfers <- TransferTransaction.parseTransfersList(transfers)
      t <- TransferTransaction.create(
        _sender,
        assetId.getOrElse(Asset.Hearth),
        _transfers,
        fee,
        timestamp,
        attachment,
        proofs,
        feeAssetId = feeAssetId.getOrElse(Asset.Hearth)
      )
    } yield t
}
