package com.wavesplatform.transaction.serialization.impl

import com.wavesplatform.transaction.{ProvenTransaction, Transaction}
import play.api.libs.json.{JsArray, JsObject, JsString, Json}

object BaseTxJson {
  def toJson(tx: Transaction): JsObject = {
    Json.obj(
      "type"       -> tx.tpe.id,
      "id"         -> tx.id().toString,
      "fee"        -> tx.assetFee._2,
      "feeAssetId" -> tx.assetFee._1.maybeBase58Repr,
      "timestamp"  -> tx.timestamp,
      "chainId"    -> tx.chainId
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
