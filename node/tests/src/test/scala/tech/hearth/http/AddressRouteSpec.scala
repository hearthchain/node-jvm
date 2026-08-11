package tech.hearth.http

import com.google.common.primitives.Longs
import tech.hearth.api.http.ApiError.{ApiKeyNotValid, MissingSenderPrivateKey}
import tech.hearth.api.http.{AddressApiRoute, RouteTimeout}
import tech.hearth.common.state.ByteStr
import tech.hearth.db.WithState
import tech.hearth.db.WithState.AddrWithBalance
import tech.hearth.settings.{WalletSettings, HearthSettings}
import tech.hearth.test.*
import tech.hearth.transaction.TxHelpers
import tech.hearth.utils.{Schedulers, SharedSchedulerMixin}
import tech.hearth.wallet.Wallet
import io.netty.util.HashedWheelTimer
import monix.execution.schedulers.SchedulerService
import org.apache.pekko.http.scaladsl.model.*
import play.api.libs.json.*

import scala.concurrent.duration.*

class AddressRouteSpec extends RouteSpec("/addresses") with RestAPISettingsHelper with SharedDomain with SharedSchedulerMixin {

  private val richAccount = TxHelpers.signer(0xaaff)

  // signer(500) is one of this node's generators, so that /addresses/bls has something to answer with
  override def settings: HearthSettings = {
    val base = DomainPresets.RideV6.copy(restAPISettings = restAPISettings)
    base.copy(minerSettings = base.minerSettings.copy(accounts = Seq(TxHelpers.miningAccountSettings(500))))
  }
  override def genesisBalances: Seq[WithState.AddrWithBalance] = Seq(AddrWithBalance(richAccount.toAddress, 10_000.hearth))

  private val wallet = Wallet(WalletSettings(None, Some("123"), Some(ByteStr(Longs.toByteArray(System.nanoTime())))))
  wallet.generateNewAccounts(10)
  private val allAccounts  = wallet.privateKeyAccounts
  private val allAddresses = allAccounts.map(_.toAddress)

  private val utxPoolSynchronizer = DummyTransactionPublisher.accepting

  private val timer: HashedWheelTimer = new HashedWheelTimer()
  private val timeLimited: SchedulerService = Schedulers.timeBoundedFixedPool(
    timer,
    5.seconds,
    1,
    "rest-time-limited"
  )

  override def afterAll(): Unit = {
    timeLimited.shutdown()
    timer.stop()
    super.afterAll()
  }

  private val MaxBalanceDepth = 5

  private val route = seal(
    AddressApiRoute(
      restAPISettings,
      wallet,
      domain.generatorKeys,
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
        TxHelpers.transfer(richAccount, address, amount = 1)
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
    // The endorser key of one of this node's generators, which come from hearth.miner.accounts - the wallet holds none
    val address              = domain.generatorKeys.accounts.head.address
    val expectedBlsPublicKey = domain.generatorKeys.endorserPublicKey(address).value

    Get(routePath(s"/bls/${TxHelpers.address(100)}")) ~> route ~> check {
      response.status shouldBe MissingSenderPrivateKey.code
      (responseAs[JsObject] \ "error").as[Int] shouldBe MissingSenderPrivateKey.id
    }

    Get(routePath(s"/bls/$address")) ~> route ~> check {
      val r = responseAs[JsObject]
      (r \ "blsPublicKey").as[String] shouldEqual expectedBlsPublicKey.base16
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
