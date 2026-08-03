package tech.hearth.it.async

import com.typesafe.config.{Config, ConfigFactory}
import tech.hearth.it.BaseFreeSpec
import tech.hearth.it.NodeConfigs.Default
import tech.hearth.it.api.AsyncHttpApi.*
import tech.hearth.test.*
import tech.hearth.state.Height

import scala.concurrent.Future.traverse
import scala.concurrent.duration.*
import scala.concurrent.{Await, Future}
import scala.util.Random

class MicroblocksFeeTestSuite extends BaseFreeSpec {

  private def firstAddress = nodes(1).address

  private def txRequestsGen(n: Int, fee: Long): Future[Unit] = {
    val parallelRequests = 10

    def requests(n: Int): Future[Unit] =
      Future
        .sequence {
          // Not mining node sends transfer transactions to another not mining node
          // Mining nodes collect fee
          (1 to n).map { _ =>
            notMiner.transfer(notMiner.keyPair, firstAddress, (1 + Random.nextInt(10)).waves, fee)
          }
        }
        .map(_ => ())

    val steps = (1 to n)
      .sliding(parallelRequests, parallelRequests)
      .map(_.size)

    steps.foldLeft(Future.successful(())) { (r, numRequests) =>
      r.flatMap(_ => requests(numRequests))
    }
  }

  // NG (microblocks, and with it the 40%/60% own-block/carry fee split - see CLAUDE.md's "Block fees" notes) is
  // unconditional now, not gated by feature 2 any more; pre-activating it crashes the node outright once the chain
  // reaches that height ("UNIMPLEMENTED FEATURE 2 has been ACTIVATED ON BLOCKCHAIN", see CLAUDE.md's node-it
  // fixtures notes on stale feature ids). There is no "before activation" state to test any more, so this only
  // checks the always-on split holds across two consecutive blocks. This suite's sole miner (see nodeConfigs below)
  // generates every block, so blockBefore/blockAt/blockAfter all share one generator.
  "fee distribution" in {
    val f = for {
      _ <- traverse(nodes)(_.height).map(_.max)

      _ <- traverse(nodes)(_.waitForHeight(checkHeight - 1))
      _ <- txRequestsGen(200, 2.waves)
      _ <- traverse(nodes)(_.waitForHeight(checkHeight + 1))

      balancesBefore <- notMiner.debugStateAt(checkHeight - 1)
      blockBefore    <- notMiner.blockHeaderAt(checkHeight - 1)

      balancesAt <- notMiner.debugStateAt(checkHeight)
      blockAt    <- notMiner.blockHeaderAt(checkHeight)

      balancesAfter <- notMiner.debugStateAt(checkHeight + 1)
      blockAfter    <- notMiner.blockHeaderAt(checkHeight + 1)
    } yield {
      balancesAt(blockAt.generator) shouldBe {
        nodes.head.settings.blockchainSettings.rewardsSettings.initial +
          balancesBefore(blockAt.generator) + blockBefore.totalFee * 6 / 10 + blockAt.totalFee * 4 / 10
      }

      balancesAfter(blockAfter.generator) shouldBe {
        nodes.head.settings.blockchainSettings.rewardsSettings.initial +
          balancesAt(blockAfter.generator) + blockAt.totalFee * 6 / 10 + blockAfter.totalFee * 4 / 10
      }
    }

    Await.result(f, 5.minute)
  }

  private val checkHeight = Height(10)
  private val minerConfig = ConfigFactory.parseString(
    """waves {
      |  miner.quorum = 3
      |}
      """.stripMargin
  )

  private val notMinerConfig = ConfigFactory.parseString(
    """waves {
      |  miner.enable = no
      |}
      """.stripMargin
  )

  // Default(0) (node01) is the lowest-balance miner-eligible account in the whole fixture (see CLAUDE.md's node-it
  // fixtures notes); as the suite's sole miner its PoS delay averaged ~30s/block, timing this test out before it
  // ever reached microblockActivationHeight. Default(8) (node09, the highest-balance one) reaches it comfortably.
  override protected val nodeConfigs: Seq[Config] = Seq(
    minerConfig.withFallback(Default(8)),
    notMinerConfig.withFallback(Default(1)),
    notMinerConfig.withFallback(Default(2)),
    notMinerConfig.withFallback(Default(3))
  )
}
