package com.wavesplatform.transaction.serialization.impl

import com.wavesplatform.transaction.assets.exchange.ExchangeTransaction
import play.api.libs.json.{JsObject, Json}

object ExchangeTxSerializer {
  def toJson(tx: ExchangeTransaction): JsObject = {
    import tx.*
    BaseTxJson.toJson(tx) ++ Json.obj(
      "order1"         -> order1.json(),
      "order2"         -> order2.json(),
      "amount"         -> amount.value,
      "price"          -> price.value,
      "buyMatcherFee"  -> buyMatcherFee.value,
      "sellMatcherFee" -> sellMatcherFee.value
    )
  }

  def bodyBytes(tx: ExchangeTransaction): Array[Byte] = {

    PBTransactionSerializer.bodyBytes(tx)
  }

  def toBytes(tx: ExchangeTransaction): Array[Byte] =
    PBTransactionSerializer.bytes(tx)
}
