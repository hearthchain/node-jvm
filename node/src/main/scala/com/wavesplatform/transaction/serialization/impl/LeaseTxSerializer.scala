package com.wavesplatform.transaction.serialization.impl

import com.wavesplatform.transaction.lease.LeaseTransaction
import play.api.libs.json.{JsObject, Json}

object LeaseTxSerializer {
  def toJson(tx: LeaseTransaction): JsObject = {
    import tx.*
    BaseTxJson.toJson(tx) ++ Json.obj(
      "amount"    -> amount.value,
      "recipient" -> recipient.toBech32,
      "version"   -> version.value
    )
  }
}
