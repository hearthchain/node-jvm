package tech.hearth.transaction.serialization.impl

import tech.hearth.transaction.ReserveTransaction
import play.api.libs.json.{JsObject, Json}

object ReserveTxSerializer {
  def toJson(tx: ReserveTransaction): JsObject = {
    import tx.*
    BaseTxJson.toJson(tx) ++ Json.obj(
      "assetId" -> assetId.id.toString,
      "amount"  -> amount.value,
      "miner"   -> miner.toString
    )
  }
}
