package tech.hearth.it.sync.lightnode

import com.typesafe.config.Config
import tech.hearth.it.NodeConfigs.Default
import tech.hearth.it.NodeConfigs.overrides
import tech.hearth.it.api.SyncHttpApi.*
import tech.hearth.it.{BaseFunSuite, TransferSending}
import tech.hearth.state.Height
import tech.hearth.test.NumericExt

import scala.concurrent.duration.*

class LightNodeMiningSuite extends BaseFunSuite with TransferSending {
  override def nodeConfigs: Seq[Config] = Seq(
    Default(6),
    Default(0).overrides("hearth.enable-light-mode = true")
  )

  // Light mode has no mining gate (supportsLightNodeBlockFields is always on), so which node mines any given early block
  // is a balance-weighted race plus startup timing; only assert that a light node's block eventually lands and the full
  // node accepts it.
  test("node can mine in light mode") {
    val lightNode        = nodes.find(_.settings.enableLightMode).get
    val fullNode         = nodes.find(!_.settings.enableLightMode).get
    val lightNodeAddress = lightNode.keyPair.toAddress.toString
    val fullNodeAddress  = fullNode.keyPair.toAddress.toString

    nodes.waitForHeight(Height(2))
    // Draining the full node's generating balance (available excludes its generation deposit) hands the light node
    // nearly every following block, so the wait below doesn't hinge on a 1-in-7 race.
    fullNode.transfer(fullNode.keyPair, lightNodeAddress, fullNode.balanceDetails(fullNodeAddress).available - 1.hearth)

    val lightBlock = lightNode
      .waitFor("block mined by the light node")(
        n => n.blockSeq(Height(2), n.height).find(_.generator == lightNodeAddress),
        _.isDefined,
        1.second
      )
      .get

    fullNode.waitForHeight(Height(lightBlock.height))
    fullNode.blockAt(Height(lightBlock.height)).id shouldBe lightBlock.id
  }
}
