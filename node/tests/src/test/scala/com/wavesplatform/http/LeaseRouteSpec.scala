package com.wavesplatform.http

import org.apache.pekko.http.scaladsl.model.{ContentTypes, FormData, HttpEntity}
import org.apache.pekko.http.scaladsl.server.Route
import com.wavesplatform.account.Address
import com.wavesplatform.api.common.CommonAccountsApi
import com.wavesplatform.api.http.RouteTimeout
import com.wavesplatform.api.http.leasing.LeaseApiRoute
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.db.WithState
import com.wavesplatform.db.WithState.AddrWithBalance
import com.wavesplatform.settings.WavesSettings
import com.wavesplatform.state.{Blockchain, LeaseDetails, LeaseStaticInfo, Height, TransactionId}
import com.wavesplatform.test.*
import com.wavesplatform.transaction.lease.LeaseTransaction
import com.wavesplatform.transaction.TxHelpers
import com.wavesplatform.utils.SharedSchedulerMixin
import org.scalactic.source.Position
import org.scalatest.OptionValues
import play.api.libs.json.{JsArray, JsObject, Json}

import scala.concurrent.duration.*

class LeaseRouteSpec extends RouteSpec("/leasing"), OptionValues, RestAPISettingsHelper, SharedDomain, SharedSchedulerMixin {
  private val richAccount = TxHelpers.signer(200)

  override def genesisBalances: Seq[WithState.AddrWithBalance] = Seq(richAccount -> 500_000.waves, TxHelpers.defaultSigner -> 2000.waves)
  override def settings: WavesSettings                         = DomainPresets.RideV6

  private val route =
    seal(
      LeaseApiRoute(
        restAPISettings,
        domain.wallet,
        domain.blockchain,
        DummyTransactionPublisher.accepting,
        ntpTime,
        CommonAccountsApi(() => domain.blockchainUpdater.snapshotBlockchain, domain.rdb, domain.blockchain),
        new RouteTimeout(60.seconds)(using sharedScheduler)
      ).route
    )

  private def checkDetails(id: ByteStr, details: LeaseDetails, json: JsObject): Unit = {
    (json \ "id").as[ByteStr] shouldEqual id
    (json \ "originTransactionId").as[ByteStr] shouldEqual details.sourceId
    (json \ "sender").as[String] shouldEqual details.sender.toAddress.toString
    (json \ "amount").as[Long] shouldEqual details.amount.value
  }

  private def checkActiveLeasesFor(address: Address, route: Route, expectedDetails: Seq[(ByteStr, LeaseDetails)])(implicit
      pos: Position
  ): Unit =
    Get(routePath(s"/active/$address")) ~> route ~> check {
      val resp = responseAs[Seq[JsObject]]
      resp.size shouldEqual expectedDetails.size
      resp.zip(expectedDetails).foreach { case (json, (id, details)) =>
        checkDetails(id, details, json)
      }
    }

  private def toDetails(lt: LeaseTransaction, blockchain: Blockchain) =
    LeaseDetails(
      LeaseStaticInfo(lt.sender, lt.recipient, lt.amount, TransactionId(lt.id()), Height(blockchain.height)),
      LeaseDetails.Status.Active
    )

  "returns active leases which were" - {
    val transactionVersions = Table("lease transaction version", 1.toByte)

    "created and cancelled by Lease/LeaseCancel transactions" in {
      val lessor         = TxHelpers.signer(201)
      val leaseRecipient = TxHelpers.address(202)

      domain.appendBlock(TxHelpers.transfer(richAccount, lessor.toAddress, 30.006.waves))

      forAll(transactionVersions) { _ =>
        val leaseTransaction = TxHelpers.lease(lessor, leaseRecipient)
        val expectedDetails  = Seq(leaseTransaction.id() -> toDetails(leaseTransaction, domain.blockchain))

        domain.appendBlock(leaseTransaction)

        domain.liquidAndSolidAssert { () =>
          checkActiveLeasesFor(leaseTransaction.sender.toAddress, route, expectedDetails)
          checkActiveLeasesFor(leaseTransaction.recipient, route, expectedDetails)
        }

        domain.appendMicroBlock(TxHelpers.leaseCancel(leaseTransaction.id(), lessor))

        domain.liquidAndSolidAssert { () =>
          checkActiveLeasesFor(leaseTransaction.sender.toAddress, route, Seq.empty)
          checkActiveLeasesFor(leaseTransaction.recipient, route, Seq.empty)
        }
      }
    }

  }

