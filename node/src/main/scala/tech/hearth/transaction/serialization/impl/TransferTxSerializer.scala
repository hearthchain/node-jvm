package tech.hearth.transaction.serialization.impl

import tech.hearth.transaction.transfer.TransferTransaction
import tech.hearth.utils.byteStrFormat
import play.api.libs.json.{JsObject, Json}

object TransferTxSerializer {
  def toJson(tx: TransferTransaction): JsObject = {
    import tx.*
    BaseTxJson.toJson(tx) ++ Json.obj(
      "recipient"  -> recipient.toBech32,
      "assetId"    -> assetId.maybeBase16Repr,
      "feeAsset"   -> feeAssetId.maybeBase16Repr, // legacy v0.11.1 compat
      "amount"     -> amount.value,
      "attachment" -> attachment
    )
  }

  def bodyBytes(tx: TransferTransaction): Array[Byte] =
    PBTransactionSerializer.bodyBytes(tx)

  def toBytes(tx: TransferTransaction): Array[Byte] =
    PBTransactionSerializer.bytes(tx)
}
