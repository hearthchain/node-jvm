package tech.hearth.api.http.requests

import tech.hearth.account.PublicKey
import tech.hearth.common.state.ByteStr
import tech.hearth.lang.ValidationError
import tech.hearth.transaction.transfer.*
import tech.hearth.transaction.transfer.MassTransferTransaction.Transfer
import tech.hearth.transaction.{Asset, Proofs}
import play.api.libs.functional.syntax.*
import play.api.libs.json.*

object MassTransferRequest {
  given Format[MassTransferRequest] = Format(
    (
      (JsPath \ "senderPublicKey").read[String] and
        (JsPath \ "assetId").readNullable[Asset] and
        (JsPath \ "transfers").read[List[Transfer]] and
        (JsPath \ "fee").read[Long] and
        (JsPath \ "feeAssetId").readNullable[Asset] and
        (JsPath \ "timestamp").read[Long] and
        (JsPath \ "attachment").readWithDefault(ByteStr.empty) and
        (JsPath \ "proofs").readWithDefault(Proofs.empty)
    )(MassTransferRequest.apply),
    Json.writes[MassTransferRequest].transform((jsobj: JsObject) => jsobj + ("type" -> JsNumber(MassTransferTransaction.typeId.toInt)))
  )
}

case class MassTransferRequest(
    senderPublicKey: String,
    assetId: Option[Asset],
    transfers: List[Transfer],
    fee: Long,
    feeAssetId: Option[Asset],
    timestamp: Long,
    attachment: ByteStr = ByteStr.empty,
    proofs: Proofs
) extends TxBroadcastRequest[MassTransferTransaction] {
  def toTx: Either[ValidationError, MassTransferTransaction] =
    for {
      _sender    <- PublicKey.fromBase16String(senderPublicKey)
      _transfers <- MassTransferTransaction.parseTransfersList(transfers)
      t <- MassTransferTransaction.create(
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
