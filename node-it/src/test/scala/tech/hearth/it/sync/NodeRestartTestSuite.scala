package tech.hearth.it.sync

import com.typesafe.config.{Config, ConfigFactory}
import tech.hearth.account.Address
import tech.hearth.common.state.ByteStr
import tech.hearth.common.utils.EitherExt2.*
import tech.hearth.it.api.SyncHttpApi.*
import tech.hearth.it.api.TransactionInfo
import tech.hearth.it.BaseFreeSpec
import tech.hearth.state.Height
import tech.hearth.test.*
import tech.hearth.transaction.Asset.Waves
import tech.hearth.transaction.TxHelpers

class NodeRestartTestSuite extends BaseFreeSpec {
  import NodeRestartTestSuite.*

  override protected def nodeConfigs: Seq[Config] = Configs

  private def nodeA = nodes.head
  private def nodeB = nodes(1)

  "node should grow up to 5 blocks together and sync" in {
    nodes.waitForSameBlockHeadersAt(Height(5))
  }

  "create many addresses and check them after node restart" in {
    1 to 10 map (_ => nodeA.createKeyPair())
    val setOfAddresses      = nodeA.getAddresses
    val nodeAWithOtherPorts = docker.restartContainer(dockerNodes().head)
    val maxHeight           = nodes.map(_.height).max
    nodeAWithOtherPorts.getAddresses should contain theSameElementsAs setOfAddresses
    nodes.waitForSameBlockHeadersAt(maxHeight + 2)
  }

  "after restarting all the nodes, the duplicate transaction cannot be put into the blockchain" in {
    val txJson = TxHelpers
      .transfer(
        from = nodeB.keyPair,
        to = Address.fromString(nodeA.address).explicitGet(),
        amount = 1.waves,
        asset = Waves,
        fee = minFee,
        feeAsset = Waves,
        attachment = ByteStr.empty,
        timestamp = System.currentTimeMillis()
      )
      .json()

    val tx = nodeB.signedBroadcast(txJson, waitForTx = true)
    nodeA.waitForTransaction(tx.id)

    val txHeight = nodeA.transactionInfo[TransactionInfo](tx.id).height

    nodes.waitForHeightArise()

    docker.restartContainer(dockerNodes().head)
    docker.restartContainer(dockerNodes()(1))

    nodes.waitForHeight(Height(txHeight + 2))

    assertBadRequestAndMessage(
      nodeB.signedBroadcast(txJson, waitForTx = true),
      s"State check failed. Reason: Transaction ${tx.id} is already in the state on a height of $txHeight"
    )
  }

}

object NodeRestartTestSuite {
  import tech.hearth.it.NodeConfigs.*
  private val FirstNode = ConfigFactory.parseString(s"""
                                                       |waves {
                                                       |  synchronization.synchronization-timeout = 10s
                                                       |  blockchain.custom.functionality {
                                                       |    pre-activated-features.1 = 0
                                                       |  }
                                                       |  miner.quorum = 0
                                                       |  wallet {
                                                       |     file = "/tmp/wallet.dat"
                                                       |     password = "bla"
                                                       |  }
                                                       |
                                                       |}""".stripMargin)

  private val SecondNode = ConfigFactory.parseString(s"""
                                                        |waves {
                                                        |  synchronization.synchronization-timeout = 10s
                                                        |  blockchain.custom.functionality {
                                                        |    pre-activated-features.1 = 0
                                                        |  }
                                                        |  miner.enable = no
                                                        |  wallet {
                                                        |     file = "/tmp/wallet.dat"
                                                        |     password = "bla"
                                                        |  }
                                                        |}""".stripMargin)

  val Configs: Seq[Config] = Seq(
    FirstNode.withFallback(Default.head),
    SecondNode.withFallback(Default(1))
  )

}
