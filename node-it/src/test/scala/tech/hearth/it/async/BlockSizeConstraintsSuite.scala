package tech.hearth.it.async

import com.typesafe.config.{Config, ConfigFactory}
import tech.hearth.it.*
import tech.hearth.it.api.AsyncHttpApi.*
import tech.hearth.state.Height

import scala.concurrent.Await.result
import scala.concurrent.Future
import scala.concurrent.duration.*

@LoadTest
class BlockSizeConstraintsSuite extends BaseFreeSpec with TransferSending {
  import BlockSizeConstraintsSuite.*

  override protected val nodeConfigs: Seq[Config] =
    Seq(ConfigOverrides.withFallback(NodeConfigs.randomMiner))

  private lazy val nodeAddresses = nodeConfigs.map(_.getString("address")).toSet

  private lazy val transfers = generateTransfersToRandomAddresses(maxTxsGroup, nodeAddresses)
  // MiningConstraints.MaxTxsSizeInBytes (1MB) is an unconditional constant, not gated behind a feature any more,
  // so the limit applies from genesis; there is no "before activation" state left to compare against.
  s"Block is limited by size" in result(
    for {
      _                <- Future.sequence((0 to maxGroups).map(_ => processRequests(transfers, includeAttachment = true)))
      _                <- miner.waitForHeight(Height(3))
      blockHeaderAfter <- miner.blockHeaderAt(Height(2))
    } yield {
      val maxSizeInBytes        = (1.1d * 1024 * 1024).toInt // including headers
      val blockSizeInBytesAfter = blockHeaderAfter.blocksize
      blockSizeInBytesAfter should be <= maxSizeInBytes
    },
    10.minutes
  )

}

object BlockSizeConstraintsSuite {
  private val maxTxsGroup     = 500 // More, than 1mb of block
  private val maxGroups       = 9
  private val txsInMicroBlock = 500
  private val ConfigOverrides = ConfigFactory.parseString(s"""pekko.http.server {
                                                             |  parsing.max-content-length = 3737439
                                                             |  request-timeout = 60s
                                                             |}
                                                             |
                                                             |hearth {
                                                             |  network.enable-peers-exchange = no
                                                             |
                                                             |  miner {
                                                             |    quorum = 0
                                                             |    minimal-block-generation-offset = 60000ms
                                                             |    micro-block-interval = 1s
                                                             |    max-transactions-in-key-block = 0
                                                             |    max-transactions-in-micro-block = $txsInMicroBlock
                                                             |  }
                                                             |
                                                             |  blockchain.custom {
                                                             |    store-transactions-in-state = false
                                                             |  }
                                                             |}""".stripMargin)

}
