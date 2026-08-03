package tech.hearth.transaction.serialization.impl

import tech.hearth.protobuf.transaction.PBOrders
import tech.hearth.protobuf.utils.PBUtils
import tech.hearth.transaction.Proofs
import tech.hearth.transaction.assets.exchange.*
import tech.hearth.utils.byteStrFormat
import play.api.libs.json.{JsObject, Json}

object OrderSerializer {
  def toJson(order: Order): JsObject = {
    import order.*
    Json.obj(
      "version"          -> version,
      "id"               -> idStr(),
      "sender"           -> senderPublicKey.toAddress.toBech32,
      "senderPublicKey"  -> senderPublicKey,
      "matcherPublicKey" -> matcherPublicKey,
      "assetPair"        -> assetPair.json,
      "orderType"        -> orderType.toString,
      "amount"           -> amount.value,
      "price"            -> price.value,
      "timestamp"        -> timestamp,
      "expiration"       -> expiration,
      "matcherFee"       -> matcherFee.value,
      "signature"        -> proofs.toSignature.toString,
      "proofs"           -> proofs.proofs.map(_.toString)
    ) ++ (if (version >= Order.V3) Json.obj("matcherFeeAssetId" -> matcherFeeAssetId) else JsObject.empty) ++
      attachment.map(attach => Json.obj("attachment" -> attach)).getOrElse(JsObject.empty)
  }

  def bodyBytes(order: Order): Array[Byte] = {

    val orderWithoutProofs = order.orderAuthentication match {
      case OrderAuthentication.OrderProofs(_, _) => order.withProofs(Proofs.empty)
    }
    PBUtils.encodeDeterministic(PBOrders.protobuf(orderWithoutProofs))
  }
}
