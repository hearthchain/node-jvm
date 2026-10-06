package tech.hearth.transaction.serialization.impl

import tech.hearth.transaction.StakeTransaction
import play.api.libs.json.{JsObject, Json}

object StakeTxSerializer {
  def toJson(tx: StakeTransaction): JsObject = {
    import tx.*
    BaseTxJson.toJson(tx) ++ Json.obj(
      "periodStart" -> periodStart,
      "amount"      -> amount.value
    )
  }
}
