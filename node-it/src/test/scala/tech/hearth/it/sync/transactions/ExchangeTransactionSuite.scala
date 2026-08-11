package tech.hearth.it.sync.transactions

import com.typesafe.config.Config
import tech.hearth.api.http.ApiError.{CustomValidationError, StateCheckFailed}
import tech.hearth.it.NodeConfigs.GenesisAssets
import tech.hearth.it.api.SyncHttpApi.*
import tech.hearth.it.sync.*
import tech.hearth.it.transactions.BaseTransactionSuite
import tech.hearth.it.{NTPTime, NodeConfigs}
import tech.hearth.test.*
import tech.hearth.transaction.Asset.Hearth
import tech.hearth.transaction.assets.exchange.*
import tech.hearth.transaction.{TxExchangeAmount, TxExchangePrice, TxHelpers}
import play.api.libs.json.JsObject

class ExchangeTransactionSuite extends BaseTransactionSuite with NTPTime {

  private def acc0 = firstKeyPair
  private def acc1 = secondKeyPair
  private def acc2 = thirdKeyPair

  // ExchangeTransaction no longer carries its own version (see CLAUDE.md "Transaction JSON"), so this collapses
  // to version-per-order only.
  private val versions = for {
    o1ver <- 1 to 3
    o2ver <- 1 to 3
  } yield (o1ver.toByte, o2ver.toByte)

  test("cannot exchange non-issued assets") {
    // 32 zero bytes, hex-encoded (64 '0' characters): a syntactically valid asset id that was never
    // declared in genesis, so it is genuinely "not issued" without tripping the byte-length validation gate.
    val neverIssuedAssetId = "0" * 64

    for ((buyVersion, sellVersion) <- versions) {
      val buyer   = acc0
      val seller  = acc1
      val matcher = acc2

      val ts                  = ntpTime.correctedTime()
      val expirationTimestamp = ts + Order.MaxLiveTime / 2

      val buyPrice   = 2 * Order.PriceConstant
      val sellPrice  = 2 * Order.PriceConstant
      val buyAmount  = 1
      val sellAmount = 1
      val amount     = 1

      val pair = AssetPair.createAssetPair("HRTH", neverIssuedAssetId).get
      val buy = TxHelpers.order(
        OrderType.BUY,
        pair.amountAsset,
        pair.priceAsset,
        sender = buyer,
        matcher = matcher,
        amount = buyAmount,
        price = buyPrice,
        fee = matcherFee,
        timestamp = ts,
        expiration = expirationTimestamp,
        version = buyVersion
      )
      val sell = TxHelpers.order(
        OrderType.SELL,
        pair.amountAsset,
        pair.priceAsset,
        sender = seller,
        matcher = matcher,
        amount = sellAmount,
        price = sellPrice,
        fee = matcherFee,
        timestamp = ts,
        expiration = expirationTimestamp,
        version = sellVersion
      )

      val buyFee  = (BigInt(matcherFee) * amount / buy.amount.value).toLong
      val sellFee = (BigInt(matcherFee) * amount / sell.amount.value).toLong

      // ExchangeTransaction has no version of its own any more, so there is exactly one validator
      // (ExchangeTxValidator) and exactly one message for each of these, regardless of order version.
      assertApiError(
        sender.broadcastExchange(
          matcher,
          sell,
          sell,
          TxExchangeAmount.unsafeFrom(amount),
          TxExchangePrice.unsafeFrom(sellPrice),
          buyFee,
          sellFee,
          matcherFee,
          validate = false
        ),
        CustomValidationError("buyOrder should has OrderType.BUY")
      )

      assertApiError(
        sender.broadcastExchange(
          matcher,
          buy,
          buy,
          TxExchangeAmount.unsafeFrom(amount),
          TxExchangePrice.unsafeFrom(buyPrice),
          buyFee,
          sellFee,
          matcherFee,
          validate = false
        ),
        CustomValidationError("sellOrder should has OrderType.SELL")
      )

      assertApiError {
        sender.broadcastExchange(
          matcher,
          buy,
          sell,
          TxExchangeAmount.unsafeFrom(amount),
          TxExchangePrice.unsafeFrom(sellPrice),
          buyFee,
          sellFee,
          matcherFee
        )
      } { error =>
        error.id shouldBe StateCheckFailed.Id
        error.statusCode shouldBe StateCheckFailed.Code.intValue
        error.message should include("Assets should be issued before they can be traded")
        (error.json \ "transaction").asOpt[JsObject] shouldBe defined
      }
    }
  }

