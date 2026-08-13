package tech.hearth.transaction.serialization.impl

import tech.hearth.transaction.UpdateCollateralTransaction
import tech.hearth.utils.byteStrFormat
import play.api.libs.json.{JsObject, Json}

object UpdateCollateralTxSerializer {
  def toJson(tx: UpdateCollateralTransaction): JsObject = {
    import tx.*
    BaseTxJson.toJson(tx) ++ Json.obj(
      "rootCaCrl"             -> rootCaCrl,
      "pckCrl"                -> pckCrl,
      "tcbInfo"               -> tcbInfo,
      "qeIdentity"            -> qeIdentity,
      "tcbSigningIssuerChain" -> tcbSigningIssuerChain,
      "pckCaIssuerChain"      -> pckCaIssuerChain
    )
  }
}
