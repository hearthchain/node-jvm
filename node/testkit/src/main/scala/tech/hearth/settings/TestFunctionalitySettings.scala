package tech.hearth.settings

import tech.hearth.features.BlockchainFeature

object TestFunctionalitySettings {
  val Enabled = FunctionalitySettings(
    featureCheckBlocksPeriod = 10000,
    blocksForFeatureActivation = 9000
  )

  def withFeatures(features: BlockchainFeature*): FunctionalitySettings =
    Enabled.copy(preActivatedFeatures = Enabled.preActivatedFeatures ++ features.map(_.id -> 0))

  def withFeaturesByHeight(features: (BlockchainFeature, Int)*): FunctionalitySettings =
    Enabled.copy(preActivatedFeatures = Enabled.preActivatedFeatures ++ features.map { case (f, height) => f.id -> height })

  val Stub: FunctionalitySettings = Enabled.copy(featureCheckBlocksPeriod = 100, blocksForFeatureActivation = 90)

  val EmptyFeaturesSettings: FeaturesSettings =
    FeaturesSettings(autoShutdownOnUnsupportedFeature = false, List.empty)
}
