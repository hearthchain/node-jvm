package com.wavesplatform.http

import com.google.common.primitives.Longs
import com.wavesplatform.api.http.ApiError.{ApiKeyNotValid, DataKeysNotSpecified, MissingSenderPrivateKey, TooBigArrayAllocation}
import com.wavesplatform.api.http.{AddressApiRoute, RouteTimeout}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.Base58
import com.wavesplatform.crypto.bls.BlsKeyPair
import com.wavesplatform.db.WithState
import com.wavesplatform.db.WithState.AddrWithBalance
import com.wavesplatform.settings.{WalletSettings, WavesSettings}
import com.wavesplatform.state.IntegerDataEntry
import com.wavesplatform.test.*
import com.wavesplatform.transaction.TxHelpers
import com.wavesplatform.utils.{Schedulers, SharedSchedulerMixin}
import com.wavesplatform.wallet.Wallet
import io.netty.util.HashedWheelTimer
import monix.execution.schedulers.SchedulerService
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.model.HttpEntity.{Chunk, LastChunk}
import org.apache.pekko.http.scaladsl.model.headers.{Accept, RawHeader, `Transfer-Encoding`}
import org.apache.pekko.stream.scaladsl.Source
import play.api.libs.json.*
import play.api.libs.json.Json.JsValueWrapper

import scala.concurrent.duration.*

class AddressRouteSpec extends RouteSpec("/addresses") with RestAPISettingsHelper with SharedDomain with SharedSchedulerMixin {

  private val richAccount = TxHelpers.signer(0xaaff)

  override def settings: WavesSettings                         = DomainPresets.RideV6.copy(restAPISettings = restAPISettings)
  override def genesisBalances: Seq[WithState.AddrWithBalance] = Seq(AddrWithBalance(richAccount.toAddress, 10_000.waves))

  private val wallet = Wallet(WalletSettings(None, Some("123"), Some(ByteStr(Longs.toByteArray(System.nanoTime())))))
  wallet.generateNewAccounts(10)
  private val allAccounts  = wallet.privateKeyAccounts
  private val allAddresses = allAccounts.map(_.toAddress)

  private val utxPoolSynchronizer = DummyTransactionPublisher.accepting

  private val timeLimited: SchedulerService = Schedulers.timeBoundedFixedPool(
    new HashedWheelTimer(),
    5.seconds,
    1,
    "rest-time-limited"
  )

  override def afterAll(): Unit = {
    timeLimited.shutdown()
    super.afterAll()
  }

  private val MaxBalanceDepth = 5

  private val route = seal(
    AddressApiRoute(
      restAPISettings,
      wallet,
      domain.blockchain,
      utxPoolSynchronizer,
      new TestTime,
      timeLimited,
      new RouteTimeout(60.seconds)(using sharedScheduler),
      domain.accountsApi,
      MaxBalanceDepth
    ).route
  )

  routePath("/balance/{address}/{confirmations}") in {
    val address = TxHelpers.signer(1).toAddress

    for (_ <- 1 until 10) domain.appendBlock(TxHelpers.transfer(richAccount, address))

    val height        = domain.blockchain.height
    val minimumHeight = height - MaxBalanceDepth

    Get(routePath(s"/balance/$address/${MaxBalanceDepth + 1}")) ~> route ~> check {
      responseAs[JsObject] shouldBe Json.obj("error" -> 199, "message" -> s"Unable to get balance past height $minimumHeight")
    }

    Get(routePath(s"/balance?address=$address&height=1")) ~> route ~> check {
      responseAs[JsObject] shouldBe Json.obj("error" -> 199, "message" -> s"Unable to get balance past height $minimumHeight")
    }
  }

  routePath("/balance") in {
    val address       = TxHelpers.address(0xaaff01)
    val transferCount = 4


    for (_ <- 1 to transferCount)
      domain.appendBlock(
        TxHelpers.transfer(richAccount, address, amount = 1),
      )

    val balanceCheckHeight = domain.blockchain.height

    Get(routePath(s"/balance?address=$address&height=$balanceCheckHeight")) ~> route ~> check {
      responseAs[JsValue] shouldBe Json.arr(Json.obj("id" -> address.toString, "balance" -> transferCount))
    }
    Post(routePath(s"/balance"), Json.obj("height" -> balanceCheckHeight, "addresses" -> Seq(address.toString))) ~> route ~> check {
      responseAs[JsValue] shouldBe Json.arr(Json.obj("id" -> address.toString, "balance" -> transferCount))
    }
  }

  routePath("/seq/{from}/{to}") in {
    val r1 = Get(routePath("/seq/1/4")) ~> route ~> check {
      val response = responseAs[Seq[String]]
      response.length shouldBe 3
      allAddresses.map(_.toString) should contain allElementsOf response
      response
    }

    val r2 = Get(routePath("/seq/5/9")) ~> route ~> check {
      val response = responseAs[Seq[String]]
      response.length shouldBe 4
      allAddresses.map(_.toString) should contain allElementsOf response
      response
    }

    r1 shouldNot contain allElementsOf r2

    Get(routePath("/seq/1/9000")) ~> route ~> check {
      responseAs[JsObject] shouldBe Json.obj("error" -> 10, "message" -> "Too big sequence requested: max limit is 1000 entries")
    }

    Get(routePath("/seq/10/1")) ~> route ~> check {
      responseAs[JsObject] shouldBe Json.obj("error" -> 199, "message" -> "Invalid sequence")
    }
  }

  routePath("/validate/{address}") in {
    val t = Table(("address", "valid"), (allAddresses.map(_ -> true) :+ "3P2HNUd5VUPLMQkJmctTPEeeHumiPN2GkTb" -> false)*)

    forAll(t) { (a, v) =>
      Get(routePath(s"/validate/$a")) ~> route ~> check {
        val r = responseAs[JsObject]
        (r \ "address").as[String] shouldEqual a.toString
        (r \ "valid").as[Boolean] shouldBe v
      }
    }
  }

  routePath("/bls/{address}") in {
    val kp                   = wallet.privateKeyAccounts.head
    val address              = kp.toAddress
    val expectedBlsPublicKey = BlsKeyPair(???).publicKey

    Get(routePath(s"/bls/${TxHelpers.address(100)}")) ~> route ~> check {
      response.status shouldBe MissingSenderPrivateKey.code
      (responseAs[JsObject] \ "error").as[Int] shouldBe MissingSenderPrivateKey.id
    }

    Get(routePath(s"/bls/$address")) ~> route ~> check {
      val r = responseAs[JsObject]
      (r \ "blsPublicKey").as[String] shouldEqual expectedBlsPublicKey.base58
    }
  }

  routePath("") in {
    Post(routePath("")) ~> route should produce(ApiKeyNotValid)
    Post(routePath("")) ~> ApiKeyHeader ~> route ~> check {
      allAddresses should not contain (responseAs[JsObject] \ "address").as[String]
    }
  }

  routePath("/{address}") in {
    Delete(routePath(s"/${allAddresses.head}")) ~> ApiKeyHeader ~> route ~> check {
      (responseAs[JsObject] \ "deleted").as[Boolean] shouldBe true
    }
  }
}
