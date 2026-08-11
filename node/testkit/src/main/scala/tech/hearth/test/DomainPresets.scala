package tech.hearth.test

import tech.hearth.db.WithState
import tech.hearth.db.WithState.AddrWithBalance
import tech.hearth.features.BlockchainFeature
import tech.hearth.history.DefaultRewardsSettings
import tech.hearth.settings.{
  FunctionalitySettings,
  GenesisAssetSettings,
  GenesisBalanceSettings,
  PredefinedSnapshotSettings,
  HearthSettings,
  loadConfig
}
import tech.hearth.state.GenesisBlockHeight
import tech.hearth.crypto.SigningKey

import scala.concurrent.duration.DurationInt

object DomainPresets {
  given Conversion[AddrWithBalance, GenesisBalanceSettings] = (b: AddrWithBalance) => GenesisBalanceSettings(b.address.toBech32, b.balance)

  extension (ws: HearthSettings) {
    def withFunctionalitySettings(fs: FunctionalitySettings): HearthSettings =
      ws.copy(blockchainSettings = ws.blockchainSettings.copy(functionalitySettings = fs))

    // Finds-or-creates the PredefinedSnapshotSettings entry at `height` and replaces it in the list.
    private def updatePredefinedSnapshot(height: Int)(f: PredefinedSnapshotSettings => PredefinedSnapshotSettings): HearthSettings = {
      val current  = ws.blockchainSettings.predefinedSnapshots
      val existing = current.find(_.height == height).getOrElse(PredefinedSnapshotSettings(height))
      ws.copy(blockchainSettings = ws.blockchainSettings.copy(predefinedSnapshots = f(existing) +: current.filterNot(_.height == height)))
    }

    def withGenesisBalances(balances: AddrWithBalance*): HearthSettings =
      if (balances.isEmpty) ws
      else
        updatePredefinedSnapshot(GenesisBlockHeight.toInt) { s =>
          s.copy(
            // Dedupe by recipient (genesis forbids duplicate recipients): lets a caller prepend a shared funding list
            // - e.g. a committed generator pool - in front of its own per-test entries without colliding.
            balances = balances
              .distinctBy(_.address)
              .map(b => GenesisBalanceSettings(b.address.toBech32, b.balance, b.assets.map { case (k, v) => k.toString -> v }))
          )
        }

    def withGenesisGenerators(generators: SigningKey*): HearthSettings =
      if (generators.isEmpty) ws
      else
        updatePredefinedSnapshot(GenesisBlockHeight.toInt) { s =>
          s.copy(
            // Callers may commit an overlapping pool (e.g. a fixed generator pool plus a challenger drawn from it),
            // so dedupe by public key: a generator is committed once, matching the genesis snapshot's own rule.
            generators = generators.distinctBy(g => tech.hearth.common.state.ByteStr(g.publicKey())).map(WithState.genesisGeneratorFor)
          )
        }

    def withGenesisAssets(assets: GenesisAssetSettings*): HearthSettings =
      if (assets.isEmpty) ws
      else updatePredefinedSnapshot(GenesisBlockHeight.toInt)(_.copy(assets = assets))

    def configure(transformF: FunctionalitySettings => FunctionalitySettings): HearthSettings = {
      val functionalitySettings = transformF(ws.blockchainSettings.functionalitySettings)
      ws.copy(blockchainSettings = ws.blockchainSettings.copy(functionalitySettings = functionalitySettings))
    }

    def withFeatures(fs: BlockchainFeature*): HearthSettings =
      configure(_.copy(preActivatedFeatures = fs.map(_.id -> 0).toMap))

    def addFeatures(fs: BlockchainFeature*): HearthSettings = configure { functionalitySettings =>
      val newFeatures = functionalitySettings.preActivatedFeatures ++ fs.map(_.id -> 0)
      functionalitySettings.copy(preActivatedFeatures = newFeatures)
    }

    def setFeaturesHeight(fs: (BlockchainFeature, Int)*): HearthSettings = configure { functionalitySettings =>
      val newFeatures = functionalitySettings.preActivatedFeatures ++ fs.map { case (f, height) => (f.id, height) }
      functionalitySettings.copy(preActivatedFeatures = newFeatures)
    }

    def withActivationPeriod(period: Int): HearthSettings =
      configure(_.copy(featureCheckBlocksPeriod = period, blocksForFeatureActivation = period))

    def noFeatures(): HearthSettings = {
      ws.copy(
        blockchainSettings = ws.blockchainSettings.copy(
          functionalitySettings = ws.blockchainSettings.functionalitySettings
            .copy(preActivatedFeatures = Map.empty)
        ),
        minerSettings = ws.minerSettings.copy(supportedFeatures = Nil)
      )
    }
  }

  /** Blocks are timestamped from the genesis block onwards (parent.timestamp + block delay), while TxHelpers stamps
    * transactions with the current time. The TESTNET genesis sits in 2016, which would put every transaction years in
    * the future relative to its block, so tests start the chain an hour ago instead.
    */
  private lazy val genesisTimestamp: Long = System.currentTimeMillis() - 1.hour.toMillis

  lazy val SettingsFromDefaultConfig: HearthSettings = {
    val settings = HearthSettings.fromRootConfig(loadConfig(None))
    // The default config is TESTNET, but genesis balances are now part of a predefined snapshot built from the
    // settings, and tests declare their own via withDomain(balances = ...). So start with an empty genesis snapshot.
    // Reward is pinned flat too (see DefaultRewardsSettings): TESTNET's own RewardsSettings is tuned for observing
    // the emission curve decay on a running testnet (short half-life, large reward), not for tests that want a
    // small, exactly predictable value to assert on - the same reasoning as history.DefaultBlockchainSettings.
    settings.copy(blockchainSettings =
      settings.blockchainSettings.copy(
        genesisSettings = settings.blockchainSettings.genesisSettings.copy(timestamp = genesisTimestamp),
        predefinedSnapshots = Seq(PredefinedSnapshotSettings(GenesisBlockHeight.toInt)),
        rewardsSettings = DefaultRewardsSettings
      )
    )
  }

  def domainSettingsWithFS(fs: FunctionalitySettings): HearthSettings =
    SettingsFromDefaultConfig.copy(
      blockchainSettings = SettingsFromDefaultConfig.blockchainSettings.copy(functionalitySettings = fs)
    )

  def domainSettingsWithPreactivatedFeatures(fs: BlockchainFeature*): HearthSettings =
    domainSettingsWithFeatures(fs.map(_ -> 0)*)

  def domainSettingsWithFeatures(fs: (BlockchainFeature, Int)*): HearthSettings = {
    val defaultFS = SettingsFromDefaultConfig
      .noFeatures()
      .blockchainSettings
      .functionalitySettings

    domainSettingsWithFS(defaultFS.copy(preActivatedFeatures = fs.map { case (f, h) =>
      f.id -> h
    }.toMap))
  }

  val NG: HearthSettings = domainSettingsWithPreactivatedFeatures()

  val ScriptsAndSponsorship: HearthSettings = NG

  val RideV3: HearthSettings = ScriptsAndSponsorship

  val RideV4: HearthSettings = RideV3

  val RideV4WithRewards: HearthSettings = RideV4

  val RideV5: HearthSettings = RideV4

  val RideV6: HearthSettings = RideV5

  val ConsensusImprovements: HearthSettings = RideV6

  val BlockRewardDistribution: HearthSettings = ConsensusImprovements

  val TransactionStateSnapshot: HearthSettings = BlockRewardDistribution

  val DeterministicFinality: HearthSettings = TransactionStateSnapshot

  def mostRecent: HearthSettings = RideV6
}
