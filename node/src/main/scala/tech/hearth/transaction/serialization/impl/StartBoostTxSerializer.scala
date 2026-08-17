package tech.hearth.transaction.serialization.impl

import tech.hearth.transaction.StartBoostTransaction
import tech.hearth.utils.byteStrFormat
import play.api.libs.json.{JsObject, Json}

object StartBoostTxSerializer {
  def toJson(tx: StartBoostTransaction): JsObject = {
    import tx.*
    BaseTxJson.toJson(tx) ++ Json.obj(
      "validator"             -> validator.toString,
      "tdxQuote"              -> tdxQuote,
      "generationPeriodStart" -> generationPeriodStart
    )
  }
}
