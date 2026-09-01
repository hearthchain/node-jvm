package tech.hearth.transaction.serialization.impl

import tech.hearth.transaction.SettleTransaction
import tech.hearth.transaction.SettleTransaction.Settlement
import play.api.libs.json.{JsObject, JsValue, Json}

object SettleTxSerializer {
  def settlementsJson(settlements: Seq[Settlement]): JsValue =
    Json.toJson(settlements.map { case Settlement(client, assetId, cumulativeSpent) =>
      Json.obj("client" -> client.toString, "assetId" -> assetId.id.toString, "cumulativeSpent" -> cumulativeSpent.value)
    })

  def toJson(tx: SettleTransaction): JsObject = {
    import tx.*
    BaseTxJson.toJson(tx) ++ Json.obj(
      "enclavePublicKey" -> enclavePublicKey.toString,
      "settlements"      -> settlementsJson(settlements),
      "enclaveSignature" -> enclaveSignature.toString
    )
  }
}
