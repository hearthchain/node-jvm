package tech.hearth.transaction.serialization.impl

import tech.hearth.transaction.lease.LeaseTransaction
import play.api.libs.json.{JsObject, Json}

object LeaseTxSerializer {
  def toJson(tx: LeaseTransaction): JsObject = {
    import tx.*
    BaseTxJson.toJson(tx) ++ Json.obj(
      "amount"    -> amount.value,
      "recipient" -> recipient.toString
    )
  }
}
