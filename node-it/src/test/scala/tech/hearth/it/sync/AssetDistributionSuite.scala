package tech.hearth.it.sync

import tech.hearth.it.Node
import tech.hearth.it.NodeConfigs.GenesisAssets
import tech.hearth.it.api.SyncHttpApi.*
import tech.hearth.it.transactions.BaseTransactionSuite
import tech.hearth.state.{AssetDistributionPage, Height}
import tech.hearth.transaction.TxHelpers
import tech.hearth.transaction.transfer.MassTransferTransaction
import org.scalatest.CancelAfterFailure

import scala.concurrent.duration.*

// There is no issue transaction any more, so every test below shares the single genesis asset
// (NodeConfigs.GenesisAssets.TestAsset) instead of minting a fresh one, and asserts balance deltas
// rather than absolute totals since earlier tests in this suite may have already spent some of it.
class AssetDistributionSuite extends BaseTransactionSuite with CancelAfterFailure {

  lazy val node: Node = nodes.head

  // The fixture asset is held by firstKeyPair/secondKeyPair (see template.conf), not by any node's own account.
  private lazy val issuer = firstKeyPair
  private val assetId     = GenesisAssets.TestAsset.id.toString

  test("'Asset distribution at height' method works properly") {
    val transferAmount = 1000000L

    val addresses           = nodes.map(_.keyPair.toAddress).filter(_ != issuer.toAddress).toList
    val initialHeight       = node.height
    val issuerBalanceBefore = node.assetBalance(issuer.toAddress.toString, assetId).balance

    nodes.waitForHeightArise()

    node.massTransfer(
      issuer,
      addresses.map(addr => MassTransferTransaction.Transfer(addr.toString, transferAmount)),
      minFee + (minFee * addresses.size),
      assetId = Some(assetId),
      waitForTx = true
    )

    nodes.waitForHeightArise()

    val distributionHeight = node.height

    nodes.waitForHeightArise()

    // The fixture asset is already distributed at genesis (to issuer and secondKeyPair), so the distribution at
    // initialHeight isn't empty; only the new recipients below shouldn't appear in it yet.
    val distributionAtInitialHeight = node.assetDistributionAtHeight(assetId, initialHeight, 100).items
    addresses.foreach(addr => distributionAtInitialHeight should not contain key(addr.toString))

    val assetDis = node
      .assetDistributionAtHeight(assetId, distributionHeight, 100)
      .items

    val issuerAssetDis = assetDis.view.filterKeys(_ == issuer.toAddress).values

    assetDis should be `equals` node.assetDistribution(assetId)

    issuerAssetDis.size shouldBe 1
    issuerAssetDis.head shouldBe (issuerBalanceBefore - addresses.length * transferAmount)

    // secondKeyPair also holds a genesis share of this asset but wasn't a recipient here, so it must be
    // excluded alongside the issuer rather than assumed away as "everyone but the issuer".
    val recipientAddresses = addresses.toSet
    val othersAssetDis     = assetDis.view.filterKeys(recipientAddresses.contains)

    assert(othersAssetDis.values.forall(_ == transferAmount))

    val assetDisFull =
      distributionPages(assetId, distributionHeight, 100)
        .flatMap(_.items.toList)
        .filter(e => recipientAddresses.contains(e._1))

    assert(assetDisFull.forall(_._2 == transferAmount))

    assertBadRequestAndMessage(
      node.assetDistributionAtHeight(assetId, node.height, 10),
      "Using 'assetDistributionAtHeight' on current height can lead to inconsistent result",
      400
    )
  }

  test("'Asset distribution' works properly") {
    val receivers = for (i <- 0 until 10) yield TxHelpers.signer(2000 + i)

    val issuerBalanceBefore = node.assetBalance(issuer.toAddress.toString, assetId).balance

    node
      .massTransfer(
        issuer,
        receivers.map(rc => MassTransferTransaction.Transfer(rc.toAddress.toString, 10)).toList,
        minFee + minFee * receivers.length,
        assetId = Some(assetId),
        waitForTx = true
      )

    nodes.waitForHeightArise()

    val distribution = node.assetDistribution(assetId)

    assert(receivers.forall(rc => distribution(rc.toAddress) == 10), "Distribution correct")
    distribution(issuer.toAddress) shouldBe (issuerBalanceBefore - 10 * receivers.length)
  }

  test("Correct last page and entry count") {
    val receivers = for (i <- 0 until 50) yield TxHelpers.signer(3000 + i)

    node
      .massTransfer(
        issuer,
        receivers.map(rc => MassTransferTransaction.Transfer(rc.toAddress.toString, 10)).toList,
        minFee + minFee * receivers.length,
        assetId = Some(assetId),
        waitForTx = true
      )

    nodes.waitForHeightArise()

    val height = node.height

    nodes.waitForHeightArise()

    val pages = distributionPages(assetId, height, 10)

    assert(!pages.last.hasNext)
    assert(pages.last.lastItem.nonEmpty)
  }

  test("Unlimited list") {
    val receivers = for (i <- 0 until 2000) yield TxHelpers.signer(4000 + i)

    val transfers = receivers.map { r => MassTransferTransaction.Transfer(r.toAddress.toString, 10L) }.toList

    transfers.grouped(100).foreach { t =>
      node.massTransfer(issuer, t, minFee + t.length * minFee, assetId = Some(assetId))
    }

    node.waitFor("empty utx")(_.utxSize, (_: Int) == 0, 1 second)
    nodes.waitForHeightArise()

    val list = node.assetDistribution(assetId)
    receivers.foreach(r => list(r.toAddress) shouldBe 10L)
  }

  def distributionPages(asset: String, height: Height, limit: Int): List[AssetDistributionPage] = {
    def _load(acc: List[AssetDistributionPage], maybeAfter: Option[String]): List[AssetDistributionPage] = {
      val page = node.assetDistributionAtHeight(asset, height, limit, maybeAfter)
      if (page.hasNext) _load(page :: acc, page.lastItem.map(_.toString))
      else page :: acc
    }

    _load(Nil, None).reverse
  }
}
