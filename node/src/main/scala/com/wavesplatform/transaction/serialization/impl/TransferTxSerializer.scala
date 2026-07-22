package com.wavesplatform.transaction.serialization.impl

import com.wavesplatform.transaction.transfer.TransferTransaction
import com.wavesplatform.utils.byteStrFormat
import play.api.libs.json.{JsObject, Json}

object TransferTxSerializer {
  def toJson(tx: TransferTransaction): JsObject = {
    import tx.*
    BaseTxJson.toJson(tx) ++ Json.obj(
      "recipient"  -> recipient.toBech32,
      "assetId"    -> assetId.maybeBase58Repr,
      "feeAsset"   -> feeAssetId.maybeBase58Repr, // legacy v0.11.1 compat
      "amount"     -> amount.value,
      "attachment" -> attachment,
      "version"    -> tx.version
    )
  }

  def bodyBytes(tx: TransferTransaction): Array[Byte] =
    PBTransactionSerializer.bodyBytes(tx)

  def toBytes(tx: TransferTransaction): Array[Byte] =
    PBTransactionSerializer.bytes(tx)
}
