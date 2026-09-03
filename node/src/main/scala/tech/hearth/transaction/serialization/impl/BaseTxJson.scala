package tech.hearth.transaction.serialization.impl

import tech.hearth.transaction.{ProvenTransaction, Transaction}
import play.api.libs.json.{JsArray, JsObject, JsString, Json}

object BaseTxJson {
  def toJson(tx: Transaction): JsObject = {
    Json.obj(
      "type"       -> tx.tpe.id,
      "id"         -> tx.id().toString,
      "fee"        -> tx.assetFee._2,
      "feeAssetId" -> tx.assetFee._1.maybeBase16Repr,
      "timestamp"  -> tx.timestamp,
      "networkId"  -> tx.networkId.value
    ) ++ (tx match {
      case p: ProvenTransaction =>
        Json.obj(
          "sender"          -> p.sender.toAddress.toString,
          "senderPublicKey" -> p.sender,
          "proofs"          -> JsArray(p.proofs.proofs.map(p => JsString(p.toString)))
        )
      case _ => JsObject.empty
    })
  }
}
