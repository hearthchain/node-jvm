package tech.hearth.transaction.assets.exchange

import tech.hearth.account.{Address, PrivateKey, PublicKey}
import tech.hearth.common.state.ByteStr
import tech.hearth.crypto
import tech.hearth.transaction.*
import tech.hearth.transaction.Asset.Hearth
import tech.hearth.transaction.TxValidationError.GenericError
import tech.hearth.transaction.assets.exchange.Validation.booleanOperators
import tech.hearth.transaction.serialization.impl.OrderSerializer
import monix.eval.Coeval
import play.api.libs.json.{Format, JsObject}

sealed trait OrderAuthentication
object OrderAuthentication {
  final case class OrderProofs(key: PublicKey, proofs: Proofs) extends OrderAuthentication

  def apply(pk: PublicKey): OrderProofs = OrderProofs(pk, Proofs.empty)
}

/** Order to matcher service for asset exchange
  */
case class Order(
    version: Order.Version,
    orderAuthentication: OrderAuthentication,
    matcherPublicKey: PublicKey,
    assetPair: AssetPair,
    orderType: OrderType,
    amount: TxExchangeAmount,
    price: TxOrderPrice,
    timestamp: TxTimestamp,
    expiration: TxTimestamp,
    matcherFee: TxMatcherFee,
    matcherFeeAssetId: Asset = Hearth,
    priceMode: OrderPriceMode = OrderPriceMode.Default,
    attachment: Option[ByteStr] = None
) extends Proven {
  import Order.*

  lazy val senderPublicKey: PublicKey = orderAuthentication match {
    case OrderAuthentication.OrderProofs(publicKey, _) => publicKey
  }

  val proofs: Proofs = orderAuthentication match {
    case OrderAuthentication.OrderProofs(_, proofs) => proofs
  }

  lazy val sender: PublicKey = senderPublicKey
  def senderAddress: Address = sender.toAddress

  def withProofs(proofs: Proofs): Order = {
    copy(orderAuthentication = OrderAuthentication.OrderProofs(senderPublicKey, proofs))
  }

  def isValid(atTime: Long): Validation = {
    assetPair.isValid &&
    (timestamp > 0) :| "timestamp should be > 0" &&
    (expiration - atTime <= MaxLiveTime) :| "expiration should be earlier than 30 days" &&
    (expiration >= atTime) :| "expiration should be > currentTime" &&
    (matcherFeeAssetId == Hearth || version >= Order.V3) :| "matcherFeeAssetId should be hearth" &&
    (version > 0 && version < 5) :| "invalid version" &&
    (version >= Order.V4 || priceMode == OrderPriceMode.Default) :| s"price mode should be default for V$version" &&
    (orderAuthentication match {
      case OrderAuthentication.OrderProofs(_, proofs) =>
        Proofs.validate(proofs).fold(e => Validation.failure(e.toString), _ => Validation.success)
    }) &&
    (attachment.isEmpty || version >= Order.V4) :| "non-empty attachment field is allowed only for version >= V4" &&
    attachment.forall(_.size <= MaxAttachmentSize) :| s"attachment size should be <= $MaxAttachmentSize bytes" &&
    attachment.forall(!_.isEmpty) :| "attachment size should be > 0"
  }

  val bodyBytes: Coeval[Array[Byte]] = Coeval.evalOnce(OrderSerializer.bodyBytes(this))
  val id: Coeval[ByteStr]            = Coeval.evalOnce(ByteStr(crypto.fastHash(bodyBytes())))
  val idStr: Coeval[String]          = Coeval.evalOnce(id().toString)

  def getReceiveAssetId: Asset = orderType match {
    case OrderType.BUY  => assetPair.amountAsset
    case OrderType.SELL => assetPair.priceAsset
  }

  def getSpendAssetId: Asset = orderType match {
    case OrderType.BUY  => assetPair.priceAsset
    case OrderType.SELL => assetPair.amountAsset
  }

  val json: Coeval[JsObject] = Coeval.evalOnce(OrderSerializer.toJson(this))

  override protected def verifyFirstProof(): Either[GenericError, Unit] =
    super.verifyFirstProof()

  override def toString: String = {
    val matcherFeeAssetIdStr = if (version == 3) s" matcherFeeAssetId=${matcherFeeAssetId.fold("Hearth")(_.toString)}," else ""
    s"OrderV$version(id=${idStr()}, sender=$senderPublicKey, matcher=$matcherPublicKey, pair=$assetPair, type=$orderType, amount=$amount, " +
      s"price=$price, priceMode=$priceMode, ts=$timestamp, exp=$expiration, fee=$matcherFee,$matcherFeeAssetIdStr, proofs=$proofs)"
  }
}

object Order {
  type Id      = ByteStr
  type Version = Byte

  implicit lazy val jsonFormat: Format[Order] = tech.hearth.transaction.assets.exchange.OrderJson.orderFormat

  val MaxLiveTime: Long   = 30L * 24L * 60L * 60L * 1000L
  final val PriceConstant = 100000000L
  final val MaxAmount     = 100 * PriceConstant * PriceConstant
  val MaxAttachmentSize   = 1024

  val V1: Version = 1.toByte
  val V2: Version = 2.toByte
  val V3: Version = 3.toByte
  val V4: Version = 4.toByte

  implicit def sign(order: Order, privateKey: PrivateKey): Order =
    order.withProofs(Proofs(crypto.sign(privateKey, order.bodyBytes())))
}
