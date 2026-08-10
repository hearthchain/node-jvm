package tech.hearth.it.sync.finalization

import com.typesafe.config.Config
import tech.hearth.common.utils.EitherExt2.*
import tech.hearth.it.{BaseFreeSpec, keyPairFromSeed}
import tech.hearth.it.api.*
import tech.hearth.it.api.SyncHttpApi.*
import tech.hearth.state.Height
import tech.hearth.test.NumericExt
import tech.hearth.transaction.TxHelpers
import tech.hearth.utils.ScorexLogging
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.scalatest.OptionValues

import scala.concurrent.duration.DurationInt

class OneNodeFinalizationTestSuite extends BaseFreeSpec, OptionValues, ScorexLogging {
  import tech.hearth.it.NodeConfigs.*
  // The default generation-period-length (1000000, kept large elsewhere so no other suite's run ever crosses a
  // period boundary the genesis commitment doesn't cover) would make period1 below - the very next period after
  // genesis - unreachable within any practical test timeout. This suite's whole point is to commit for and then
  // reach that next period, so it needs a short one instead.
  override val nodeConfigs: Seq[Config] = Seq(
    BiggestMiner.quorum(0).overrides("hearth.blockchain.custom.functionality.generation-period-length = 20")
  )

  private def node            = dockerNodes().last
  private lazy val miner1Acc  = node.keyPair
  private lazy val miner1Addr = node.address

  "finalization activated and works" in {
    // A second committed generator on the same single node can't be signed via /transactions/sign - that only ever
    // signs with a generator this node itself holds keys for (hearth.miner.accounts), which a fresh address never is
    // (see CLAUDE.md's node-it fixtures notes on createAddressServerSide()). Built and signed locally instead, with
    // VRF/BLS keys derived from its own SigningKey the same way TxHelpers.commitToGeneration does everywhere else.
    // Genesis-funded already (it's node02's own account, whose container this suite never starts) so the commit's
    // generating-balance check passes immediately - a fresh address funded via a same-run transfer wouldn't clear it,
    // since generating balance is a minimum over a lookback window that a same-run credit hasn't accumulated yet.
    val miner2Acc  = keyPairFromSeed(Miners(1).getString("account-seed")).explicitGet()
    val miner2Addr = miner2Acc.toAddress.toString
    val miner3Addr = node.createAddressServerSide()

    log.warn(s"M2=$miner2Addr, M3=$miner3Addr")

    step("Commit to generation")
    val period1 = node.currentGenerationPeriod.value.next

    val commitTxn1 = node.signCommitToGenerationRequest(miner1Addr)
    commitTxn1.generationPeriodStart.value shouldBe period1.start.toInt

    val commitTxn2 = node.signedBroadcast(TxHelpers.commitToGeneration(Height(period1.start.toInt), sender = miner2Acc).json())
    commitTxn2.generationPeriodStart.value shouldBe period1.start.toInt

    node.broadcastRequest(commitTxn1)
    // 20 blocks at this environment's actual ~12s/block pace (vs. the 10s configured average-block-delay) is close
    // to 4 minutes, past waitForGenerationPeriod's 3-minute default.
    node.waitForGenerationPeriod(period1, 6.minutes)

    step("Generators")
    isolated {
      val generators = node.generators(period1.start)
      generators.size shouldBe 2
      generators.map(e => e.address -> e.transactionId) should contain theSameElementsAs Seq(
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
    val finalizedHeight1       = node.finalizedHeight
    val waitingFinalizedHeight = finalizedHeight1 + 2

    withClue("Finalized height is unknown: ") {
      try node.finalizedHeightAt(node.height)
      catch {
        case ApiCallException(e: UnexpectedStatusCodeException) => e.statusCode shouldBe StatusCodes.NotFound.intValue
      }

      try node.finalizedHeightAt(node.height + 10)
      catch {
        case ApiCallException(e: UnexpectedStatusCodeException) => e.statusCode shouldBe StatusCodes.NotFound.intValue
      }
    }

    // We need at least one transaction, otherwise there won't be a microblock, thus no voting, no finalization
    // Finalization happened in a microblock
    node.waitForHeight(Height(node.waitForTransaction(node.transfer(miner1Acc, miner3Addr, 1.hearth, waitForTx = true).id).height + 1))
    val fs = node.finalityStatus
    if (fs.height > waitingFinalizedHeight + 2)
      fail(
        s"Finalization height doesn't rise: height=${fs.height}, waiting for finalized height=$waitingFinalizedHeight, last finalized height=$finalizedHeight1"
      )

    if (fs.finalizedHeight < finalizedHeight1)
      fail(s"Finalized height ${fs.finalizedHeight} became lower than the previous $finalizedHeight1")
    else if (fs.finalizedHeight != finalizedHeight1)
      log.debug(s"New finalized height: $finalizedHeight1 -> ${fs.finalizedHeight}")

    step("Survives restart")
    isolated {
      val height = node.height
      docker.restartContainer(node)
      node.waitForHeight(height)
    }

    step("Finalized block header and height checks")
    val finalizedBlock1 = node.finalizedBlockHeader()
    finalizedBlock1.height should be >= finalizedHeight1
    node.finalizedHeightAt(finalizedBlock1.height) should be <= finalizedBlock1.height

    step("Finalization voting in a block header")
    val votingBlockHeader  = node.blockHeaderAt(finalizedBlock1.height + 1)
    val finalizationVoting = votingBlockHeader.finalizationVoting.value

    val generators: Seq[(data: GeneratorsResponse.Entry, index: Int)] = node.generators(votingBlockHeader.height).zipWithIndex

    val minerEndorser = generators.find { g => g.data.address == votingBlockHeader.generator }.value

    withClue(s"endorsers=[${finalizationVoting.endorserIndexes.mkString(", ")}], miner=${minerEndorser.index}: ") {
      finalizationVoting.endorserIndexes should not contain minerEndorser.index
    }

    val totalBalance = generators.map { g => BigInt(g.data.balance) }.sum
    val votedBalance = generators.collect {
      case g if finalizationVoting.endorserIndexes.contains(g.index) || g.index == minerEndorser.index => BigInt(g.data.balance)
    }.sum

    withClue(s"totalBalance=$totalBalance, votedBalance=$votedBalance: ") {
      votedBalance * 2 should be >= (totalBalance * 2)
    }

    step("Force rollback")
    val startHeight = waitingFinalizedHeight + 2
    node.waitForHeight(startHeight)

    val currentFinalizedHeight = node.finalizedHeight
    currentFinalizedHeight should be >= finalizedHeight1
    node.rollback(currentFinalizedHeight - 1, returnToUTX = false)
    node.waitFor("finalizedHeight decreased")(_.finalizedHeight, _ < currentFinalizedHeight, 1.second)
  }
}
