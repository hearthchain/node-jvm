package tech.hearth.it.sync.finalization

import com.typesafe.config.Config
import tech.hearth.it.BaseFreeSpec
import tech.hearth.it.api.*
import tech.hearth.it.api.SyncHttpApi.*
import tech.hearth.state.Height
import tech.hearth.test.NumericExt
import tech.hearth.utils.ScorexLogging
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.scalatest.OptionValues

import scala.concurrent.duration.DurationInt

class OneNodeFinalizationTestSuite extends BaseFreeSpec, OptionValues, ScorexLogging {
  import tech.hearth.it.NodeConfigs.*

  // node08's own raw fixture config - genesis-funded already, but its container is never started in this suite.
  // Deliberately NOT a low-balance account: EndorsementFilter.simulate (state/EndorsementFilter.scala:57) computes
  // `reached` from the miner's own balance alone before ever looking at endorsers, so if BiggestMiner's balance
  // (node09, 100T) already cleared 2/3 of the two-generator total by itself, the endorser's vote would never be
  // selected (chosenValid stays empty) even though it was recorded - and FinalizationVoting.withValid then fails
  // outright on an empty signature list (crypto/bls/BlsUtils.scala:24), so no voting ever gets embedded. node08
  // (80T) keeps both shares under 66.7% (100/180=55.6%, 80/180=44.4%) with a comfortable margin, so reaching
  // quorum genuinely requires the endorsement.
  private val miner2RawConfig = Miners(7)
  private val miner2Addr      = miner2RawConfig.getString("address")

  private def minerAccountEntry(rawConfig: Config): String = {
    val account = rawConfig.getConfigList("hearth.miner.accounts").get(0)
    s"""{ signing-key = "${account.getString("signing-key")}", vrf-key = "${account.getString("vrf-key")}", bls-key = "${account.getString("bls-key")}" }"""
  }

  // The default generation-period-length (1000000, kept large elsewhere so no other suite's run ever crosses a
  // period boundary the genesis commitment doesn't cover) would make period1 below - the very next period after
  // genesis - unreachable within any practical test timeout. This suite's whole point is to commit for and then
  // reach that next period, so it needs a short one instead.
  //
  // An endorsement can only ever be signed for an address this node process itself holds generatorKeys for -
  // BlockEndorser.vote checks `generatorKeys.contains(cg.address)` before it will sign on that generator's behalf
  // (BlockEndorser.scala:86) - so with only this suite's single container running, an endorsement "from one local
  // account to another" needs both accounts' signing/vrf/bls keys configured in *this* node's hearth.miner.accounts,
  // not just committed on-chain. Adding a second entry (node02's own keys) means whichever of the two ends up
  // mining a given block, this process still holds the other's key and can self-endorse across accounts for it
  // (BlockEndorser.scala:85 only excludes the block's actual miner from endorsing its own block).
  override val nodeConfigs: Seq[Config] = Seq(
    BiggestMiner
      .quorum(0)
      .overrides(
        s"""hearth.blockchain.custom.functionality.generation-period-length = 20
           |hearth.miner.accounts = [
           |  ${minerAccountEntry(BiggestMiner)}
           |  ${minerAccountEntry(miner2RawConfig)}
           |]""".stripMargin
      )
  )

  private def node            = dockerNodes().last
  private lazy val miner1Acc  = node.keyPair
  private lazy val miner1Addr = node.address

  "finalization activated and works" in {
    val miner3Addr = node.createAddressServerSide()

    log.warn(s"M2=$miner2Addr, M3=$miner3Addr")

    step("Commit to generation")
    val period1 = node.currentGenerationPeriod.value.next

    // Both addresses are now configured in this node's own hearth.miner.accounts (see nodeConfigs above), so
    // GeneratorKeys.signingKey resolves either one and /transactions/sign can sign on behalf of both, even though
    // only miner1Addr is this node's wallet/own account.
    val commitTxn1 = node.signCommitToGenerationRequest(miner1Addr)
    commitTxn1.generationPeriodStart.value shouldBe period1.start.toInt

    val commitTxn2 = node.signCommitToGenerationRequest(miner2Addr)
    commitTxn2.generationPeriodStart.value shouldBe period1.start.toInt

    node.broadcastRequest(commitTxn1)
    node.broadcastRequest(commitTxn2)
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
    // first one endorsable under the new, fully-online [miner1, miner2] set.
    nodes.waitForHeightArise()

    // We need at least one transaction, otherwise there won't be a microblock, thus no voting, no finalization.
    // With balanced miner1/miner2 balances (see nodeConfigs above), neither one clears 2/3 of the committed
    // balance alone, so - unlike a lopsided committed set, where the richer account's own balance would already
    // reach quorum on every block with no endorsement at all - finalizedHeight cannot advance past its
    // genesis-period value (1) until this transfer's microblock actually embeds a real cross-account vote. Send
    // it, and wait for that vote to land, before taking the finalizedHeight baseline below - capturing it any
    // earlier would always read the stuck genesis-period value.
    node.waitForHeight(Height(node.waitForTransaction(node.transfer(miner1Acc, miner3Addr, 1.hearth, waitForTx = true).id).height + 1))

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
