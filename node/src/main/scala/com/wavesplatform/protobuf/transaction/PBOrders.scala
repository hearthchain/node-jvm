package com.wavesplatform.protobuf.transaction

import com.wavesplatform.account.{AddressScheme, PublicKey}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.lang.ValidationError
import com.wavesplatform.protobuf.*
import tech.hearth.protobuf.order.AssetPair
import tech.hearth.protobuf.order.Order.PriceMode.{ASSET_DECIMALS, FIXED_DECIMALS, DEFAULT as DEFAULT_PRICE_MODE}
import tech.hearth.protobuf.order.Order.PriceMode
import com.wavesplatform.transaction as vt
import com.wavesplatform.transaction.TxValidationError.GenericError
import com.wavesplatform.transaction.assets.exchange.OrderPriceMode.{AssetDecimals, FixedDecimals, Default as DefaultPriceMode}
import com.wavesplatform.transaction.assets.exchange.{OrderAuthentication, OrderType}
import com.wavesplatform.transaction.{TxExchangeAmount, TxMatcherFee, TxOrderPrice}

object PBOrders {
  import com.wavesplatform.protobuf.utils.PBImplicitConversions.*

  def vanilla(order: PBOrder): Either[ValidationError, VanillaOrder] =
    for {
      amount     <- TxExchangeAmount(order.amount)(GenericError(TxExchangeAmount.errMsg))
      price      <- TxOrderPrice(order.price)(GenericError(TxOrderPrice.errMsg))
      orderType  <- vanillaOrderType(order.orderSide)
      matcherFee <- TxMatcherFee(order.getMatcherFee.longAmount)(GenericError(TxMatcherFee.errMsg))
    } yield {
      VanillaOrder(
        order.version.toByte,
        // The sender is a plain field now, rather than a oneof of a public key and an ethereum signature
        OrderAuthentication.OrderProofs(PublicKey(order.senderPublicKey.toByteStr), order.proofs.map(_.toByteStr)),
        PublicKey(order.matcherPublicKey.toByteArray),
        vt.assets.exchange
          .AssetPair(PBAmounts.toVanillaAssetId(order.getAssetPair.amountAssetId), PBAmounts.toVanillaAssetId(order.getAssetPair.priceAssetId)),
        orderType,
        amount,
        price,
        order.timestamp,
        order.expiration,
        matcherFee,
        PBAmounts.toVanillaAssetId(order.getMatcherFee.assetId),
        order.priceMode match {
          case DEFAULT_PRICE_MODE        => DefaultPriceMode
          case ASSET_DECIMALS            => AssetDecimals
          case FIXED_DECIMALS            => FixedDecimals
          case PriceMode.Unrecognized(v) => throw new IllegalArgumentException(s"Unknown order price mode: $v")
        },
        Option.unless(order.attachment.isEmpty)(order.attachment.toByteStr)
      )
    }

  def protobuf(order: VanillaOrder): PBOrder = {
    PBOrder(
      chainId = AddressScheme.current.chainId,
      senderPublicKey = order.orderAuthentication match {
        case OrderAuthentication.OrderProofs(key, _) => key.toByteString
      },
      matcherPublicKey = order.matcherPublicKey.toByteString,
      assetPair = Some(AssetPair(PBAmounts.toPBAssetId(order.assetPair.amountAsset), PBAmounts.toPBAssetId(order.assetPair.priceAsset))),
      orderSide = order.orderType match {
        case vt.assets.exchange.OrderType.BUY  => PBOrder.Side.BUY
        case vt.assets.exchange.OrderType.SELL => PBOrder.Side.SELL
      },
      amount = order.amount.value,
      price = order.price.value,
      timestamp = order.timestamp,
      expiration = order.expiration,
      matcherFee = Some((order.matcherFeeAssetId, order.matcherFee.value)),
      version = order.version,
      proofs = order.proofs.map(_.toByteString),
      priceMode = order.priceMode match {
        case DefaultPriceMode => DEFAULT_PRICE_MODE
        case AssetDecimals    => ASSET_DECIMALS
        case FixedDecimals    => FIXED_DECIMALS
      },
      attachment = order.attachment.getOrElse(ByteStr.empty).toByteString
    )
  }

  private def vanillaOrderType(orderSide: tech.hearth.protobuf.order.Order.Side): Either[GenericError, OrderType] =
    orderSide match {
      case PBOrder.Side.BUY             => Right(vt.assets.exchange.OrderType.BUY)
      case PBOrder.Side.SELL            => Right(vt.assets.exchange.OrderType.SELL)
      case PBOrder.Side.Unrecognized(v) => Left(GenericError(s"Unknown order type: $v"))
    }
}
