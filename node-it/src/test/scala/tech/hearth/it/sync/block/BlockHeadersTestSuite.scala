package tech.hearth.it.sync.block

import com.typesafe.config.Config
import tech.hearth.it.api.*
import tech.hearth.it.api.SyncHttpApi.*
import tech.hearth.it.transactions.NodesFromDocker
import tech.hearth.it.{Node, NodeConfigs, TransferSending}
import tech.hearth.state.Height
import org.scalactic.source.Position
import org.scalatest.*

import scala.concurrent.Await
import scala.concurrent.duration.*

class BlockHeadersTestSuite
    extends funsuite.AnyFunSuite
    with CancelAfterFailure
    with TransferSending
    with NodesFromDocker
    with matchers.should.Matchers {

  private val activationHeight = Height(4)
  private val initialReward    = 600000000

  override protected def nodeConfigs: Seq[Config] =
    NodeConfigs.newBuilder
      .overrideBase(
        _.raw(
          s"""waves {
             |  blockchain.custom.rewards {
             |    initial = $initialReward
             |  }
             |  miner.quorum = 1
             |}""".stripMargin
        )
      )
      .withDefault(1)
      .withSpecial(_.nonMiner)
      .buildNonConflicting()

  private def notMiner: Node = nodes.last

  private val nodeAddresses = nodeConfigs.map(_.getString("address")).toSet

  def assertBlockInfo(block: Block, blockHeader: BlockHeader)(implicit pos: Position): Unit = {
    blockHeader.generator shouldBe block.generator
    blockHeader.timestamp shouldBe block.timestamp
    blockHeader.signature shouldBe block.signature
    blockHeader.desiredReward shouldBe block.desiredReward
    blockHeader.reward shouldBe block.reward
    blockHeader.transactionCount shouldBe block.transactions.size
  }

  test("blockAt content should be equal to blockHeaderAt, except transactions info") {
    val baseHeight = nodes.map(_.height).max
    Await.result(processRequests(generateTransfersToRandomAddresses(10, nodeAddresses)), 2.minutes)
    nodes.waitForHeight(baseHeight + 4)
    notMiner.blockHeaderAt(activationHeight).reward shouldBe Some(initialReward)
    val block       = notMiner.blockAt(baseHeight + 1)
    val blockHeader = notMiner.blockHeaderAt(baseHeight + 1)

    assertBlockInfo(block, blockHeader)
  }

  test("lastBlock content should be equal to lastBlockHeader, except transactions info") {
    val baseHeight = nodes.map(_.height).max
    Await.result(processRequests(generateTransfersToRandomAddresses(30, nodeAddresses)), 2.minutes)
    nodes.waitForHeight(baseHeight + 1)
    val blocks        = nodes.map(_.lastBlock())
    val blocksHeaders = nodes.map(_.lastBlockHeader())
    blocks.zip(blocksHeaders).foreach { case (k, v) => assertBlockInfo(k, v) }
  }

  test("blockSeq content should be equal to blockHeaderSeq, except transactions info") {
    val baseHeight = nodes.map(_.height).max
    Await.result(processRequests(generateTransfersToRandomAddresses(30, nodeAddresses)), 2.minutes)
    nodes.waitForSameBlockHeadersAt(baseHeight + 3)
    val blocks       = nodes.head.blockSeq(baseHeight + 1, baseHeight + 3)
    val blockHeaders = nodes.head.blockHeadersSeq(baseHeight + 1, baseHeight + 3)

    blocks.zip(blockHeaders).foreach { case (block, header) =>
      header.generator shouldBe block.generator
      header.timestamp shouldBe block.timestamp
      header.signature shouldBe block.signature
      header.desiredReward shouldBe block.desiredReward
      header.reward shouldBe block.reward
      header.transactionCount shouldBe block.transactions.size
    }
  }

  test("blocks/address produces correct result") {
    val miner  = nodes.head
    val height = miner.height

    val minerBlocks    = miner.blockSeqByAddress(miner.address, Height(1), height)
    val nonMinerBlocks = notMiner.blockSeqByAddress(notMiner.address, Height(1), height)

    minerBlocks.size shouldEqual (height - 1)
    nonMinerBlocks shouldBe empty
  }
}
