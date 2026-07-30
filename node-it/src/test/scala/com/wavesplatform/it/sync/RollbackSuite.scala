package com.wavesplatform.it.sync

import com.typesafe.config.Config
import com.wavesplatform.features.BlockchainFeatures
import com.wavesplatform.it.*
import com.wavesplatform.it.api.SyncHttpApi.*
import com.wavesplatform.state.Height
import org.scalatest.prop.TableDrivenPropertyChecks

import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.util.Random

@LoadTest
class RollbackSuite extends BaseFunSuite with TransferSending with TableDrivenPropertyChecks {
  import NodeConfigs.*
  override def nodeConfigs: Seq[Config] = Seq(
    BiggestMiner.quorum(0),
    NotMiner
  )

  private lazy val nodeAddresses = nodeConfigs.map(_.getString("address")).toSet

  test("Apply the same transfer transactions twice with return to UTX") {

    val startHeight = sender.height

    val transactionIds = Await.result(processRequests(generateTransfersToRandomAddresses(190, nodeAddresses)), 2.minutes).map(_.id)
    nodes.waitFor("empty utx")(_.utxSize)(_.forall(_ == 0))
    nodes.waitForHeightArise()

    val stateHeight        = sender.height
    val stateAfterFirstTry = nodes.head.debugStateAt(stateHeight)

    nodes.rollback(startHeight)
    nodes.waitFor("empty utx")(_.utxSize)(_.forall(_ == 0))
    val maxHeight = Height(sender.transactionStatus(transactionIds).flatMap(_.height).max)
    sender.waitForHeight(maxHeight + 2) // so that NG fees won't affect miner's balances

    val stateAfterSecondTry = nodes.head.debugStateAt(maxHeight + 1)
    stateAfterSecondTry.toSet shouldBe stateAfterFirstTry.toSet
  }

  test("Just rollback transactions") {
    nodes.waitForHeightArise() // so that NG fees won't affect miner's balances
    val startHeight      = sender.height
    val stateBeforeApply = sender.debugStateAt(startHeight)

    nodes.waitForHeightArise()

    val requests = generateTransfersToRandomAddresses(190, nodeAddresses)
    Await.result(processRequests(requests), 2.minutes)

    nodes.waitFor("empty utx")(_.utxSize)(_.forall(_ == 0))

    nodes.waitForHeightArise()

    sender.debugStateAt(sender.height).size shouldBe stateBeforeApply.size + 190

    nodes.rollback(startHeight, returnToUTX = false)

    nodes.waitFor("empty utx")(_.utxSize)(_.forall(_ == 0))

    nodes.waitForHeightArise()

    val stateAfterApply = sender.debugStateAt(sender.height)

    stateAfterApply should contain theSameElementsAs stateBeforeApply

  }

  forAll(
    Table(
      ("num", "name"),
      (1, "1 of N"),
      (nodeConfigs.size, "N of N")
    )
  ) { (num, name) =>
    test(s"generate more blocks and resynchronise after rollback $name") {
      val baseHeight = nodes.map(_.height).max + 5
      nodes.waitForHeight(baseHeight)
      val rollbackNodes = Random.shuffle(nodes).take(num)
      rollbackNodes.foreach(_.rollback(baseHeight - 1))
      nodes.waitForHeightArise()
      nodes.waitForSameBlockHeadersAt(baseHeight)
    }
  }
}
