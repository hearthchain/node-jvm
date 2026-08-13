package tech.hearth.transaction.serialization.impl

import tech.hearth.transaction.SettleTransaction
import play.api.libs.json.{JsObject, Json}

object SettleTxSerializer {
  def toJson(tx: SettleTransaction): JsObject = {
    import tx.*
    BaseTxJson.toJson(tx) ++ Json.obj(
      "senderAddress" -> senderAddress.toString
    )
  }
}
