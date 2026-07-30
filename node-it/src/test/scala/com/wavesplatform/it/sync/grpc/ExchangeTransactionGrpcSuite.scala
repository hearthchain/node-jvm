package com.wavesplatform.it.sync.grpc

import com.wavesplatform.it.NTPTime
import com.wavesplatform.it.NodeConfigs.GenesisAssets
import com.wavesplatform.it.api.SyncGrpcApi.*
import com.wavesplatform.it.sync.{matcherFee, minFee}
import com.wavesplatform.test.*
import com.wavesplatform.transaction.Asset.Waves
import com.wavesplatform.transaction.TxHelpers
import com.wavesplatform.transaction.assets.exchange.{Order, OrderType}
import com.wavesplatform.utils.*
import io.grpc.Status.Code

import scala.collection.immutable

class ExchangeTransactionGrpcSuite extends GrpcBaseTransactionSuite with NTPTime {

  val transactionV1versions: (Byte, Byte, Byte) = (1: Byte, 1: Byte, 1: Byte)
  val transactionV2versions: immutable.Seq[(Byte, Byte, Byte)] = for {
    o1ver <- 1 to 3
    o2ver <- 1 to 3
    txVer <- 2 to 3
  } yield (o1ver.toByte, o2ver.toByte, txVer.toByte)

  val (buyer, buyerAddress)     = (firstAcc, firstAddress)
  val (seller, sellerAddress)   = (secondAcc, secondAddress)
  val (matcher, matcherAddress) = (thirdAcc, thirdAddress)

  val versions: immutable.Seq[(Byte, Byte, Byte)] = transactionV1versions +: transactionV2versions

  private val exchAssetId = GenesisAssets.TestAsset.id.toString

  test("exchange tx with orders v1,v2") {
    val price              = 500000L
    val amount             = 40000000L
    val priceAssetSpending = amount * price / 100000000L
    for ((o1ver, o2ver, tver) <- versions) {
      val ts                  = ntpTime.correctedTime()
      val expirationTimestamp = ts + Order.MaxLiveTime / 2
      val buy = TxHelpers.order(
        OrderType.BUY,
        Waves,
        GenesisAssets.TestAsset,
        sender = buyer,
        matcher = matcher,
        amount = amount,
        price = price,
        fee = matcherFee,
        timestamp = ts,
        expiration = expirationTimestamp,
        version = o1ver
      )
      val sell = TxHelpers.order(
        OrderType.SELL,
        Waves,
        GenesisAssets.TestAsset,
        sender = seller,
        matcher = matcher,
        amount = amount,
        price = price,
        fee = matcherFee,
        timestamp = ts,
        expiration = expirationTimestamp,
        version = o2ver
      )
      val buyerWavesBalanceBefore  = sender.wavesBalance(buyerAddress).available
      val sellerWavesBalanceBefore = sender.wavesBalance(sellerAddress).available
      val buyerAssetBalanceBefore  = sender.assetsBalance(buyerAddress, Seq(exchAssetId)).getOrElse(exchAssetId, 0L)
      val sellerAssetBalanceBefore = sender.assetsBalance(sellerAddress, Seq(exchAssetId)).getOrElse(exchAssetId, 0L)

      sender.exchange(matcher, buy, sell, amount, price, matcherFee, matcherFee, matcherFee, ts, tver, waitForTx = true)

      sender.wavesBalance(buyerAddress).available shouldBe buyerWavesBalanceBefore + amount - matcherFee
      sender.wavesBalance(sellerAddress).available shouldBe sellerWavesBalanceBefore - amount - matcherFee
      sender.assetsBalance(buyerAddress, Seq(exchAssetId))(exchAssetId) shouldBe buyerAssetBalanceBefore - priceAssetSpending
      sender.assetsBalance(sellerAddress, Seq(exchAssetId))(exchAssetId) shouldBe sellerAssetBalanceBefore + priceAssetSpending
    }
  }

  test("cannot exchange non-issued assets") {
    val neverIssuedAssetId = "11111111111111111111111111111111111111111111111111"

    for ((o1ver, o2ver, tver) <- versions) {
      val ts                  = ntpTime.correctedTime()
      val expirationTimestamp = ts + Order.MaxLiveTime / 2
      val price               = 2 * Order.PriceConstant
      val amount              = 1
      val pair                = com.wavesplatform.transaction.assets.exchange.AssetPair.createAssetPair("WAVES", neverIssuedAssetId).get
      val buy = TxHelpers.order(
        OrderType.BUY,
        pair.amountAsset,
        pair.priceAsset,
        sender = buyer,
        matcher = matcher,
        amount = amount,
        price = price,
        fee = matcherFee,
        timestamp = ts,
        expiration = expirationTimestamp,
        version = o1ver
      )
      val sell = TxHelpers.order(
        OrderType.SELL,
        pair.amountAsset,
        pair.priceAsset,
        sender = seller,
        matcher = matcher,
        amount = amount,
        price = price,
        fee = matcherFee,
        timestamp = ts,
        expiration = expirationTimestamp,
        version = o2ver
      )

      assertGrpcError(
        sender.exchange(matcher, buy, sell, amount, price, matcherFee, matcherFee, matcherFee, ts, tver),
        "Assets should be issued before they can be traded",
        Code.INVALID_ARGUMENT
      )
    }
  }
}
