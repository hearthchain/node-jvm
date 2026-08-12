package tech.hearth.transaction.serialization.impl

import tech.hearth.transaction.BindApiKeyTransaction
import tech.hearth.utils.byteStrFormat
import play.api.libs.json.{JsObject, Json}

object BindApiKeyTxSerializer {
  def toJson(tx: BindApiKeyTransaction): JsObject = {
    import tx.*
    BaseTxJson.toJson(tx) ++ Json.obj(
      "enclavePublicKey" -> enclavePublicKey,
      "encryptedApiKey"  -> encryptedApiKey
    )
  }
}
