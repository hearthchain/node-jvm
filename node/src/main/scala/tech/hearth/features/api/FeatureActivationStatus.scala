package tech.hearth.features.api

import tech.hearth.features.BlockchainFeatureStatus
import tech.hearth.state.Height

case class FeatureActivationStatus(
    id: Short,
    description: String,
    blockchainStatus: BlockchainFeatureStatus,
    nodeStatus: NodeFeatureStatus,
    activationHeight: Option[Height],
    supportingBlocks: Option[Int]
)
