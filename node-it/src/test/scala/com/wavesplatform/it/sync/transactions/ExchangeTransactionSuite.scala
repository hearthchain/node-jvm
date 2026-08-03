package com.wavesplatform.it.sync.transactions

import com.typesafe.config.Config
import com.wavesplatform.api.http.ApiError.{CustomValidationError, StateCheckFailed}
import com.wavesplatform.it.NodeConfigs.GenesisAssets
import com.wavesplatform.it.api.SyncHttpApi.*
import com.wavesplatform.it.sync.*
import com.wavesplatform.it.transactions.BaseTransactionSuite
import com.wavesplatform.it.{NTPTime, NodeConfigs}
import com.wavesplatform.test.*
import com.wavesplatform.transaction.Asset.Waves
import com.wavesplatform.transaction.assets.exchange.*
import com.wavesplatform.transaction.{TxExchangeAmount, TxExchangePrice, TxHelpers}
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
    // 32 zero bytes, base58-encoded (32 '1' characters): a syntactically valid asset id that was never
    // declared in genesis, so it is genuinely "not issued" without tripping the byte-length validation gate.
    val neverIssuedAssetId = "1" * 32

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

      val pair = AssetPair.createAssetPair("WAVES", neverIssuedAssetId).get
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
          version = 2,
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
          version = 2,
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
          matcherFee,
          version = 2
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
        (1: Byte, 3: Byte, Waves, GenesisAssets.TestAsset),
        (1: Byte, 3: Byte, Waves, Waves),
        (2: Byte, 3: Byte, Waves, GenesisAssets.TestAsset),
        (3: Byte, 1: Byte, GenesisAssets.TestAsset, Waves),
        (2: Byte, 3: Byte, Waves, Waves),
        (3: Byte, 2: Byte, GenesisAssets.TestAsset, Waves)
      )
    ) {

      val matcher                  = thirdKeyPair
      val ts                       = ntpTime.correctedTime()
      val expirationTimestamp      = ts + Order.MaxLiveTime / 2
      var assetBalanceBefore: Long = 0L

      if (matcherFeeOrder1 == Waves && matcherFeeOrder2 != Waves) {
        assetBalanceBefore = sender.assetBalance(secondKeyPair.toAddress.toString, assetId.toString).balance
        sender.transfer(buyer, seller.toAddress.toString, 100000, minFee, Some(assetId.toString), waitForTx = true)
      }

      val buyPrice   = 500000
      val sellPrice  = 500000
      val buyAmount  = 40000000
      val sellAmount = 40000000
      val buy = TxHelpers.order(
        OrderType.BUY,
        Waves,
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
        Waves,
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

      if (matcherFeeOrder1 == Waves && matcherFeeOrder2 != Waves) {
        sender.assetBalance(secondAddress, assetId.toString).balance shouldBe assetBalanceBefore
      }
    }
  }

  test("exchange tx with orders v4 can use price that is impossible for orders v3/v2/v1") {
    sender.transfer(sender.keyPair, firstAddress, 1000.waves, waitForTx = true)

    val seller        = acc1
    val buyer         = acc0
    val sellerKeyPair = secondKeyPair
    val buyerKeyPair  = firstKeyPair

    val nftAsset = GenesisAssets.TestNftAsset.id.toString

    val matcher             = thirdKeyPair
    val ts                  = ntpTime.correctedTime()
    val expirationTimestamp = ts + Order.MaxLiveTime / 2
    val amount              = 1
    val nftWavesPrice       = 1000 * math.pow(10, 8).toLong

    val sellNftForWaves = TxHelpers.order(
      OrderType.SELL,
      GenesisAssets.TestNftAsset,
      Waves,
      sender = seller,
      matcher = matcher,
      amount = amount,
      price = nftWavesPrice,
      fee = matcherFee,
      timestamp = ts,
      expiration = expirationTimestamp,
      version = 4.toByte
    )
    val buyNftForWaves = TxHelpers.order(
      OrderType.BUY,
      GenesisAssets.TestNftAsset,
      Waves,
      sender = buyer,
      matcher = matcher,
      amount = amount,
      price = nftWavesPrice,
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
        order1 = buyNftForWaves,
        order2 = sellNftForWaves,
        amount = amount,
        price = nftWavesPrice,
        buyMatcherFee = (BigInt(matcherFee) * amount / sellNftForWaves.amount.value).toLong,
        sellMatcherFee = (BigInt(matcherFee) * amount / sellNftForWaves.amount.value).toLong,
        fee = matcherFee,
        timestamp = ntpTime.correctedTime()
      )

    sender.signedBroadcast(tx.json(), waitForTx = true)
    nodes.waitForHeightAriseAndTxPresent(tx.id().toString)

    sender.nftList(buyerAddress, 1).head.assetId shouldBe nftAsset
    sender.balanceDetails(sellerAddress).regular shouldBe sellerBalance + nftWavesPrice - matcherFee
    sender.balanceDetails(buyerAddress).regular shouldBe buyerBalance - nftWavesPrice - matcherFee
  }

  import NodeConfigs.*
  override protected def nodeConfigs: Seq[Config] =
    Seq(BiggestMiner.quorum(0), NotMiner)
}
