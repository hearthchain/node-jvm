package tech.hearth.it.sync.grpc

import tech.hearth.it.NTPTime
import tech.hearth.it.NodeConfigs.GenesisAssets
import tech.hearth.it.api.SyncGrpcApi.*
import tech.hearth.it.sync.matcherFee
import tech.hearth.transaction.Asset.Waves
import tech.hearth.transaction.TxHelpers
import tech.hearth.transaction.assets.exchange.{Order, OrderType}
import io.grpc.Status.Code

import scala.collection.immutable

class ExchangeTransactionGrpcSuite extends GrpcBaseTransactionSuite with NTPTime {

  // ExchangeTransaction no longer carries its own version (see CLAUDE.md "Transaction JSON"), so this collapses
  // to version-per-order only.
  val versions: immutable.Seq[(Byte, Byte)] = for {
    o1ver <- 1 to 3
    o2ver <- 1 to 3
  } yield (o1ver.toByte, o2ver.toByte)

  val (buyer, buyerAddress)     = (firstAcc, firstAddress)
  val (seller, sellerAddress)   = (secondAcc, secondAddress)
  val (matcher, matcherAddress) = (thirdAcc, thirdAddress)

  private val exchAssetId = GenesisAssets.TestAsset.id.toString

  test("exchange tx with orders v1,v2") {
    val price              = 500000L
    val amount             = 40000000L
    val priceAssetSpending = amount * price / 100000000L
    for ((o1ver, o2ver) <- versions) {
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

      sender.exchange(matcher, buy, sell, amount, price, matcherFee, matcherFee, matcherFee, ts, waitForTx = true)

      sender.wavesBalance(buyerAddress).available shouldBe buyerWavesBalanceBefore + amount - matcherFee
      sender.wavesBalance(sellerAddress).available shouldBe sellerWavesBalanceBefore - amount - matcherFee
      sender.assetsBalance(buyerAddress, Seq(exchAssetId))(exchAssetId) shouldBe buyerAssetBalanceBefore - priceAssetSpending
      sender.assetsBalance(sellerAddress, Seq(exchAssetId))(exchAssetId) shouldBe sellerAssetBalanceBefore + priceAssetSpending
    }
  }

  test("cannot exchange non-issued assets") {
    // 32 zero bytes, base58-encoded (32 '1' characters): a syntactically valid asset id that was never
    // declared in genesis, so it is genuinely "not issued" without tripping the byte-length validation gate.
    val neverIssuedAssetId = "1" * 32

    for ((o1ver, o2ver) <- versions) {
      val ts                  = ntpTime.correctedTime()
      val expirationTimestamp = ts + Order.MaxLiveTime / 2
      val price               = 2 * Order.PriceConstant
      val amount              = 1
      val pair                = tech.hearth.transaction.assets.exchange.AssetPair.createAssetPair("WAVES", neverIssuedAssetId).get
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
        sender.exchange(matcher, buy, sell, amount, price, matcherFee, matcherFee, matcherFee, ts),
        "Assets should be issued before they can be traded",
        Code.INVALID_ARGUMENT
      )
    }
  }
}
