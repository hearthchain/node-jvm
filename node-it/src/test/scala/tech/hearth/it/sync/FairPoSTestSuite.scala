package tech.hearth.it.sync

import com.typesafe.config.{Config, ConfigFactory}
import tech.hearth.it.BaseFunSuite
import tech.hearth.it.api.SyncHttpApi.*
import tech.hearth.state.Height

import scala.concurrent.duration.*

class FairPoSTestSuite extends BaseFunSuite {
  import FairPoSTestSuite.*

  override protected def nodeConfigs: Seq[Config] = Configs

  test("blockchain grows with FairPoS activated") {
    nodes.waitForSameBlockHeadersAt(height = Height(10), conditionAwaitTime = 11.minutes)

    val txId = nodes.head.transfer(nodes.head.keyPair, nodes.last.address, transferAmount, minFee).id
    nodes.last.waitForTransaction(txId)

    val heightAfterTransfer = nodes.head.height

    nodes.waitForSameBlockHeadersAt(heightAfterTransfer + 10, conditionAwaitTime = 11.minutes)
  }
}

object FairPoSTestSuite {
  import tech.hearth.it.NodeConfigs.*

  // FairPoS, microblocks and VRF are all unconditional now (no feature gate left to pre-activate).
  private val config =
    ConfigFactory.parseString(
      s"""
         |hearth {
         |   blockchain.custom {
         |      functionality {
         |        generation-balance-depth-from-50-to-1000-after-height = 1000
         |      }
         |   }
         |   miner.quorum = 1
         |}""".stripMargin
    )

  val Configs: Seq[Config] = Default.map(config.withFallback(_)).take(3)
}
