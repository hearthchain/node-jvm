package tech.hearth.http

import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, MediaType, StatusCodes}
import tech.hearth.api.http.{BroadcastRoute, RouteTimeout, TransactionsApiRoute}
import tech.hearth.common.state.ByteStr
import tech.hearth.db.WithDomain
import tech.hearth.mining.GeneratorKeys
import tech.hearth.network.TransactionPublisher
import tech.hearth.test.TestTime
import tech.hearth.transaction.Asset.{IssuedAsset, Hearth}
import tech.hearth.transaction.assets.exchange.*
import tech.hearth.transaction.smart.script.trace.TracedResult
import tech.hearth.transaction.serialization.impl.PBTransactionSerializer
import tech.hearth.transaction.{AssetIdLength, Transaction, TxHelpers}
import tech.hearth.utils.{EmptyBlockchain, SharedSchedulerMixin}
import io.netty.channel.Channel
import play.api.libs.json.JsObject

import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.util.Random

class TransactionBroadcastSpec extends RouteSpec("/transactions") with RestAPISettingsHelper with WithDomain with SharedSchedulerMixin {
  private val seed = new Array[Byte](32)
  Random.nextBytes(seed)

  private val transactionPublisher: TransactionPublisher = (_: Transaction, _: Option[Channel]) => Future.successful(TracedResult(Right(true)))
  private val testTime                                   = new TestTime

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
            TxHelpers.exchange(TxHelpers.order(OrderType.BUY, asset, Hearth), TxHelpers.order(OrderType.SELL, asset, Hearth)),
            TxHelpers.exchange(TxHelpers.order(OrderType.BUY, Hearth, asset), TxHelpers.order(OrderType.SELL, Hearth, asset)),
            TxHelpers.exchange(
              TxHelpers.order(OrderType.BUY, IssuedAsset(validSizeAssetId), Hearth, asset),
              TxHelpers.order(OrderType.SELL, IssuedAsset(validSizeAssetId), Hearth, asset)
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

  "protobuf-encoded transactions" - {
    val tx = TxHelpers.transfer()

    def protobufPost(bytes: Array[Byte], mediaType: MediaType.Binary = BroadcastRoute.`application/x-protobuf`) =
      Post(routePath("/broadcast"), HttpEntity(mediaType.toContentType, bytes))

    "are broadcast when sent as application/x-protobuf" in {
      protobufPost(PBTransactionSerializer.bytes(tx)) ~> route ~> check {
        status shouldBe StatusCodes.OK
        (responseAs[JsObject] \ "id").as[String] shouldBe tx.id().toString
      }
    }

    "are broadcast when sent as application/protobuf" in {
      protobufPost(PBTransactionSerializer.bytes(tx), BroadcastRoute.`application/protobuf`) ~> route ~> check {
        status shouldBe StatusCodes.OK
        (responseAs[JsObject] \ "id").as[String] shouldBe tx.id().toString
      }
    }

    "are rejected when the payload is not a valid SignedTransaction" in {
      protobufPost(Array[Byte](1, 2, 3)) ~> route ~> check {
        responseAs[JsObject].toString should include("Error while parsing protobuf transaction")
      }
    }

    "do not affect JSON broadcast" in {
      Post(routePath("/broadcast"), HttpEntity(ContentTypes.`application/json`, tx.json().toString())) ~> route ~> check {
        status shouldBe StatusCodes.OK
        (responseAs[JsObject] \ "id").as[String] shouldBe tx.id().toString
      }
    }

    "reject other content types with 415" in {
      Post(routePath("/broadcast"), HttpEntity(ContentTypes.`text/plain(UTF-8)`, "not a transaction")) ~> route ~> check {
        status shouldBe StatusCodes.UnsupportedMediaType
      }
    }
  }
}
