package tech.hearth.it.async

import com.typesafe.config.{Config, ConfigFactory}
import tech.hearth.it.api.AsyncHttpApi.*
import tech.hearth.it.{BaseFreeSpec, LoadTest, NodeConfigs, TransferSending}
import tech.hearth.state.Height

import scala.concurrent.Await.result
import scala.concurrent.duration.*

@LoadTest
class MicroblocksGenerationSuite extends BaseFreeSpec with TransferSending {
  import MicroblocksGenerationSuite.*

  override protected val nodeConfigs: Seq[Config] =
    Seq(ConfigOverrides.withFallback(NodeConfigs.randomMiner))

  private val nodeAddresses = nodeConfigs.map(_.getString("address")).toSet

  s"Generate transactions and wait for one block with $maxTxs txs" in result(
    for {
      uploadedTxs <- processRequests(generateTransfersToRandomAddresses(maxTxs, nodeAddresses))
      _           <- miner.waitForHeight(Height(3))
      block       <- miner.blockAt(Height(2))
    } yield {
      block.transactions.size shouldBe maxTxs
      block.transactions.map(_.id) should contain theSameElementsAs uploadedTxs.map(_.id).toSet
    },
    3.minutes
  )

}

object MicroblocksGenerationSuite {
  private val txsInMicroBlock = 200
  private val maxTxs          = 2000
  private val ConfigOverrides = ConfigFactory.parseString(s"""hearth {
                                                             |    miner {
                                                             |      quorum = 0
                                                             |      minimal-block-generation-offset = 1m
                                                             |      micro-block-interval = 3s
                                                             |      max-transactions-in-key-block = 0
                                                             |      max-transactions-in-micro-block = $txsInMicroBlock
                                                             |    }
                                                             |}""".stripMargin)
}
