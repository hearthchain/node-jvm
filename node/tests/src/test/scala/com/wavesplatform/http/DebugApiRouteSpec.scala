package com.wavesplatform.http

import com.typesafe.config.ConfigObject
import com.wavesplatform.*
import com.wavesplatform.api.http.ApiError.ApiKeyNotValid
import com.wavesplatform.api.http.DebugApiRoute.AccountMiningInfo
import com.wavesplatform.api.http.{DebugApiRoute, RouteTimeout}
import com.wavesplatform.block.Block
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.db.WithState.AddrWithBalance
import com.wavesplatform.features.BlockchainFeatures
import com.wavesplatform.lagonaki.mocks.TestBlock
import com.wavesplatform.mining.TestMiner
import com.wavesplatform.network.PeerDatabase
import com.wavesplatform.settings.WavesSettings
import com.wavesplatform.state.StateHash.Section
import com.wavesplatform.state.diffs.ENOUGH_AMT
import com.wavesplatform.state.{Blockchain, Height, StateHash}
import com.wavesplatform.test.*
import com.wavesplatform.transaction.{Transaction, TxHelpers}
import com.wavesplatform.utils.SharedSchedulerMixin
import monix.eval.Task
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import org.scalatest.OptionValues
import play.api.libs.json.{JsArray, JsObject, JsValue, Json}

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.*
import scala.util.Random

