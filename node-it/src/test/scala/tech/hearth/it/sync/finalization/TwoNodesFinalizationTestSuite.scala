package tech.hearth.it.sync.finalization

import com.typesafe.config.Config
import tech.hearth.it.api.*
import tech.hearth.it.api.SyncHttpApi.*
import tech.hearth.it.{BaseFreeSpec, NodeConfigs}
import tech.hearth.state.Height
import tech.hearth.test.NumericExt
import tech.hearth.utils.ScorexLogging
import org.scalatest.OptionValues

import scala.concurrent.duration.DurationInt

class TwoNodesFinalizationTestSuite extends BaseFreeSpec, OptionValues, ScorexLogging {
  import NodeConfigs.*
  // The default generation-period-length (1000000, kept large elsewhere so no other suite's run ever crosses a
  // period boundary the genesis commitment doesn't cover) would make period1 below - the very next period after
  // genesis - unreachable within any practical test timeout. This suite's whole point is to commit for and then
  // reach that next period, so it needs a short one instead.
  // The two highest-balance miner-eligible accounts: too low a generating balance (combined with the network's base
  // target) drives the PoS block delay way up - Miners.head/Miners(3) (node01, node04) are both low-balance and
  // were the actual cause of this suite's block pace being roughly 2.5x the configured min-block-time.
  override protected def nodeConfigs: Seq[Config] =
    Seq(Miners.last, Miners(Miners.size - 2)).map(
      _.overrides("waves.blockchain.custom.functionality.min-block-time = 10s")
        .overrides("waves.blockchain.custom.functionality.generation-period-length = 20")
        .quorum(1)
    )

  private def node1 = dockerNodes().head
  private def node2 = dockerNodes().last

  private lazy val miner1Acc  = node1.keyPair
  private lazy val miner1Addr = node1.address

  private lazy val miner2Addr = node2.address

  "finalization activated and works" in {
    val period1 = node1.currentGenerationPeriod.value.next

    step("Commit to generation")
    val commitTxn1 = node1.signCommitToGenerationRequest(miner1Addr)
    val commitTxn2 = node2.signCommitToGenerationRequest(miner2Addr)

    node2.broadcastRequest(commitTxn1)
    node2.broadcastRequest(commitTxn2)

    // 20 blocks at this environment's actual ~12s/block pace (vs. the 10s configured average-block-delay) is close
    // to 4 minutes, past waitForGenerationPeriod's 3-minute default.
    node1.waitForGenerationPeriod(period1, 6.minutes)

    step("Generators")
    isolated {
      val generators = node1.generators(period1.start)
      generators.size shouldBe 2
      generators.map(ge => ge.address -> ge.transactionId) should contain theSameElementsAs Seq(
        miner1Addr -> commitTxn1.id,
        miner2Addr -> commitTxn2.id
      )
    }

    // The committed set for the genesis period is every genesis-committed node in the fixture (see CLAUDE.md's
    // node-it fixtures notes on the 2/3 endorsement threshold), not just the ones this suite actually starts, so
    // finalization can never reach quorum during it - a block's miner endorses only its immediate parent, has one
    // shot per block, and doesn't retry or accumulate progress across several. period1.start's own block is the
    // first one endorsable under the new, fully-online [miner1, miner2] set; its endorsement can only land in the
    // *next* block, so the finalizedHeight baseline below has to be read after that one arrives, not at
    // period1.start itself, or it still reflects the stuck genesis-period value.
    nodes.waitForHeightArise()

    step("Finalized height checks")
    val deadline               = 2.minutes.fromNow
    var finalizedHeight1       = node1.finalizedHeight
    val waitingFinalizedHeight = finalizedHeight1 + 2
    val startHeight            = node1.height

    var done = false
    while (!done && deadline.hasTimeLeft()) {
      val currHeight = node1.height
      // Compare against how far height has moved since this step started, not a fixed offset from the pre-loop
      // finalizedHeight baseline - by the time this step starts, height is already well past that baseline (quorum
      // is structurally impossible during the genesis period, see the node-it fixtures notes above), so a fixed
      // small offset from it fires immediately regardless of whether finalization itself is progressing.
      if (currHeight > startHeight + 10)
        fail(
          s"Finalization height doesn't rise: height=$currHeight, waiting for finalized height=$waitingFinalizedHeight, last finalized height=$finalizedHeight1"
        )

      // We need at least one transaction, otherwise there won't be a microblock, thus no voting, no finalization
      node1.transfer(miner1Acc, miner2Addr, 1.waves, waitForTx = true)

      val updatedFinalizedHeight = node1.finalizedHeight
      if (updatedFinalizedHeight < finalizedHeight1)
        fail(s"Finalized height $updatedFinalizedHeight became lower than the previous $finalizedHeight1")
      else if (updatedFinalizedHeight != finalizedHeight1)
        log.debug(s"New finalized height: $finalizedHeight1 -> $updatedFinalizedHeight")

      finalizedHeight1 = updatedFinalizedHeight
      done = finalizedHeight1 >= waitingFinalizedHeight
    }
  }
}
