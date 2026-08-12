package tech.hearth.transaction.serialization.impl

import tech.hearth.transaction.transfer.TransferTransaction
import tech.hearth.transaction.transfer.TransferTransaction.{ParsedTransfer, Transfer}
import tech.hearth.utils.byteStrFormat
import play.api.libs.json.{JsObject, JsValue, Json}

object TransferTxSerializer {
  def transfersJson(transfers: Seq[ParsedTransfer]): JsValue =
    Json.toJson(transfers.map { case ParsedTransfer(address, amount) => Transfer(address.toBech32, amount.value) })

  def toJson(tx: TransferTransaction): JsObject = {
    import tx.*
    BaseTxJson.toJson(tx) ++ Json.obj(
      "assetId"       -> assetId.maybeBase16Repr,
      "attachment"    -> attachment,
      "transferCount" -> transfers.size,
      "totalAmount"   -> transfers.map(_.amount.value).sum,
      "transfers"     -> transfersJson(transfers)
    )
  }

  def bodyBytes(tx: TransferTransaction): Array[Byte] = PBTransactionSerializer.bodyBytes(tx)
  def toBytes(tx: TransferTransaction): Array[Byte]   = PBTransactionSerializer.bytes(tx)
}
