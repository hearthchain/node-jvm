package tech.hearth.it.sync.network

import com.typesafe.config.{Config, ConfigFactory}
import tech.hearth.it.api.SyncHttpApi.*
import tech.hearth.it.sync.minFee
import tech.hearth.it.{BaseFreeSpec, Node}
import tech.hearth.state.Height
import tech.hearth.test.*
import tech.hearth.utils.ScorexLogging

import scala.concurrent.duration.*

class NetworkSeparationTestSuite extends BaseFreeSpec, ScorexLogging {
  import NetworkSeparationTestSuite.*

  override protected def nodeConfigs: Seq[Config] = Configs

  private def nodeA: Node = nodes.head
  private def nodeB: Node = nodes.last

  "node should grow up to 10 blocks together and sync" in {
    nodes.waitForSameBlockHeadersAt(Height(10))
  }

  // Doing all work in one step, because nodes will not be available for requests and ReportingTestName fails here
  "then we disconnect nodes from the network, wait some time and connect them again" in {
    val lastMaxHeight = nodes.map(_.height).max
    dockerNodes().foreach(docker.disconnectFromNetwork)
    Thread.sleep(80.seconds.toMillis) // >= 10 blocks, because a new block appears every 6 seconds
    docker.connectToNetwork(dockerNodes())
    nodes.map(_.height).max shouldBe >=(lastMaxHeight + 5)
  }

  "nodes should sync" in {
    val maxHeight = nodes.map(_.height).max
    log.debug(s"Max height is $maxHeight")
    nodes.waitForSameBlockHeadersAt(maxHeight + 5)
  }

  "after fork node should apply correct subchain" in {
    val txId = nodeA.transfer(nodeA.keyPair, nodeB.address, 1.waves, minFee).id
    nodes.waitForHeightAriseAndTxPresent(txId)

    docker.disconnectFromNetwork(dockerNodes().head)

    val divergingTxId = nodeB.transfer(nodeB.keyPair, nodeA.address, 1.waves, minFee).id
    nodeB.waitForTransaction(divergingTxId, 2.minute)
    val heightAfter = nodeB.height

    Thread.sleep(60.seconds.toMillis)
    docker.disconnectFromNetwork(dockerNodes().last)
    docker.connectToNetwork(Seq(dockerNodes().head))

    nodeA.waitForHeight(heightAfter)
    val block = nodeA.blockAt(heightAfter)

    docker.connectToNetwork(Seq(dockerNodes().last))
    Thread.sleep(80.seconds.toMillis)

    assert(nodeA.blockAt(heightAfter) == block)
    val height = nodeA.height
    assert(nodeA.blockAt(height) != nodeB.blockAt(height))
  }

}

object NetworkSeparationTestSuite {
  import tech.hearth.it.NodeConfigs.*
  // Both nodes used to differ on a second, now-removed feature (id 6); only feature 1 survives, and this
  // suite's fork/reconnect scenario never depended on that other feature, so both configs collapse to the same one.
  private val config = ConfigFactory.parseString(s"""
                                                    |waves {
                                                    |  synchronization.synchronization-timeout = 10s
                                                    |  blockchain.custom.functionality {
                                                    |    pre-activated-features = {
                                                    |     1 = 0
                                                    |     }
                                                    |  }
                                                    |  miner.quorum = 0
                                                    |}""".stripMargin)

  val Configs: Seq[Config] = Seq(
    config.withFallback(Miners.head),
    config.withFallback(Miners.last)
  )
}
