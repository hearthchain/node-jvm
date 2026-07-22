package com.wavesplatform.transaction.serialization.impl

import com.wavesplatform.transaction.transfer.MassTransferTransaction
import com.wavesplatform.transaction.transfer.MassTransferTransaction.{ParsedTransfer, Transfer}
import com.wavesplatform.utils.byteStrFormat
import play.api.libs.json.{JsObject, JsValue, Json}

object MassTransferTxSerializer {
  def transfersJson(transfers: Seq[ParsedTransfer]): JsValue =
    Json.toJson(transfers.map { case ParsedTransfer(address, amount) => Transfer(address.toBech32, amount.value) })

  def toJson(tx: MassTransferTransaction): JsObject = {
    import tx.*
    BaseTxJson.toJson(tx) ++ Json.obj(
      "assetId"       -> assetId.maybeBase16Repr,
      "attachment"    -> attachment,
      "transferCount" -> transfers.size,
      "totalAmount"   -> transfers.map(_.amount.value).sum,
      "transfers"     -> transfersJson(transfers),
      "version"       -> version
    )
  }

  def bodyBytes(tx: MassTransferTransaction): Array[Byte] = PBTransactionSerializer.bodyBytes(tx)
  def toBytes(tx: MassTransferTransaction): Array[Byte]   = PBTransactionSerializer.bytes(tx)

}
