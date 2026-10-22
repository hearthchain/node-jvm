package tech.hearth.transaction.serialization.impl

import tech.hearth.transaction.WithdrawTransaction
import play.api.libs.json.{JsObject, Json}

object WithdrawTxSerializer {
  def toJson(tx: WithdrawTransaction): JsObject = {
    import tx.*
    BaseTxJson.toJson(tx) ++ Json.obj(
      "fromMiner" -> fromMiner.toString,
      "assetId"   -> assetId.id.toString,
      "amount"    -> amount.value
    )
  }
}