  "multiple leases in the same block" in {
    val sender  = TxHelpers.signer(240)
    val leases1 = Seq.tabulate(10)(i => TxHelpers.lease(sender, TxHelpers.address(241 + i)))
    val leases2 = Seq.tabulate(10)(i => TxHelpers.lease(sender, TxHelpers.address(251 + i)))
    val leases3 = Seq.tabulate(10)(i => TxHelpers.lease(sender, TxHelpers.address(261 + i)))
    val leases4 = Seq.tabulate(10)(i => TxHelpers.lease(sender, TxHelpers.address(271 + i)))

    domain.appendBlock(TxHelpers.transfer(richAccount, sender.toAddress, 10_000.waves))
    domain.appendBlock(leases1*)
    domain.appendBlock(leases2*)
    domain.appendBlock(leases3*)
    domain.appendBlock(leases4*)
    domain.appendBlock()

    import monix.execution.Scheduler.Implicits.global
    val leases = domain.accountsApi.activeLeases(sender.toAddress).toListL.runSyncUnsafe(15.seconds)
    leases.size shouldEqual 40

    Get(routePath(s"/active/${sender.toAddress}")) ~> route ~> check {
      responseAs[Seq[JsObject]].map(v => (v \ "id").as[ByteStr]) should contain theSameElementsAs (
        leases4.map(_.id()) ++
          leases3.map(_.id()) ++
          leases2.map(_.id()) ++
          leases1.map(_.id())
      )
    }
  }

  routePath("/info") in {

    val lease       = TxHelpers.lease(richAccount)
    val leaseCancel = TxHelpers.leaseCancel(lease.id(), richAccount)
    domain.appendBlock(lease)
    val leaseHeight = domain.blockchain.height
    domain.appendBlock(leaseCancel)
    val leaseCancelHeight = domain.blockchain.height

    Get(routePath(s"/info/${lease.id()}")) ~> route ~> check {
      val response = responseAs[JsObject]
      response should matchJson(s"""{
                                   |  "id" : "${lease.id()}",
                                   |  "originTransactionId" : "${lease.id()}",
                                   |  "sender" : "thrth1fzw9nzceufrm8s5ncrmfxzfj2h8sazx0essmag",
                                   |  "recipient" : "thrth1qtz5fm477798qyugdrm8svcnwpnw9deung20t0",
                                   |  "amount" : 1000000000,
                                   |  "height" : $leaseHeight,
                                   |  "status" : "canceled",
                                   |  "cancelHeight" : $leaseCancelHeight,
                                   |  "cancelTransactionId" : "${leaseCancel.id()}"
                                   |}""".stripMargin)
    }

    val leasesListJson = Json.parse(s"""[{
                                       |  "id" : "${lease.id()}",
                                       |  "originTransactionId" : "${lease.id()}",
                                       |  "sender" : "thrth1fzw9nzceufrm8s5ncrmfxzfj2h8sazx0essmag",
                                       |  "recipient" : "thrth1qtz5fm477798qyugdrm8svcnwpnw9deung20t0",
                                       |  "amount" : 1000000000,
                                       |  "height" : $leaseHeight,
                                       |  "status" : "canceled",
                                       |  "cancelHeight" : $leaseCancelHeight,
                                       |  "cancelTransactionId" : "${leaseCancel.id()}"
                                       |},
                                       {
                                       |  "id" : "${lease.id()}",
                                       |  "originTransactionId" : "${lease.id()}",
                                       |  "sender" : "thrth1fzw9nzceufrm8s5ncrmfxzfj2h8sazx0essmag",
                                       |  "recipient" : "thrth1qtz5fm477798qyugdrm8svcnwpnw9deung20t0",
                                       |  "amount" : 1000000000,
                                       |  "height" : $leaseHeight,
                                       |  "status" : "canceled",
                                       |  "cancelHeight" : $leaseCancelHeight,
                                       |  "cancelTransactionId" : "${leaseCancel.id()}"
                                       |}]""".stripMargin)

    Get(routePath(s"/info?id=${lease.id()}&id=${lease.id()}")) ~> route ~> check {
      val response = responseAs[JsArray]
      response should matchJson(leasesListJson)
    }

    Post(
      routePath(s"/info"),
      HttpEntity(ContentTypes.`application/json`, Json.obj("ids" -> Seq(lease.id().toString, lease.id().toString)).toString())
    ) ~> route ~> check {
      val response = responseAs[JsArray]
      response should matchJson(leasesListJson)
    }

    Post(
      routePath(s"/info"),
      HttpEntity(
        ContentTypes.`application/json`,
        Json.obj("ids" -> (0 to restAPISettings.transactionsByAddressLimit).map(_ => lease.id().toString)).toString()
      )
    ) ~> route ~> check {
      val response = responseAs[JsObject]
      response should matchJson("""{
                                  |  "error" : 10,
                                  |  "message" : "Too big sequence requested: max limit is 10000 entries"
                                  |}""".stripMargin)
    }

    Post(
      routePath(s"/info"),
      FormData("id" -> lease.id().toString, "id" -> lease.id().toString)
    ) ~> route ~> check {
      val response = responseAs[JsArray]
      response should matchJson(leasesListJson)
    }

    Get(routePath(s"/info?id=nonvalid&id=${leaseCancel.id()}")) ~> route ~> check {
      val response = responseAs[JsObject]
      response should matchJson(s"""
                                   |{
                                   |  "error" : 116,
                                   |  "message" : "Request contains invalid IDs. nonvalid, ${leaseCancel.id()}",
                                   |  "ids" : [ "nonvalid", "${leaseCancel.id()}" ]
                                   |}""".stripMargin)
    }
  }
}