//noinspection ScalaStyle
class DebugApiRouteSpec
    extends RouteSpec("/debug")
    with RestAPISettingsHelper
    with TestWallet
    with NTPTime
    with SharedDomain
    with OptionValues
    with SharedSchedulerMixin {

  override def settings: WavesSettings = DomainPresets.RideV6.copy(
    dbSettings = DomainPresets.RideV6.dbSettings.copy(storeStateHashes = true),
    restAPISettings = restAPISettings
  )

  private val configObject: ConfigObject = settings.config.root()

  private val richAccount = TxHelpers.signer(905)

  override def genesisBalances: Seq[AddrWithBalance] = Seq(AddrWithBalance(richAccount.toAddress, 50_000.waves))

  val block: Block = TestBlock.create(Nil).block
  val testStateHash: StateHash = {
    import com.wavesplatform.utils.byteStrOrdering
    def randomHash: ByteStr = ByteStr(Array.fill(32)(Random.nextInt(256).toByte))

    val hashes = Section.values.map((_, randomHash)).toMap
    StateHash(randomHash, hashes)
  }

  val debugApiRoute: DebugApiRoute =
    DebugApiRoute(
      settings,
      ntpTime,
      domain.blockchain,
      domain.accountsApi,
      domain.transactionsApi,
      domain.assetsApi,
      PeerDatabase.NoOp,
      new ConcurrentHashMap(),
      (blockId, _) => Task(domain.blockchain.removeAfter(blockId).map(_ => ())),
      domain.utxPool,
      TestMiner.SafelyDisabled,
      null,
      null,
      null,
      null,
      configObject,
      domain.rocksDBWriter,
      new RouteTimeout(60.seconds)(using sharedScheduler),
      sharedScheduler
    )

  private val route = seal(debugApiRoute.route)

  routePath("/configInfo") - {
    "requires api-key header" in {
      Get(routePath("/configInfo?full=true")) ~> route should produce(ApiKeyNotValid)
      Get(routePath("/configInfo?full=false")) ~> route should produce(ApiKeyNotValid)
    }
  }

  routePath("/balances/history/{address}") - {
    val acc1 = TxHelpers.signer(1001)
    val acc2 = TxHelpers.signer(1002)

    val initBalance = 10.waves

    "works" in {
      val tx1 = TxHelpers.transfer(acc2, acc1.toAddress, 1.waves)
      val tx2 = TxHelpers.transfer(acc1, acc2.toAddress, 3.waves)
      val tx3 = TxHelpers.transfer(acc2, acc1.toAddress, 4.waves)
      val tx4 = TxHelpers.transfer(acc1, acc2.toAddress, 5.waves)

      domain.appendBlock(
        TxHelpers.massTransfer(
          richAccount,
          Seq(
            acc1.toAddress -> initBalance,
            acc2.toAddress -> initBalance
          ),
          fee = 0.002.waves
        )
      )
      val initialHeight = domain.blockchain.height
      domain.appendBlock(tx1)
      domain.appendBlock(tx2)
      domain.appendBlock()
      domain.appendBlock(tx3)
      domain.appendBlock(tx4)
      domain.appendBlock()

      val expectedBalance2 = initBalance - tx1.fee.value - tx1.amount.value
      val expectedBalance3 = expectedBalance2 + tx2.amount.value
      val expectedBalance5 = expectedBalance3 - tx3.fee.value - tx3.amount.value
      val expectedBalance6 = expectedBalance5 + tx4.amount.value

      Get(routePath(s"/balances/history/${acc2.toAddress}")) ~> route ~> check {
        status shouldBe StatusCodes.OK
        responseAs[JsArray] shouldBe Json.toJson(
          Seq(
            initialHeight + 5 -> expectedBalance6,
            initialHeight + 4 -> expectedBalance5,
            initialHeight + 2 -> expectedBalance3,
            initialHeight + 1 -> expectedBalance2,
            initialHeight + 0 -> initBalance
          ).map { case (height, balance) =>
            Json.obj("height" -> height, "balance" -> balance)
          }
        )
      }
    }
  }

  routePath("/stateHash") - {
    "works" - {
      "at nonexistent height" in {
        Get(routePath(s"/stateHash/${domain.blockchain.height}")) ~> route ~> check {
          status shouldBe StatusCodes.NotFound
        }
      }

      "at existing height" in {
        (0 until (3 - domain.blockchain.height).min(0)) foreach { _ =>
          domain.appendBlock()
        }

        val lastButOneHeight               = domain.blockchain.height - 1
        val lastButOneHeader               = domain.blockchain.blockHeader(lastButOneHeight).value
        val lastButOneStateHash            = domain.rocksDBWriter.loadStateHash(Height(lastButOneHeight)).value
        val lastButOneStateHashJson        = StateHash.toJson(lastButOneStateHash)
        def field(name: String)            = (lastButOneStateHashJson \ name).as[String]

        val expectedResponse = Json.obj(
          "stateHash"         -> field("stateHash"),
          "wavesBalanceHash"  -> field("wavesBalanceHash"),
          "assetBalanceHash"  -> field("assetBalanceHash"),
          "dataEntryHash"     -> field("dataEntryHash"),
          "accountScriptHash" -> field("accountScriptHash"),
          "assetScriptHash"   -> field("assetScriptHash"),
          "leaseBalanceHash"  -> field("leaseBalanceHash"),
          "leaseStatusHash"   -> field("leaseStatusHash"),
          "sponsorshipHash"   -> field("sponsorshipHash"),
          "aliasHash"         -> field("aliasHash"),
          "snapshotHash"      -> domain.rocksDBWriter.snapshotStateHash(lastButOneHeight),
          "blockId"           -> lastButOneHeader.id().toString,
          "baseTarget"        -> lastButOneHeader.header.baseTarget,
          "height"            -> lastButOneHeight,
          "version"           -> Version.VersionString
        )

        Get(routePath(s"/stateHash/last")) ~> route ~> check {
          status shouldBe StatusCodes.OK
          responseAs[JsObject] shouldBe expectedResponse
        }

        Get(routePath(s"/stateHash/$lastButOneHeight")) ~> route ~> check {
          status shouldBe StatusCodes.OK
          responseAs[JsObject] shouldBe expectedResponse
        }
      }
    }
  }

  routePath("/validate") - {
    def validatePost(tx: Transaction) =
      Post(routePath("/validate"), HttpEntity(ContentTypes.`application/json`, tx.json().toString()))

    "takes the priority pool into account" in {
      domain.appendBlock(TxHelpers.transfer(to = TxHelpers.secondAddress, amount = 1.waves + TestValues.fee))

      val tx = TxHelpers.transfer(TxHelpers.secondSigner, TestValues.address, 1.waves)
      validatePost(tx) ~> route ~> check {
        val json = responseAs[JsValue]
        (json \ "valid").as[Boolean] shouldBe true
        (json \ "validationTime").as[Int] shouldBe 1000 +- 1000
      }
    }

    "valid tx" in {
      val tx = TxHelpers.transfer(TxHelpers.defaultSigner, TestValues.address, 1.waves)
      validatePost(tx) ~> route ~> check {
        val json = responseAs[JsValue]
        (json \ "valid").as[Boolean] shouldBe true
        (json \ "validationTime").as[Int] shouldBe 1000 +- 1000
      }
    }

    "invalid tx" in {
      val tx = TxHelpers.transfer(TxHelpers.defaultSigner, TestValues.address, ENOUGH_AMT)
      validatePost(tx) ~> route ~> check {
        val json = responseAs[JsValue]
        (json \ "valid").as[Boolean] shouldBe false
        (json \ "validationTime").as[Int] shouldBe 1000 +- 1000
        (json \ "error").as[String] should include("Attempt to transfer unavailable funds")
      }
    }

  }

  routePath("/minerInfo") - {
    "returns info from wallet if miner private keys not specified in config" in {
      val acc = domain.wallet.generateNewAccount()

      acc shouldBe defined
      Get(routePath("/minerInfo")) ~> ApiKeyHeader ~> route ~> check {
        responseAs[Seq[AccountMiningInfo]].map(_.address) shouldBe acc.toSeq.map(_.toAddress.toString)
      }
    }

    "returns info only for miner private keys from config when specified" in {
      val minerAccs   = Seq(TxHelpers.signer(1090), TxHelpers.signer(1091))
      val debugRoute  = debugApiRoute.copy(ws = debugApiRoute.ws.copy(minerSettings = ???))

      Get(routePath("/minerInfo")) ~> ApiKeyHeader ~> debugRoute.route ~> check {
        responseAs[Seq[AccountMiningInfo]].map(_.address) shouldBe minerAccs.map(_.toAddress.toString)
      }
    }
  }
}
