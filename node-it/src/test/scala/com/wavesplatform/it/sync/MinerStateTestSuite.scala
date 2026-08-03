package com.wavesplatform.it.sync

import com.typesafe.config.{Config, ConfigFactory}
import com.wavesplatform.it.BaseFunSuite
import com.wavesplatform.it.api.State
import com.wavesplatform.it.api.SyncHttpApi.*
import com.wavesplatform.test.*

import scala.concurrent.duration.*

class MinerStateTestSuite extends BaseFunSuite {
  import MinerStateTestSuite.*

  override protected def nodeConfigs: Seq[Config] = Configs

  private val transferAmount = 1000.waves

  private def last = nodes.last

  // generation-balance-depth-from-50-to-1000-after-height doesn't correspond to any settings field any more -
  // GeneratingBalanceProvider.SecondDepth hardcodes the generating-balance lookback window to 1000 blocks
  // unconditionally, and the shorter pre-height-100 window this test relies on to observe the transition in ~51
  // blocks is gone for good. Waiting out a real 1000-block window here would take well over an hour, so this test's
  // premise can no longer be exercised within a practical integration-test run.
  ignore("node w/o balance can forge blocks after effective balance increase") {
    val newKeyPair = last.createKeyPair()
    val newAddress = newKeyPair.toAddress.toString

    val (balance1, eff1)        = miner.accountBalances(miner.address)
    val minerFullBalanceDetails = miner.balanceDetails(miner.address)
    // miner is a committed generator, so its regular balance includes the generation deposit while available/
    // effective do not (see CLAUDE.md "Balance snapshots"); accountBalances reports regular, not available.
    assert(balance1 == minerFullBalanceDetails.regular)
    assert(eff1 == minerFullBalanceDetails.effective)

    val (balance2, eff2)     = last.accountBalances(newAddress)
    val newAccBalanceDetails = last.balanceDetails(newAddress)
    assert(balance2 == newAccBalanceDetails.available)
    assert(eff2 == newAccBalanceDetails.effective)

    val minerInfoBefore = last.debugMinerInfo()
    all(minerInfoBefore) shouldNot matchPattern { case State(`newAddress`, _, ts) if ts > 0 => }

    miner.waitForPeers(1)
    val txId = miner.transfer(miner.keyPair, newAddress, transferAmount, minFee).id
    nodes.waitForHeightAriseAndTxPresent(txId)

    val heightAfterTransfer = miner.height

    last.assertBalances(newAddress, balance2 + transferAmount, eff2 + transferAmount)

    // 51 blocks at this environment's actual ~6-7s/block pace (vs. the 5s configured average-block-delay) can brush
    // up against 6 minutes; if you know how to reduce waiting time, please ping @monroid
    last.waitForHeight(heightAfterTransfer + 51, 10.minutes)

    assert(last.balanceDetails(newAddress).generating == balance2 + transferAmount)

    val minerInfoAfter = last.debugMinerInfo()
    atMost(1, minerInfoAfter) should matchPattern { case State(`newAddress`, _, ts) if ts > 0 => }

    last.waitForPeers(1)
    val leaseBack = last.lease(newKeyPair, miner.address, (transferAmount - minFee), minFee).id
    nodes.waitForHeightAriseAndTxPresent(leaseBack)

    assert(last.balanceDetails(newAddress).generating == balance2)

    all(miner.debugMinerInfo()) shouldNot matchPattern { case State(`newAddress`, _, ts) if ts > 0 => }

    all(last.debugMinerInfo()) shouldNot matchPattern { case State(`newAddress`, _, ts) if ts > 0 => }

  }
}

object MinerStateTestSuite {
  import com.wavesplatform.it.NodeConfigs.*
  private val minerConfig = ConfigFactory.parseString(s"""
                                                         |waves {
                                                         |  synchronization.synchronization-timeout = 10s
                                                         |  blockchain.custom.functionality {
                                                         |    pre-activated-features.1 = 0
                                                         |  }
                                                         |  blockchain.custom.genesis {
                                                         |     average-block-delay = 5s
                                                         |  }
                                                         |  miner.quorum = 1
                                                         |}""".stripMargin)

  // The two highest-balance miner-eligible accounts: BaseSuite.miner is nodes.head, and a low-balance miner's PoS
  // delay for its very first block can exceed waitForHeightAriseAndTxPresent's tx-await timeout.
  val Configs: Seq[Config] = Seq(
    minerConfig.withFallback(Miners.last),
    minerConfig.withFallback(Miners(Miners.size - 2))
  )

}