  test("exchange tx with orders v3") {
    val buyer   = acc0
    val seller  = acc1
    val assetId = GenesisAssets.TestAsset.id

    sender.transfer(firstKeyPair, secondKeyPair.toAddress.toString, someAssetAmount / 2, assetId = Some(assetId.toString), waitForTx = true)

    for (
      (o1ver, o2ver, matcherFeeOrder1, matcherFeeOrder2) <- Seq(
        (1: Byte, 3: Byte, Hearth, GenesisAssets.TestAsset),
        (1: Byte, 3: Byte, Hearth, Hearth),
        (2: Byte, 3: Byte, Hearth, GenesisAssets.TestAsset),
        (3: Byte, 1: Byte, GenesisAssets.TestAsset, Hearth),
        (2: Byte, 3: Byte, Hearth, Hearth),
        (3: Byte, 2: Byte, GenesisAssets.TestAsset, Hearth)
      )
    ) {

      val matcher                  = thirdKeyPair
      val ts                       = ntpTime.correctedTime()
      val expirationTimestamp      = ts + Order.MaxLiveTime / 2
      var assetBalanceBefore: Long = 0L

      if (matcherFeeOrder1 == Hearth && matcherFeeOrder2 != Hearth) {
        assetBalanceBefore = sender.assetBalance(secondKeyPair.toAddress.toString, assetId.toString).balance
        sender.transfer(buyer, seller.toAddress.toString, 100000, minFee, Some(assetId.toString), waitForTx = true)
      }

      val buyPrice   = 500000
      val sellPrice  = 500000
      val buyAmount  = 40000000
      val sellAmount = 40000000
      val buy = TxHelpers.order(
        OrderType.BUY,
        Hearth,
        GenesisAssets.TestAsset,
        matcherFeeOrder1,
        buyAmount,
        buyPrice,
        sender = buyer,
        matcher = matcher,
        fee = matcherFee,
        timestamp = ts,
        expiration = expirationTimestamp,
        version = o1ver
      )
      val sell = TxHelpers.order(
        OrderType.SELL,
        Hearth,
        GenesisAssets.TestAsset,
        matcherFeeOrder2,
        sellAmount,
        sellPrice,
        sender = seller,
        matcher = matcher,
        fee = matcherFee,
        timestamp = ts,
        expiration = expirationTimestamp,
        version = o2ver
      )
      val amount = 40000000

      val tx =
        TxHelpers.exchange(
          matcher = matcher,
          order1 = buy,
          order2 = sell,
          amount = amount,
          price = sellPrice,
          buyMatcherFee = (BigInt(matcherFee) * amount / buy.amount.value).toLong,
          sellMatcherFee = (BigInt(matcherFee) * amount / sell.amount.value).toLong,
          fee = matcherFee,
          timestamp = ntpTime.correctedTime()
        )

      sender.postJson("/transactions/broadcast", tx.json())

      nodes.waitForHeightAriseAndTxPresent(tx.id().toString)

      if (matcherFeeOrder1 == Hearth && matcherFeeOrder2 != Hearth) {
        sender.assetBalance(secondAddress, assetId.toString).balance shouldBe assetBalanceBefore
      }
    }
  }

  test("exchange tx with orders v4 can use price that is impossible for orders v3/v2/v1") {
    // The genesis NFT (quantity 1) is held entirely by firstKeyPair (see template.conf), so it must be the seller.
    sender.transfer(sender.keyPair, secondAddress, 1000.hearth, waitForTx = true)

    val seller        = acc0
    val buyer         = acc1
    val sellerKeyPair = firstKeyPair
    val buyerKeyPair  = secondKeyPair

    val nftAsset = GenesisAssets.TestNftAsset.id.toString

    val matcher             = thirdKeyPair
    val ts                  = ntpTime.correctedTime()
    val expirationTimestamp = ts + Order.MaxLiveTime / 2
    val amount              = 1
    val nftHearthPrice      = 1000 * math.pow(10, 8).toLong

    val sellNftForHearth = TxHelpers.order(
      OrderType.SELL,
      GenesisAssets.TestNftAsset,
      Hearth,
      sender = seller,
      matcher = matcher,
      amount = amount,
      price = nftHearthPrice,
      fee = matcherFee,
      timestamp = ts,
      expiration = expirationTimestamp,
      version = 4.toByte
    )
    val buyNftForHearth = TxHelpers.order(
      OrderType.BUY,
      GenesisAssets.TestNftAsset,
      Hearth,
      sender = buyer,
      matcher = matcher,
      amount = amount,
      price = nftHearthPrice,
      fee = matcherFee,
      timestamp = ts,
      expiration = expirationTimestamp,
      version = 4.toByte
    )

    val sellerAddress = sellerKeyPair.toAddress.toString
    val sellerBalance = sender.balanceDetails(sellerAddress).regular
    val buyerAddress  = buyerKeyPair.toAddress.toString
    val buyerBalance  = sender.balanceDetails(buyerAddress).regular

    val tx =
      TxHelpers.exchange(
        matcher = matcher,
        order1 = buyNftForHearth,
        order2 = sellNftForHearth,
        amount = amount,
        price = nftHearthPrice,
        buyMatcherFee = (BigInt(matcherFee) * amount / sellNftForHearth.amount.value).toLong,
        sellMatcherFee = (BigInt(matcherFee) * amount / sellNftForHearth.amount.value).toLong,
        fee = matcherFee,
        timestamp = ntpTime.correctedTime()
      )

    sender.signedBroadcast(tx.json(), waitForTx = true)
    nodes.waitForHeightAriseAndTxPresent(tx.id().toString)

    sender.assetBalance(buyerAddress, nftAsset).balance shouldBe 1L
    sender.balanceDetails(sellerAddress).regular shouldBe sellerBalance + nftHearthPrice - matcherFee
    sender.balanceDetails(buyerAddress).regular shouldBe buyerBalance - nftHearthPrice - matcherFee
  }

  import NodeConfigs.*
  override protected def nodeConfigs: Seq[Config] =
    Seq(BiggestMiner.quorum(0), NotMiner)
}
