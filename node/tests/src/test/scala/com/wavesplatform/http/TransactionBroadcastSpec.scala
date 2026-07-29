package com.wavesplatform.http

import com.wavesplatform.api.http.{RouteTimeout, TransactionsApiRoute}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.db.WithDomain
import com.wavesplatform.mining.GeneratorKeys
import com.wavesplatform.network.TransactionPublisher
import com.wavesplatform.test.TestTime
import com.wavesplatform.transaction.Asset.{IssuedAsset, Waves}
import com.wavesplatform.transaction.assets.exchange.*
import com.wavesplatform.transaction.smart.script.trace.TracedResult
import com.wavesplatform.transaction.{
  AssetIdLength,
  Transaction,
  TxHelpers,
}
import com.wavesplatform.utils.{EmptyBlockchain, SharedSchedulerMixin}
import io.netty.channel.Channel
import play.api.libs.json.JsObject

import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.util.Random

class TransactionBroadcastSpec
    extends RouteSpec("/transactions")
    with RestAPISettingsHelper
    with WithDomain
    with SharedSchedulerMixin {
  private val seed = new Array[Byte](32)
  Random.nextBytes(seed)

  private val transactionPublisher: TransactionPublisher = (_: Transaction, _: Option[Channel]) =>
    Future.successful(TracedResult(Right(true)))
  private val testTime = new TestTime

  private val transactionsApiRoute = new TransactionsApiRoute(
    restAPISettings,
    null,
    null,
    GeneratorKeys.Empty,
    EmptyBlockchain,
    () => ???,
    () => 0,
    transactionPublisher,
    testTime,
    new RouteTimeout(60.seconds)(using sharedScheduler)
  )

  private val route = seal(transactionsApiRoute.route)


  "transactions with asset id field" - {
    "return error when asset id with wrong size is passed" in {
      val validSizeAssetId = ByteStr.fill(AssetIdLength)(1)
      val wrongAssetIds = Seq(
        Array.fill(AssetIdLength - 1)(1.toByte),
        Array.fill(AssetIdLength + 1)(1.toByte)
      ).map(arr => IssuedAsset(ByteStr(arr)))

      val txs = wrongAssetIds.flatMap { asset =>
        Seq(
          TxHelpers.massTransfer(asset = asset),
          TxHelpers.transfer(asset = asset)
        ) ++
          Seq(
            TxHelpers.exchange(TxHelpers.order(OrderType.BUY, asset, Waves), TxHelpers.order(OrderType.SELL, asset, Waves)),
            TxHelpers.exchange(TxHelpers.order(OrderType.BUY, Waves, asset), TxHelpers.order(OrderType.SELL, Waves, asset)),
            TxHelpers.exchange(
              TxHelpers.order(OrderType.BUY, IssuedAsset(validSizeAssetId), Waves, asset),
              TxHelpers.order(OrderType.SELL, IssuedAsset(validSizeAssetId), Waves, asset)
            )
          )
      }

      txs.foreach { tx =>
        Post(routePath("/broadcast"), tx.json()) ~> route ~> check {
          val result = responseAs[JsObject].toString
          result should include regex "Invalid validation. Size of asset id.*not equal 32 bytes"
        }
      }
    }
  }
}
