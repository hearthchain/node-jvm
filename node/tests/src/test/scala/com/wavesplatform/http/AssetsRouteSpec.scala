package com.wavesplatform.http

import com.google.protobuf.ByteString
import com.wavesplatform.TestWallet
import com.wavesplatform.account.PublicKey
import com.wavesplatform.api.http.ApiError.{AssetIdNotSpecified, AssetsDoesNotExist, InvalidIds, TooBigArrayAllocation}
import com.wavesplatform.api.http.RouteTimeout
import com.wavesplatform.api.http.assets.AssetsApiRoute
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.db.WithDomain
import com.wavesplatform.db.WithState.AddrWithBalance
import com.wavesplatform.history.{Domain, defaultSigner}
import com.wavesplatform.settings.WavesSettings
import com.wavesplatform.state.{AssetDescription, Height, TransactionId}
import com.wavesplatform.test.*
import com.wavesplatform.test.DomainPresets.*
import com.wavesplatform.transaction.Asset.IssuedAsset
import com.wavesplatform.transaction.{AssetIdLength, Transaction, TxHelpers}
import com.wavesplatform.utils.SharedSchedulerMixin
import org.apache.pekko.http.scaladsl.model.{ContentTypes, FormData, HttpEntity, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Route
import org.scalatest.concurrent.Eventually
import play.api.libs.json.*
import play.api.libs.json.Json.JsValueWrapper

import scala.concurrent.duration.*

class AssetsRouteSpec
    extends RouteSpec("/assets")
    with Eventually
    with RestAPISettingsHelper
    with WithDomain
    with TestWallet
    with SharedSchedulerMixin {
  private val MaxDistributionDepth = 1

  def routeTest[A](
      settings: WavesSettings = DomainPresets.RideV4,
      balances: Seq[AddrWithBalance] = Seq.empty
  )(f: (Domain, Route) => A): A =
    // Blocks in these route tests are mined by defaultSigner, so it must be a committed and funded genesis generator.
    withDomain(
      settings,
      if (balances.exists(_.address == defaultSigner.toAddress)) balances
      else AddrWithBalance(defaultSigner.toAddress) +: balances
    ) { d =>
      f(
        d,
        seal(
          AssetsApiRoute(
            restAPISettings,
            60.seconds,
            testWallet,
            d.blockchain,
            () => d.blockchain.snapshotBlockchain,
            TestTime(),
            d.accountsApi,
            d.assetsApi,
            MaxDistributionDepth,
            new RouteTimeout(60.seconds)(using sharedScheduler)
          ).route
        )
      )
    }

  private val assetDesc = AssetDescription(
    TransactionId(ByteStr.empty),
    issuer = PublicKey(TxHelpers.defaultSigner.publicKey),
    name = ByteString.copyFromUtf8("test"),
    description = ByteString.copyFromUtf8("description"),
    decimals = 0,
    reissuable = true,
    totalVolume = 100,
    lastUpdatedAt = Height(1),
    nft = false,
    1,
    Height(1)
  )

  "/balance/{address}" - {
    "multiple ids" in {
      // The issuer has to exist before the domain: it is credited by the genesis snapshot
      val issuer = testWallet.generateNewAccount().get
      routeTest(balances = Seq(AddrWithBalance(issuer.toAddress, 100.waves))) { (d, route) =>
      val issueTransactions = Seq.empty[Transaction]
      d.appendBlock(issueTransactions*)

      route.anyParamTest(routePath(s"/balance/${issuer.toAddress}"), "id")(issueTransactions.reverseIterator.map(_.id().toString).toSeq*) {
        status shouldBe StatusCodes.OK
        (responseAs[JsObject] \ "balances")
          .as[Seq[JsObject]]
          .zip(issueTransactions.reverse)
          .foreach { case (jso, tx) =>
            (jso \ "balance").as[Long] shouldEqual 0L
            (jso \ "assetId").as[ByteStr] shouldEqual tx.id()
          }

      }

      route.anyParamTest(routePath(s"/balance/${issuer.toAddress}"), "id")("____", "----") {
        status shouldBe StatusCodes.BadRequest
        responseAs[JsValue] should matchJson("""{
                                               |    "error": 116,
                                               |    "message": "Request contains invalid IDs. ____, ----",
                                               |    "ids": [
                                               |        "____",
                                               |        "----"
                                               |    ]
                                               |}""".stripMargin)
      }

      withClue("over limit")(route.anyParamTest(routePath(s"/balance/${issuer.toAddress}"), "id")(Seq.fill(101)("aaa")*) {
        status shouldBe StatusCodes.BadRequest
        responseAs[JsValue] should matchJson("""{
                                               |  "error" : 10,
                                               |  "message" : "Too big sequence requested: max limit is 100 entries"
                                               |}""".stripMargin)
      })

      withClue("old GET portfolio does not include NFT")(Get(routePath(s"/balance/${issuer.toAddress}")) ~> route ~> check { // portfolio
        status shouldBe StatusCodes.OK
        val allBalances = (responseAs[JsValue] \ "balances")
          .as[Seq[JsObject]]
          .map { jso =>
            (jso \ "assetId").as[ByteStr] -> (jso \ "balance").as[Long]
          }
          .toMap

        val balancesAfterIssue = issueTransactions.init.map { it =>
          it.id() -> 0L
        }.toMap

        allBalances shouldEqual balancesAfterIssue
      })
      }
    }
  }

  routePath(s"/details/{id} - non-smart asset") in routeTest(RideV6, AddrWithBalance.enoughBalances(defaultSigner)) { (d, route) =>
    val issues = Seq.empty[Transaction]

    d.appendBlock()
    d.appendMicroBlock(issues(1))
    checkDetails(route, issues(1), issues(1).id().toString, assetDesc)

    (2 to 6).foreach { i =>
      d.appendMicroBlock(issues(i))
      checkDetails(route, issues(i), issues(i).id().toString, assetDesc.copy(sequenceInBlock = i))
    }

    d.appendKeyBlock()
    (1 to 6).foreach { i =>
      checkDetails(route, issues(i), issues(i).id().toString, assetDesc.copy(sequenceInBlock = i))
    }

    d.appendBlock((7 to 10).map(issues)*)
    (1 to 6).foreach { i =>
      checkDetails(route, issues(i), issues(i).id().toString, assetDesc.copy(sequenceInBlock = i))
    }
    (7 to 10).foreach { i =>
      checkDetails(route, issues(i), issues(i).id().toString, assetDesc.copy(sequenceInBlock = i - 6, issueHeight = Height(2)))
    }
  }

  routePath("/{assetId}/distribution/{height}/limit/{limit}") in {
    val issuer = testWallet.generateNewAccount().get
    routeTest(balances = Seq(AddrWithBalance(issuer.toAddress, 10.waves))) { (d, route) =>
    val issueTransaction: Transaction = ???
    val recipients = testWallet.generateNewAccounts(5)
    val transfers = recipients.zipWithIndex.map { case (kp, i) =>
      kp.toAddress -> ((i + 1) * 10000L)
    }
    d.appendBlock(
      issueTransaction,
      TxHelpers.massTransfer(
        issuer,
        transfers,
        ???,
        0.01.waves
      )
    )

    d.appendBlock()
    Get(routePath(s"/${issueTransaction.id()}/distribution/2/limit/$MaxAddressesPerRequest")) ~> route ~> check {
      val response = responseAs[JsObject]
      (response \ "items").as[JsObject] shouldBe Json.obj(
        transfers.map(pt => pt._1.toString -> (pt._2: JsValueWrapper)) :+
          (issuer.toAddress.toString -> (0: JsValueWrapper)) *
      )
    }

    Get(routePath(s"/${issueTransaction.id()}/distribution/2/limit/${MaxAddressesPerRequest + 1}")) ~> route ~> check {
      responseAs[JsObject] shouldBe Json.obj("error" -> 199, "message" -> s"Limit should be less than or equal to $MaxAddressesPerRequest")
    }

    Get(routePath(s"/${issueTransaction.id()}/distribution/1/limit/1")) ~> route ~> check {
      responseAs[JsObject] shouldBe Json.obj(
        "error"   -> 199,
        "message" -> s"Unable to get distribution past height ${d.blockchain.height - MaxDistributionDepth}"
      )
    }
    }
  }

  private val nonNftTestData = Table(
    ("version", "reissuable"),
    (1.toByte, false),
    (1.toByte, true),
    (2.toByte, false),
    (2.toByte, true),
    (3.toByte, false),
    (3.toByte, true)
  )

  routePath(s"/details/{id}") in {
    val sender = testWallet.generateNewAccount().get
    routeTest(balances = Seq(AddrWithBalance(sender.toAddress, 100.waves))) { (d, route) =>

    forAll(nonNftTestData) { case (version, reissuable) =>
      val name        = s"IA_$version"
      val description = s"v${version}_${if (reissuable) "" else "non-"}reissuable"
      val issueTransaction: Transaction = ???


      d.appendBlock(issueTransaction)

      route.anyParamTest(routePath("/details"), "id")(issueTransaction.id().toString) {
        status shouldBe StatusCodes.OK
        checkResponse(
          issueTransaction,
          ???,
          issueTransaction.id().toString,
          responseAs[Seq[JsObject]].head
        )
      }
    }
    }
  }

  routePath(s"/details - handles assets ids limit") in routeTest() { (d, route) =>
    val inputLimitErrMsg = TooBigArrayAllocation(restAPISettings.assetDetailsLimit).message
    val emptyInputErrMsg = AssetIdNotSpecified.message

    def checkErrorResponse(errMsg: String): Unit = {
      response.status shouldBe StatusCodes.BadRequest
      (responseAs[JsObject] \ "message").as[String] shouldBe errMsg
    }

    def checkResponse(issueTx: Transaction, idsCount: Int): Unit = {
      response.status shouldBe StatusCodes.OK

      val result = responseAs[JsArray].value
      result.size shouldBe idsCount
      (1 to idsCount).zip(responseAs[JsArray].value) foreach { case (_, json) =>
        ???
      }
    }

    val issuer = TxHelpers.signer(1)

    val issue: Transaction = ???

    d.appendBlock(issue)

    val maxLimitIds      = Seq.fill(restAPISettings.assetDetailsLimit)(issue.id().toString)
    val moreThanLimitIds = issue.id().toString +: maxLimitIds

    Get(routePath(s"/details?${maxLimitIds.map("id=" + _).mkString("&")}")) ~> route ~> check(checkResponse(issue, maxLimitIds.size))
    Get(routePath(s"/details?${moreThanLimitIds.map("id=" + _).mkString("&")}")) ~> route ~> check(checkErrorResponse(inputLimitErrMsg))
    Get(routePath("/details")) ~> route ~> check(checkErrorResponse(emptyInputErrMsg))

    Post(routePath("/details"), FormData(maxLimitIds.map("id" -> _)*)) ~> route ~> check(checkResponse(issue, maxLimitIds.size))
    Post(routePath("/details"), FormData(moreThanLimitIds.map("id" -> _)*)) ~> route ~> check(checkErrorResponse(inputLimitErrMsg))
    Post(routePath("/details"), FormData()) ~> route ~> check(checkErrorResponse(emptyInputErrMsg))

    Post(
      routePath("/details"),
      HttpEntity(ContentTypes.`application/json`, Json.obj("ids" -> Json.arr(maxLimitIds.map(id => id: JsValueWrapper)*)).toString())
    ) ~> route ~> check(checkResponse(issue, maxLimitIds.size))
    Post(
      routePath("/details"),
      HttpEntity(ContentTypes.`application/json`, Json.obj("ids" -> Json.arr(moreThanLimitIds.map(id => id: JsValueWrapper)*)).toString())
    ) ~> route ~> check(checkErrorResponse(inputLimitErrMsg))
    Post(
      routePath("/details"),
      HttpEntity(ContentTypes.`application/json`, Json.obj("ids" -> JsArray.empty).toString())
    ) ~> route ~> check(checkErrorResponse(emptyInputErrMsg))
  }

  routePath(s"/details - handles not existed assets error") in routeTest() { (_, route) =>
    val unexistedAssetIds = Seq(
      ByteStr.fill(AssetIdLength)(1),
      ByteStr.fill(AssetIdLength)(2)
    ).map(IssuedAsset.apply)

    def checkErrorResponse(): Unit = {
      response.status shouldBe StatusCodes.BadRequest
      (responseAs[JsObject] \ "message").as[String] shouldBe AssetsDoesNotExist(unexistedAssetIds).message
      (responseAs[JsObject] \ "ids").as[Seq[String]] shouldBe unexistedAssetIds.map(_.id.toString)
    }

    Get(routePath(s"/details?${unexistedAssetIds.map("id=" + _.id.toString).mkString("&")}")) ~> route ~> check(checkErrorResponse())

    Post(routePath("/details"), FormData(unexistedAssetIds.map("id" -> _.id.toString)*)) ~> route ~> check(checkErrorResponse())

    Post(
      routePath("/details"),
      HttpEntity(ContentTypes.`application/json`, Json.obj("ids" -> Json.arr(unexistedAssetIds.map(id => id: JsValueWrapper)*)).toString())
    ) ~> route ~> check(checkErrorResponse())
  }

  routePath(s"/details - handles invalid asset ids") in routeTest() { (_, route) =>
    val invalidAssetIds = Seq(
      ByteStr.fill(AssetIdLength)(1),
      ByteStr.fill(AssetIdLength)(2)
    ).map(bs => s"${bs}0")

    def checkErrorResponse(): Unit = {
      response.status shouldBe StatusCodes.BadRequest
      (responseAs[JsObject] \ "message").as[String] shouldBe InvalidIds(invalidAssetIds).message
      (responseAs[JsObject] \ "ids").as[Seq[String]] shouldBe invalidAssetIds
    }

    Get(routePath(s"/details?${invalidAssetIds.map("id=" + _).mkString("&")}")) ~> route ~> check(checkErrorResponse())

    Post(routePath("/details"), FormData(invalidAssetIds.map("id" -> _)*)) ~> route ~> check(checkErrorResponse())

    Post(
      routePath("/details"),
      HttpEntity(ContentTypes.`application/json`, Json.obj("ids" -> Json.arr(invalidAssetIds.map(id => id: JsValueWrapper)*)).toString())
    ) ~> route ~> check(checkErrorResponse())
  }

  routePath("/nft/list") - {
    "NFTs in 1 block" in {
      val issuer = testWallet.generateNewAccount().get
      routeTest(balances = Seq(AddrWithBalance(issuer.toAddress, 100.waves))) { (d, route) =>
        val nfts: Seq[Transaction] = Seq.tabulate(5) { i =>
          ???
        }
        val nonNFT: Transaction = ???
        d.appendBlock(nfts :+ nonNFT *)

        Get(routePath(s"/balance/${issuer.toAddress}/${nonNFT.id()}")) ~> route ~> check {
          val balance = responseAs[JsObject]
          (balance \ "address").as[String] shouldEqual issuer.toAddress.toString
          (balance \ "balance").as[Long] shouldEqual 0L
          (balance \ "assetId").as[String] shouldEqual nonNFT.id().toString
        }

        Get(routePath(s"/nft/${issuer.toAddress}/limit/6")) ~> route ~> check {
          status shouldBe StatusCodes.OK
          val nftList = responseAs[Seq[JsObject]]
          nftList.size shouldEqual nfts.size
          nftList.foreach { jso =>
            val nftId = (jso \ "assetId").as[ByteStr]
            val nft   = nfts.find(_.id() == nftId).get

            ???
          }
        }
      }
    }
  }

  private def checkDetails(route: Route, tx: Transaction, assetId: String, assetDesc: AssetDescription): Unit = {
    Get(routePath(s"/details/$assetId")) ~> route ~> check {
      val response = responseAs[JsObject]
      checkResponse(tx, assetDesc, assetId, response)
    }
    Get(routePath(s"/details?id=$assetId")) ~> route ~> check {
      val responses = responseAs[List[JsObject]]
      responses.foreach(response => checkResponse(tx, assetDesc, assetId, response))
    }
    Post(routePath("/details"), Json.obj("ids" -> List(s"$assetId"))) ~> route ~> check {
      val responses = responseAs[List[JsObject]]
      responses.foreach(response => checkResponse(tx, assetDesc, assetId, response))
    }
  }

  private def checkResponse(tx: Transaction, desc: AssetDescription, assetId: String, response: JsObject): Unit = {
    (response \ "assetId").as[String] shouldBe assetId
    (response \ "issueTimestamp").as[Long] shouldBe tx.timestamp
    (response \ "issuer").as[String] shouldBe desc.issuer.toAddress.toString
    (response \ "name").as[String] shouldBe desc.name.toStringUtf8
    (response \ "description").as[String] shouldBe desc.description.toStringUtf8
    (response \ "decimals").as[Int] shouldBe desc.decimals
    (response \ "reissuable").as[Boolean] shouldBe desc.reissuable
    (response \ "quantity").as[BigDecimal] shouldBe desc.totalVolume
    (response \ "minSponsoredAssetFee").asOpt[Long] shouldBe empty
    (response \ "originTransactionId").as[String] shouldBe tx.id().toString
    (response \ "sequenceInBlock").as[Int] shouldBe desc.sequenceInBlock
  }
}
