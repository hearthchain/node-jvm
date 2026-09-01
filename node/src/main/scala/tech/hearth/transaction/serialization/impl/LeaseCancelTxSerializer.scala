package tech.hearth.transaction.serialization.impl

import tech.hearth.transaction.lease.LeaseCancelTransaction
import play.api.libs.json.{JsObject, Json}

object LeaseCancelTxSerializer {
  def toJson(tx: LeaseCancelTransaction): JsObject =
    BaseTxJson.toJson(tx) ++ Json.obj(
      "leaseId" -> tx.leaseId.toString
    )

  def bodyBytes(tx: LeaseCancelTransaction): Array[Byte] =
    PBTransactionSerializer.bodyBytes(tx)

  def toBytes(tx: LeaseCancelTransaction): Array[Byte] =
    PBTransactionSerializer.bytes(tx)
}
