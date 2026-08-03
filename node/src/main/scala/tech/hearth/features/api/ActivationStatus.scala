package tech.hearth.features.api

import tech.hearth.state.Height

case class ActivationStatus(height: Height, votingInterval: Int, votingThreshold: Int, nextCheck: Height, features: Seq[FeatureActivationStatus])
