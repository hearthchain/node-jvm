package com.wavesplatform.state.diffs

import cats.implicits.toFoldableOps
import cats.syntax.either.*
import com.wavesplatform.account.Address
import com.wavesplatform.lang.ValidationError
import com.wavesplatform.state.*
import com.wavesplatform.transaction.Asset.{IssuedAsset, Waves}
import com.wavesplatform.transaction.TxValidationError.{GenericError, OrderValidationError}
import com.wavesplatform.transaction.assets.exchange.*
import com.wavesplatform.transaction.assets.exchange.OrderPriceMode.AssetDecimals
import com.wavesplatform.transaction.Asset

import java.text.{DecimalFormat, DecimalFormatSymbols}
import scala.util.{Right, Try}

object ExchangeTransactionDiff {

  private val formatter = {
    val symbols = DecimalFormatSymbols.getInstance
    symbols.setGroupingSeparator('_')
    new DecimalFormat("###,###.##", symbols)
  }

  def apply(blockchain: Blockchain)(tx: ExchangeTransaction): Either[ValidationError, StateSnapshot] = {
    for {
      _          <- checkOrderPkRecover(tx.order1)
      _          <- checkOrderPkRecover(tx.order2)
      _          <- enoughVolume(tx, blockchain)
      portfolios <- getPortfolios(blockchain, tx)
      orderFills = Map(
        tx.buyOrder.id()  -> VolumeAndFee(tx.amount.value, tx.buyMatcherFee.value),
        tx.sellOrder.id() -> VolumeAndFee(tx.amount.value, tx.sellMatcherFee.value)
      )
      snapshot <- StateSnapshot.build(blockchain, portfolios, orderFills)
    } yield snapshot
  }

  def getPortfolios(blockchain: Blockchain, tx: ExchangeTransaction): Either[ValidationError, Map[Address, Portfolio]] = {
    def isPriceValid(amountDecimals: Int, priceDecimals: Int) = {
      def convertPrice(price: Long, amountDecimals: Int, priceDecimals: Int) =
        Try {
          (BigDecimal(price) / BigDecimal(10).pow(priceDecimals - amountDecimals)).toBigInt.bigInteger.longValueExact()
        }.toEither.leftMap(x => GenericError(x.getMessage))

      def orderPrice(order: Order, amountDecimals: Int, priceDecimals: Int) =
        if (order.version < Order.V4 || order.priceMode == AssetDecimals)
          convertPrice(order.price.value, amountDecimals, priceDecimals)
        else
          Right(order.price.value)

      def formatTxPrice: String = formatter.format(tx.price.value)

      def formatOrderPrice(order: Order, convertedPrice: Long): String = {
        val rawPriceStr =
          if (order.price.value == convertedPrice) ""
          else s" (assetDecimals price = ${formatter.format(order.price.value)})"
        s"${formatter.format(convertedPrice)}$rawPriceStr"
      }

      for {
        buyOrderPrice  <- orderPrice(tx.buyOrder, amountDecimals, priceDecimals)
        sellOrderPrice <- orderPrice(tx.sellOrder, amountDecimals, priceDecimals)
        _ <- Either.cond(
          tx.price.value <= buyOrderPrice,
          (),
          GenericError(s"exchange.price = $formatTxPrice should be <= buyOrder.price = ${formatOrderPrice(tx.buyOrder, buyOrderPrice)}")
        )
        _ <- Either.cond(
          tx.price.value >= sellOrderPrice,
          (),
          GenericError(s"exchange.price = $formatTxPrice should be >= sellOrder.price = ${formatOrderPrice(tx.sellOrder, sellOrderPrice)}")
        )
      } yield ()
    }

    val assetIds =
      List(
        tx.buyOrder.assetPair.amountAsset,
        tx.buyOrder.assetPair.priceAsset,
        tx.sellOrder.assetPair.amountAsset,
        tx.sellOrder.assetPair.priceAsset
      ).collect { case asset: IssuedAsset =>
        asset
      }.distinct
    val assets = assetIds.map(id => id -> blockchain.assetDescription(id)).toMap

    val matcher: Address = tx.sender.toAddress
    val buyer: Address   = tx.buyOrder.sender.toAddress
    val seller: Address  = tx.sellOrder.sender.toAddress

    def getAssetDiff(asset: Asset, buyAssetChange: Long, sellAssetChange: Long): Either[String, Map[Address, Portfolio]] = {
      Portfolio.combine(
        Map(buyer  -> Portfolio.build(asset, buyAssetChange)),
        Map(seller -> Portfolio.build(asset, sellAssetChange))
      )
    }

    lazy val matcherPortfolioE =
      Seq(
        getOrderFeePortfolio(tx.buyOrder, tx.buyMatcherFee.value),
        getOrderFeePortfolio(tx.sellOrder, tx.sellMatcherFee.value),
        Portfolio.waves(-tx.fee.value)
      ).foldM(Portfolio())(_.combine(_))

    lazy val feeDiffE =
      matcherPortfolioE.flatMap(matcherPortfolio =>
        Seq(
          Map[Address, Portfolio](matcher -> matcherPortfolio),
          Map[Address, Portfolio](buyer   -> getOrderFeePortfolio(tx.buyOrder, -tx.buyMatcherFee.value)),
          Map[Address, Portfolio](seller  -> getOrderFeePortfolio(tx.sellOrder, -tx.sellMatcherFee.value))
        ).foldM(Map.empty[Address, Portfolio])(Portfolio.combine)
      )

    for {
      // No sign check on the matcher fees: TxMatcherFee refines them to (0; Order.MaxAmount), so a negative one cannot
      // reach here - ExchangeTransaction.create rejects it with TxMatcherFee.errMsg.
      _ <- Either.cond(assets.values.forall(_.isDefined), (), GenericError("Assets should be issued before they can be traded"))
      amountDecimals = tx.buyOrder.assetPair.amountAsset.fold(8)(ia => assets(ia).fold(8)(_.decimals))
      priceDecimals  = tx.buyOrder.assetPair.priceAsset.fold(8)(ia => assets(ia).fold(8)(_.decimals))
      _                     <- isPriceValid(amountDecimals, priceDecimals)
      buyPriceAssetChange   <- getSpendAmount(tx.buyOrder, amountDecimals, priceDecimals, tx.amount.value, tx.price.value).map(-_)
      buyAmountAssetChange  <- getReceiveAmount(tx.buyOrder, amountDecimals, priceDecimals, tx.amount.value, tx.price.value)
      sellPriceAssetChange  <- getReceiveAmount(tx.sellOrder, amountDecimals, priceDecimals, tx.amount.value, tx.price.value)
      sellAmountAssetChange <- getSpendAmount(tx.sellOrder, amountDecimals, priceDecimals, tx.amount.value, tx.price.value).map(-_)
      priceDiff             <- getAssetDiff(tx.buyOrder.assetPair.priceAsset, buyPriceAssetChange, sellPriceAssetChange).leftMap(GenericError(_))
      amountDiff            <- getAssetDiff(tx.buyOrder.assetPair.amountAsset, buyAmountAssetChange, sellAmountAssetChange).leftMap(GenericError(_))
      feeDiff               <- feeDiffE.leftMap(GenericError(_))
      totalDiff             <- Portfolio.combine(feeDiff, priceDiff).flatMap(Portfolio.combine(_, amountDiff)).leftMap(GenericError(_))
    } yield totalDiff
  }

