package tech.hearth.it.sync

import com.typesafe.config.Config
import tech.hearth.it.*
import tech.hearth.it.api.SyncHttpApi.*
import tech.hearth.state.Height
import org.scalatest.prop.TableDrivenPropertyChecks

import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.util.Random

@LoadTest
class RollbackSuite extends BaseFunSuite with TransferSending with TableDrivenPropertyChecks {
  import NodeConfigs.*
  // Zeroed so that the block reward - which scales with however many blocks the same 190 transactions happen to spread
  // across on each mining attempt - doesn't dominate the state comparison below; without this, "Apply the same transfer
  // transactions twice with return to UTX" fails whenever the two attempts don't mine into the same number of blocks.
  override def nodeConfigs: Seq[Config] = Seq(
    BiggestMiner.quorum(0),
    NotMiner
  ).map(_.overrides("hearth.blockchain.custom.rewards.initial = 0"))

  private lazy val nodeAddresses = nodeConfigs.map(_.getString("address")).toSet

  test("Apply the same transfer transactions twice with return to UTX") {

    val startHeight = sender.height

    val transactionIds = Await.result(processRequests(generateTransfersToRandomAddresses(190, nodeAddresses)), 2.minutes).map(_.id)
    nodes.waitFor("empty utx")(_.utxSize)(_.forall(_ == 0))
    val maxHeightFirstTry = Height(sender.transactionStatus(transactionIds).flatMap(_.height).max)
    sender.waitForHeight(maxHeightFirstTry + 2) // so that NG fees won't affect miner's balances

    val stateAfterFirstTry = nodes.head.debugStateAt(maxHeightFirstTry + 1)

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
