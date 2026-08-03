package tech.hearth.it.sync.activation

import com.typesafe.config.Config
import tech.hearth.features.api.NodeFeatureStatus
import tech.hearth.features.{BlockchainFeatureStatus, BlockchainFeatures}
import tech.hearth.it.api.SyncHttpApi.*
import tech.hearth.it.{BaseFreeSpec, NodeConfigs, ReportingTestName}
import tech.hearth.state.Height

class FeatureActivationTestSuite extends BaseFreeSpec with ActivationStatusRequest with ReportingTestName {

  private val votingInterval      = 12
  private val blocksForActivation = 12 // should be even
  private val featureNum: Short   = BlockchainFeatures.SmallerMinimalGeneratingBalance.id
  private val featureDescr        = BlockchainFeatures.SmallerMinimalGeneratingBalance.description

  import NodeConfigs.*
  override protected def nodeConfigs: Seq[Config] =
    Seq(BiggestMiner, Miners(5)).map(_.overrides(s"""waves {
                                                    |  blockchain.custom.functionality {
                                                    |    pre-activated-features = {}
                                                    |    feature-check-blocks-period = $votingInterval
                                                    |    blocks-for-feature-activation = $blocksForActivation
                                                    |  }
                                                    |  miner.supported-features = [$featureNum]
                                                    |  miner.quorum = 1
                                                    |}""".stripMargin))

  "supported blocks increased when voting starts" in {
    nodes.waitForHeight(Height(votingInterval * 2 / 3))
    val status = nodes.map(_.featureActivationStatus(featureNum))
    status.foreach { s =>
      s.description shouldBe featureDescr
      assertVotingStatus(s, s.supportingBlocks.get, BlockchainFeatureStatus.Undefined, NodeFeatureStatus.Voted)
    }
  }

  "supported blocks counter resets on the next voting interval" in {
    nodes.waitForHeight(Height(votingInterval * 2 - blocksForActivation / 2))
    val info = nodes.map(_.featureActivationStatus(featureNum))
    info.foreach(i => i.blockchainStatus shouldBe BlockchainFeatureStatus.Undefined)
  }

  "blockchain status is APPROVED in second voting interval" in {
    val checkHeight = votingInterval * 2
    nodes.waitForHeight(Height(checkHeight))
    val statusInfo = nodes.map(_.featureActivationStatus(featureNum))
    statusInfo.foreach { si =>
      si.description shouldBe featureDescr
      // Activation will be on a next voting interval
      assertApprovedStatus(si, checkHeight + votingInterval, NodeFeatureStatus.Voted)
    }
  }

  "blockchain status is ACTIVATED in third voting interval" in {
    val checkHeight = votingInterval * 3
    nodes.waitForHeight(Height(checkHeight))
    val statusInfo = nodes.map(_.featureActivationStatus(featureNum))
    statusInfo.foreach { si =>
      si.description shouldBe featureDescr
      assertActivatedStatus(si, checkHeight, NodeFeatureStatus.Implemented)
    }
  }
}
