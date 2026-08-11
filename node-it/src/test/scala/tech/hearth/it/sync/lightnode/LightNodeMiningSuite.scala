package tech.hearth.it.sync.lightnode

import com.typesafe.config.Config
import tech.hearth.it.NodeConfigs.Default
import tech.hearth.it.NodeConfigs.overrides
import tech.hearth.it.api.SyncHttpApi.*
import tech.hearth.it.{BaseFunSuite, TransferSending}
import tech.hearth.state.Height
import tech.hearth.test.NumericExt

class LightNodeMiningSuite extends BaseFunSuite with TransferSending {
  override def nodeConfigs: Seq[Config] = {
    val interval = "hearth.blockchain.custom.functionality.light-node-block-fields-absence-interval = 2"
    // buildNonConflicting's withDefault/withSpecial always assigns the lowest-index NonConflictingNodes entry (node01)
    // as the default (full) node and a later one (node04) as the special (light) one - but template.conf's genesis
    // balances only grow with node index, so that pairing hands the light node a genesis balance 2.5x the full
    // node's, and it then wins the early blocks this test asserts belong to the full node regardless of light-mode
    // eligibility. Picking node07 (still in NonConflictingNodes = {1,4,6,7}) as the full node instead keeps it far
    // enough ahead in balance to reliably win them.
    Seq(
      Default(6).overrides(interval),
      Default(0).overrides(interval).overrides("hearth.enable-light-mode = true")
    )
  }

  test("node can mine in light mode after light-node-block-fields-absence-interval") {
    val lightNode        = nodes.find(_.settings.enableLightMode).get
    val fullNode         = nodes.find(!_.settings.enableLightMode).get
    val lightNodeAddress = lightNode.keyPair.toAddress.toString
    val fullNodeAddress  = fullNode.keyPair.toAddress.toString

    nodes.waitForHeight(Height(5))
    // available (unlike the regular balance) excludes the generation deposit fullNode reserves as a committed
    // generator.
    fullNode.transfer(fullNode.keyPair, lightNodeAddress, fullNode.balanceDetails(fullNodeAddress).available - 1.hearth)
    lightNode.blockSeq(Height(2), Height(5)).foreach(_.generator shouldBe fullNodeAddress)

    lightNode.waitForHeight(Height(6))
    lightNode.blockAt(Height(6)).generator shouldBe lightNodeAddress
  }
}