  private def enoughVolume(exTrans: ExchangeTransaction, blockchain: Blockchain): Either[ValidationError, Unit] = {

    val filledBuy  = blockchain.filledVolumeAndFee(exTrans.buyOrder.id())
    val filledSell = blockchain.filledVolumeAndFee(exTrans.sellOrder.id())

    val buyTotal  = filledBuy.volume + exTrans.amount.value
    val sellTotal = filledSell.volume + exTrans.amount.value

    lazy val buyAmountValid  = exTrans.buyOrder.amount.value >= buyTotal
    lazy val sellAmountValid = exTrans.sellOrder.amount.value >= sellTotal

    def isFeeValid(feeTotal: Long, amountTotal: Long, maxfee: Long, maxAmount: Long, order: Order): Boolean = {
      feeTotal <= (order match {
        case o: Order if o.version >= Order.V3 => BigInt(maxfee)
        case _                                 => BigInt(maxfee) * BigInt(amountTotal) / BigInt(maxAmount)
      })
    }

    lazy val buyFeeValid =
      isFeeValid(
        feeTotal = filledBuy.fee + exTrans.buyMatcherFee.value,
        amountTotal = buyTotal,
        maxfee = exTrans.buyOrder.matcherFee.value,
        maxAmount = exTrans.buyOrder.amount.value,
        order = exTrans.buyOrder
      )

    lazy val sellFeeValid =
      isFeeValid(
        feeTotal = filledSell.fee + exTrans.sellMatcherFee.value,
        amountTotal = sellTotal,
        maxfee = exTrans.sellOrder.matcherFee.value,
        maxAmount = exTrans.sellOrder.amount.value,
        order = exTrans.sellOrder
      )

    if (!buyAmountValid) Left(OrderValidationError(exTrans.buyOrder, s"Too much buy. Already filled volume for the order: ${filledBuy.volume}"))
    else if (!sellAmountValid)
      Left(OrderValidationError(exTrans.sellOrder, s"Too much sell. Already filled volume for the order: ${filledSell.volume}"))
    else if (!buyFeeValid) Left(OrderValidationError(exTrans.buyOrder, s"Insufficient buy fee"))
    else if (!sellFeeValid) Left(OrderValidationError(exTrans.sellOrder, s"Insufficient sell fee"))
    else Right(())
  }

  private[diffs] def getSpendAmount(
      order: Order,
      amountDecimals: Int,
      priceDecimals: Int,
      matchAmount: Long,
      matchPrice: Long
  ): Either[ValidationError, Long] =
    Try {
      if (order.orderType == OrderType.SELL) matchAmount
      else {
        val spend = (BigDecimal(matchAmount) * matchPrice * BigDecimal(10).pow(priceDecimals - amountDecimals - 8)).toBigInt
        if (order.getSpendAssetId == Waves && !(spend + order.matcherFee.value).isValidLong) {
          throw new ArithmeticException("BigInteger out of long range")
        } else spend.bigInteger.longValueExact()
      }
    }.toEither.left.map(x => GenericError(x.getMessage))

  private[diffs] def getReceiveAmount(
      order: Order,
      amountDecimals: Int,
      priceDecimals: Int,
      matchAmount: Long,
      matchPrice: Long
  ): Either[ValidationError, Long] =
    Try {
      if (order.orderType == OrderType.BUY) matchAmount
      else {
        (BigDecimal(matchAmount) * matchPrice * BigDecimal(10).pow(priceDecimals - amountDecimals - 8)).toBigInt.bigInteger.longValueExact()
      }
    }.toEither.left.map(x => GenericError(x.getMessage))

  /** Calculates fee portfolio from the order (taking into account that in OrderV3 fee can be paid in asset != Waves)
    */
  private[diffs] def getOrderFeePortfolio(order: Order, fee: Long): Portfolio =
    Portfolio.build(order.matcherFeeAssetId, fee)

  private def checkOrderPkRecover(order: Order): Either[GenericError, Unit] =
    order.orderAuthentication match {
      case _ => Right(())
    }
}
