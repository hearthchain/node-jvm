package com.wavesplatform.transaction.serialization.impl

import com.wavesplatform.transaction.TxVersion
import com.wavesplatform.transaction.lease.LeaseCancelTransaction
import play.api.libs.json.{JsObject, Json}

object LeaseCancelTxSerializer {
  def toJson(tx: LeaseCancelTransaction): JsObject =
    BaseTxJson.toJson(tx) ++ Json.obj(
      "leaseId" -> tx.leaseId.toString,
      "chainId" -> tx.chainId,
      "version" -> tx.version
    )

  def bodyBytes(tx: LeaseCancelTransaction): Array[Byte] =
    PBTransactionSerializer.bodyBytes(tx)

  def toBytes(tx: LeaseCancelTransaction): Array[Byte] =
    PBTransactionSerializer.bytes(tx)
}
