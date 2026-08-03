package com.wavesplatform.it.sync.lightnode

import com.typesafe.config.Config
import com.wavesplatform.it.NodeConfigs.Default
import com.wavesplatform.it.NodeConfigs.overrides
import com.wavesplatform.it.api.SyncHttpApi.*
import com.wavesplatform.it.{BaseFunSuite, TransferSending}
import com.wavesplatform.state.Height
import com.wavesplatform.test.NumericExt

class LightNodeMiningSuite extends BaseFunSuite with TransferSending {
  override def nodeConfigs: Seq[Config] = {
    val interval = "waves.blockchain.custom.functionality.light-node-block-fields-absence-interval = 2"
    // buildNonConflicting's withDefault/withSpecial always assigns the lowest-index NonConflictingNodes entry (node01)
    // as the default (full) node and a later one (node04) as the special (light) one - but template.conf's genesis
    // balances only grow with node index, so that pairing hands the light node a genesis balance 2.5x the full
    // node's, and it then wins the early blocks this test asserts belong to the full node regardless of light-mode
    // eligibility. Picking node07 (still in NonConflictingNodes = {1,4,6,7}) as the full node instead keeps it far
    // enough ahead in balance to reliably win them.
    Seq(
      Default(6).overrides(interval),
      Default(0).overrides(interval).overrides("waves.enable-light-mode = true")
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
    fullNode.transfer(fullNode.keyPair, lightNodeAddress, fullNode.balanceDetails(fullNodeAddress).available - 1.waves)
    lightNode.blockSeq(Height(2), Height(5)).foreach(_.generator shouldBe fullNodeAddress)

    lightNode.waitForHeight(Height(6))
    lightNode.blockAt(Height(6)).generator shouldBe lightNodeAddress
  }
}
