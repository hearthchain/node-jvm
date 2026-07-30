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
import com.wavesplatform.settings.{GenesisAssetSettings, WavesSettings}
import com.wavesplatform.state.{AssetDescription, Height, TransactionId}
import com.wavesplatform.test.*
import com.wavesplatform.test.DomainPresets.*
import com.wavesplatform.transaction.Asset.IssuedAsset
import com.wavesplatform.transaction.{AssetIdLength, TxHelpers}
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
  private val MaxDistributionDepth   = 1
  private val MaxAddressesPerRequest = restAPISettings.distributionAddressLimit

  def routeTest[A](
      settings: WavesSettings = DomainPresets.RideV4,
      balances: Seq[AddrWithBalance] = Seq.empty,
      assets: Seq[GenesisAssetSettings] = Seq.empty
  )(f: (Domain, Route) => A): A =
    // Blocks in these route tests are mined by defaultSigner, so it must be a committed and funded genesis generator.
    withDomain(
      settings,
      if (balances.exists(_.address == defaultSigner.toAddress)) balances
      else AddrWithBalance(defaultSigner.toAddress) +: balances,
      assets = assets
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

  /** Assets are declared in the genesis snapshot: nothing issues one any more. What an issue transaction used to
    * decide is now settings - name, description, decimals, quantity, reissuable - and what it used to imply follows
    * from the declaration itself: the origin transaction id is the asset id, the issue height is the genesis one, and
    * the sequence in block is the asset's position in the declared list. A genesis asset is never an NFT.
    */
  private val assetIssuer = TxHelpers.signer(700)

  private def genesisAsset(index: Int, quantity: Long = 100, reissuable: Boolean = true, decimals: Int = 0): GenesisAssetSettings =
    GenesisAssetSettings(
      id = ByteStr.fill(AssetIdLength)(index.toByte),
      issuer = ByteStr(assetIssuer.publicKey()).toString,
      name = "test",
      decimals = decimals,
      quantity = quantity,
      description = "description",
      reissuable = reissuable
    )

  private def descriptionOf(asset: GenesisAssetSettings, sequenceInBlock: Int): AssetDescription =
    AssetDescription(
      TransactionId(asset.id),
      issuer = PublicKey(assetIssuer.publicKey()),
      name = ByteString.copyFromUtf8(asset.name),
      description = ByteString.copyFromUtf8(asset.description),
      decimals = asset.decimals,
      reissuable = asset.reissuable,
      totalVolume = asset.quantity,
      lastUpdatedAt = Height(1),
      nft = false,
      sequenceInBlock,
      Height(1)
    )

  "/balance/{address}" - {
    "multiple ids" in {
      val assets = (1 to 3).map(genesisAsset(_))
      routeTest(
        balances = Seq(AddrWithBalance(assetIssuer.toAddress, 100.waves, assets.map(a => IssuedAsset(a.id) -> a.quantity).toMap)),
        assets = assets
      ) { (_, route) =>
        route.anyParamTest(routePath(s"/balance/${assetIssuer.toAddress}"), "id")(assets.reverseIterator.map(_.id.toString).toSeq*) {
          status shouldBe StatusCodes.OK
          (responseAs[JsObject] \ "balances")
            .as[Seq[JsObject]]
            .zip(assets.reverse)
            .foreach { case (jso, asset) =>
              (jso \ "balance").as[Long] shouldEqual asset.quantity
              (jso \ "assetId").as[ByteStr] shouldEqual asset.id
            }
        }

        route.anyParamTest(routePath(s"/balance/${assetIssuer.toAddress}"), "id")("____", "----") {
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

        withClue("over limit")(route.anyParamTest(routePath(s"/balance/${assetIssuer.toAddress}"), "id")(Seq.fill(101)("aaa")*) {
          status shouldBe StatusCodes.BadRequest
          responseAs[JsValue] should matchJson("""{
                                                 |  "error" : 10,
                                                 |  "message" : "Too big sequence requested: max limit is 100 entries"
                                                 |}""".stripMargin)
        })

        withClue("GET portfolio")(Get(routePath(s"/balance/${assetIssuer.toAddress}")) ~> route ~> check {
          status shouldBe StatusCodes.OK
          val allBalances = (responseAs[JsValue] \ "balances")
            .as[Seq[JsObject]]
            .map(jso => (jso \ "assetId").as[ByteStr] -> (jso \ "balance").as[Long])
            .toMap

          allBalances shouldEqual assets.map(a => a.id -> a.quantity).toMap
        })
      }
    }
  }

  routePath(s"/details/{id} - non-smart asset") in {
    // Ten assets, declared in order: their sequence in block is their position in that declaration
    val assets = (1 to 10).map(genesisAsset(_))
    routeTest(
      RideV6,
      AddrWithBalance
        .enoughBalances(defaultSigner) :+ AddrWithBalance(assetIssuer.toAddress, 10.waves, assets.map(a => IssuedAsset(a.id) -> a.quantity).toMap),
      assets
    ) { (d, route) =>
      assets.zipWithIndex.foreach { case (asset, idx) =>
        checkDetails(route, asset.id.toString, descriptionOf(asset, sequenceInBlock = idx + 1))
      }

      // Still the same once the genesis block is no longer the liquid one
      d.appendBlock()
      d.appendBlock()
      assets.zipWithIndex.foreach { case (asset, idx) =>
        checkDetails(route, asset.id.toString, descriptionOf(asset, sequenceInBlock = idx + 1))
      }
    }
  }

  routePath("/{assetId}/distribution/{height}/limit/{limit}") in {
    val recipients = testWallet.generateNewAccounts(5)
    val transfers  = recipients.zipWithIndex.map { case (kp, i) => kp.toAddress -> ((i + 1) * 10000L) }
    // Declared in genesis and held in full by the issuer, which then hands out the amounts below
    val asset = genesisAsset(1, quantity = transfers.map(_._2).sum)

    routeTest(
      balances = Seq(AddrWithBalance(assetIssuer.toAddress, 10.waves, Map(IssuedAsset(asset.id) -> asset.quantity))),
      assets = Seq(asset)
    ) { (d, route) =>
      d.appendBlock(TxHelpers.massTransfer(assetIssuer, transfers, IssuedAsset(asset.id), 0.01.waves))
      d.appendBlock()

      Get(routePath(s"/${asset.id}/distribution/2/limit/$MaxAddressesPerRequest")) ~> route ~> check {
        val response = responseAs[JsObject]
        // The issuer handed out everything it held, and an address holding nothing is not part of the distribution
        (response \ "items").as[JsObject] shouldBe Json.obj(transfers.map(pt => pt._1.toString -> (pt._2: JsValueWrapper))*)
      }

      Get(routePath(s"/${asset.id}/distribution/2/limit/${MaxAddressesPerRequest + 1}")) ~> route ~> check {
        responseAs[JsObject] shouldBe Json.obj("error" -> 199, "message" -> s"Limit should be less than or equal to $MaxAddressesPerRequest")
      }

      Get(routePath(s"/${asset.id}/distribution/1/limit/1")) ~> route ~> check {
        responseAs[JsObject] shouldBe Json.obj(
          "error"   -> 199,
          "message" -> s"Unable to get distribution past height ${d.blockchain.height - MaxDistributionDepth}"
        )
      }
    }
  }

  routePath(s"/details/{id}") in {
    // The version dimension is gone with the issue transaction; what a declaration still varies is reissuable
    val assets = Seq(genesisAsset(1, reissuable = false), genesisAsset(2, reissuable = true))
    routeTest(
      balances = Seq(AddrWithBalance(assetIssuer.toAddress, 100.waves, assets.map(a => IssuedAsset(a.id) -> a.quantity).toMap)),
      assets = assets
    ) { (_, route) =>
      assets.zipWithIndex.foreach { case (asset, idx) =>
        route.anyParamTest(routePath("/details"), "id")(asset.id.toString) {
          status shouldBe StatusCodes.OK
          checkResponse(descriptionOf(asset, sequenceInBlock = idx + 1), asset.id.toString, responseAs[Seq[JsObject]].head)
        }
      }
    }
  }

  routePath(s"/details - handles assets ids limit") in {
    val asset = genesisAsset(1)
    routeTest(
      balances = Seq(AddrWithBalance(assetIssuer.toAddress, 100.waves, Map(IssuedAsset(asset.id) -> asset.quantity))),
      assets = Seq(asset)
    ) { (_, route) =>
      val inputLimitErrMsg = TooBigArrayAllocation(restAPISettings.assetDetailsLimit).message
      val emptyInputErrMsg = AssetIdNotSpecified.message

      def checkErrorResponse(errMsg: String): Unit = {
        response.status shouldBe StatusCodes.BadRequest
        (responseAs[JsObject] \ "message").as[String] shouldBe errMsg
      }

      def checkAllAreThisAsset(idsCount: Int): Unit = {
        response.status shouldBe StatusCodes.OK

        val result = responseAs[JsArray].value
        result.size shouldBe idsCount
        // The same id repeated, so every entry describes that one asset
        result.foreach(json => checkResponse(descriptionOf(asset, sequenceInBlock = 1), asset.id.toString, json.as[JsObject]))
      }

      val maxLimitIds      = Seq.fill(restAPISettings.assetDetailsLimit)(asset.id.toString)
      val moreThanLimitIds = asset.id.toString +: maxLimitIds

      Get(routePath(s"/details?${maxLimitIds.map("id=" + _).mkString("&")}")) ~> route ~> check(checkAllAreThisAsset(maxLimitIds.size))
      Get(routePath(s"/details?${moreThanLimitIds.map("id=" + _).mkString("&")}")) ~> route ~> check(checkErrorResponse(inputLimitErrMsg))
      Get(routePath("/details")) ~> route ~> check(checkErrorResponse(emptyInputErrMsg))

      Post(routePath("/details"), FormData(maxLimitIds.map("id" -> _)*)) ~> route ~> check(checkAllAreThisAsset(maxLimitIds.size))
      Post(routePath("/details"), FormData(moreThanLimitIds.map("id" -> _)*)) ~> route ~> check(checkErrorResponse(inputLimitErrMsg))
      Post(routePath("/details"), FormData()) ~> route ~> check(checkErrorResponse(emptyInputErrMsg))

      Post(
        routePath("/details"),
        HttpEntity(ContentTypes.`application/json`, Json.obj("ids" -> Json.arr(maxLimitIds.map(id => id: JsValueWrapper)*)).toString())
      ) ~> route ~> check(checkAllAreThisAsset(maxLimitIds.size))
      Post(
        routePath("/details"),
        HttpEntity(ContentTypes.`application/json`, Json.obj("ids" -> Json.arr(moreThanLimitIds.map(id => id: JsValueWrapper)*)).toString())
      ) ~> route ~> check(checkErrorResponse(inputLimitErrMsg))
      Post(
        routePath("/details"),
        HttpEntity(ContentTypes.`application/json`, Json.obj("ids" -> JsArray.empty).toString())
      ) ~> route ~> check(checkErrorResponse(emptyInputErrMsg))
    }
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

  /* There was an "/nft/list - NFTs in 1 block" property here. An asset can only come from the genesis snapshot now,
   * and `GenesisSnapshot` builds every one of them with `nft = false` - there is no way to declare an NFT, so the
   * property cannot be set up at all. It has to come back with whatever issues NFTs, if anything does.
   */

  private def checkDetails(route: Route, assetId: String, assetDesc: AssetDescription): Unit = {
    Get(routePath(s"/details/$assetId")) ~> route ~> check {
      checkResponse(assetDesc, assetId, responseAs[JsObject])
    }
    Get(routePath(s"/details?id=$assetId")) ~> route ~> check {
      responseAs[List[JsObject]].foreach(checkResponse(assetDesc, assetId, _))
    }
    Post(routePath("/details"), Json.obj("ids" -> List(s"$assetId"))) ~> route ~> check {
      responseAs[List[JsObject]].foreach(checkResponse(assetDesc, assetId, _))
    }
  }

  private def checkResponse(desc: AssetDescription, assetId: String, response: JsObject): Unit = {
    (response \ "assetId").as[String] shouldBe assetId
    (response \ "issuer").as[String] shouldBe desc.issuer.toAddress.toString
    (response \ "name").as[String] shouldBe desc.name.toStringUtf8
    (response \ "description").as[String] shouldBe desc.description.toStringUtf8
    (response \ "decimals").as[Int] shouldBe desc.decimals
    (response \ "reissuable").as[Boolean] shouldBe desc.reissuable
    (response \ "quantity").as[BigDecimal] shouldBe desc.totalVolume
    (response \ "minSponsoredAssetFee").asOpt[Long] shouldBe empty
    // The asset was declared, not issued, so it stands in for its own origin transaction
    (response \ "originTransactionId").as[String] shouldBe assetId
    (response \ "sequenceInBlock").as[Int] shouldBe desc.sequenceInBlock
  }
}
