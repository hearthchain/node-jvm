package com.wavesplatform.it.sync

import com.typesafe.config.Config
import com.wavesplatform.api.http.ApiError.{CustomValidationError, TooBigArrayAllocation}
import com.wavesplatform.it.NodeConfigs.GenesisAssets
import com.wavesplatform.it.api.SyncHttpApi.*
import com.wavesplatform.it.transactions.BaseTransactionSuite
import com.wavesplatform.it.{NTPTime, NodeConfigs}
import com.wavesplatform.state.Height
import com.wavesplatform.transaction.TxHelpers
import play.api.libs.json.*

import scala.util.Random

class AddressApiSuite extends BaseTransactionSuite with NTPTime {
  test("balance at height") {
    val address = TxHelpers.signer(1000).toAddress.toString
    sender.transfer(sender.keyPair, address, 1, waitForTx = true)
    nodes.waitForHeightArise()
    sender.transfer(sender.keyPair, address, 1, waitForTx = true)
    nodes.waitForHeightArise()
    sender.transfer(sender.keyPair, address, 1, waitForTx = true)
    nodes.waitForHeightArise()

    val Seq(_, h2, _)     = sender.debugBalanceHistory(address): @unchecked
    val Seq((_, balance)) = sender.accountsBalances(Some(Height(h2.height)), Seq(address)): @unchecked
    balance shouldBe 2
  }

  test("balances for waves should be correct") {
    assertBalances(None)
  }

  test("balances for issued asset should be correct") {
    assertBalances(Some(GenesisAssets.TestAsset.id.toString))
  }

  test("limit violation requests should be handled correctly") {
    val limit     = miner.config.getInt("waves.rest-api.transactions-by-address-limit")
    val addresses = List.fill(limit + 1)(firstAddress)
    assertApiError(
      miner.get(s"/addresses/balance?${addresses.map(a => s"address=$a").mkString("&")}"),
      TooBigArrayAllocation
    )
    assertApiError(
      miner.accountsBalances(None, addresses),
      TooBigArrayAllocation
    )
  }

  test("requests to the illegal height should be handled correctly") {
    val height = miner.height + 100
    assertApiError(
      miner.get(s"/addresses/balance?height=$height&address=$firstKeyPair"),
      CustomValidationError(s"Illegal height: $height")
    )
    assertApiError(
      miner.accountsBalances(Some(height), Seq("firstAddress")),
      CustomValidationError(s"Illegal height: $height")
    )

    assertApiError(
      miner.get(s"/addresses/balance?height=-1&address=$firstKeyPair"),
      CustomValidationError("Illegal height: -1")
    )
    assertApiError(
      miner.accountsBalances(Some(Height(-1)), Seq("firstAddress")),
      CustomValidationError("Illegal height: -1")
    )
  }

  private def assertBalances(asset: Option[String]): Unit = {
    val addressesAndBalances = (1 to 5).map(i => (TxHelpers.signer(1000 + i).toAddress.toString, (i * 100).toLong)).toList

    val firstAddresses   = addressesAndBalances.slice(0, 2)
    val secondAddresses  = addressesAndBalances.slice(2, 5)
    val illegalAddresses = List.fill(3)(Random.nextString(10))

    val heightBefore  = transferAndReturnHeights(firstAddresses, asset).min - 1
    val heightBetween = nodes.waitForHeightArise()
    nodes.waitForHeightArise() // prevents next transfers from accepting on the heightBetween
    val heightAfter = transferAndReturnHeights(secondAddresses, asset).max

    nodes.waitForHeightArise()

    val requestedAddresses = addressesAndBalances.map(_._1) ++ illegalAddresses :+ firstAddresses.head._1 :+ secondAddresses.head._1

    // balances at the height before all transfers
    checkBalances(addressesAndBalances.map { case (a, _) => (a, 0L) }, requestedAddresses, Some(Height(heightBefore)), asset)
    // balances at the height after the 2nd transfer
    checkBalances(firstAddresses ++ secondAddresses.map { case (a, _) => (a, 0L) }, requestedAddresses, Some(heightBetween), asset)
    // balances at the height after all transfers
    checkBalances(addressesAndBalances, requestedAddresses, Some(Height(heightAfter)), asset)
    // balances at the current height
    checkBalances(addressesAndBalances, requestedAddresses, None, asset)
  }

  private def transferAndReturnHeights(addresses: List[(String, Long)], asset: Option[String]): List[Int] = {
    // Genesis assets are only ever distributed to firstKeyPair/secondKeyPair, never to a node's own account (see
    // CLAUDE.md's node-it fixtures notes), so an asset transfer has to be sent from firstKeyPair; miner.keyPair
    // still works fine for the plain-WAVES case.
    val sender = asset.fold(miner.keyPair)(_ => firstKeyPair)
    val ids    = addresses.map { case (address, a) => miner.transfer(sender, address, a, minFee, asset).id }
    ids.map(id => miner.waitForTransaction(id).height)
  }

  private def checkBalances(expected: List[(String, Long)], addresses: List[String], height: Option[Height], assetId: Option[String]): Unit = {
    val getResponse = miner.get(
      s"/addresses/balance?${height.fold("")(h => s"height=$h&")}${assetId.fold("")(a => s"asset=$a&")}${addresses.map(a => s"address=$a").mkString("&")}"
    )

    val getResult = Json.parse(getResponse.getResponseBody).as[List[JsObject]].map(r => ((r \ "id").as[String], (r \ "balance").as[Long]))

    getResult should contain theSameElementsAs expected

    val postResult = miner.accountsBalances(height, addresses, assetId)

    postResult should contain theSameElementsAs expected
  }

  import NodeConfigs.*
  override protected def nodeConfigs: Seq[Config] =
    Seq(BiggestMiner.quorum(0).overrides("waves.rest-api.transactions-by-address-limit = 20"), NotMiner)
}
