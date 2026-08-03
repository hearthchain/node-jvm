package tech.hearth.test

import tech.hearth.db.WithState
import tech.hearth.db.WithState.AddrWithBalance
import tech.hearth.features.BlockchainFeature
import tech.hearth.settings.{FunctionalitySettings, GenesisAssetSettings, GenesisBalanceSettings, WavesSettings, loadConfig}
import tech.hearth.crypto.SigningKey

import scala.concurrent.duration.DurationInt

object DomainPresets {
  given Conversion[AddrWithBalance, GenesisBalanceSettings] = (b: AddrWithBalance) => GenesisBalanceSettings(b.address.toBech32, b.balance)

  extension (ws: WavesSettings) {
    def withFunctionalitySettings(fs: FunctionalitySettings): WavesSettings =
      ws.copy(blockchainSettings = ws.blockchainSettings.copy(functionalitySettings = fs))

    def withGenesisBalances(balances: AddrWithBalance*): WavesSettings =
      if (balances.isEmpty) ws
      else
        ws.copy(blockchainSettings =
          ws.blockchainSettings.copy(
            genesisSettings = ws.blockchainSettings.genesisSettings.copy(
              // Dedupe by recipient (genesis forbids duplicate recipients): lets a caller prepend a shared funding list
              // - e.g. a committed generator pool - in front of its own per-test entries without colliding.
              balances = balances
                .distinctBy(_.address)
                .map(b => GenesisBalanceSettings(b.address.toBech32, b.balance, b.assets.map { case (k, v) => k.toString -> v }))
            )
          )
        )

    def withGenesisGenerators(generators: SigningKey*): WavesSettings =
      if (generators.isEmpty) ws
      else
        ws.copy(blockchainSettings =
          ws.blockchainSettings.copy(
            genesisSettings = ws.blockchainSettings.genesisSettings.copy(
              // Callers may commit an overlapping pool (e.g. a fixed generator pool plus a challenger drawn from it),
              // so dedupe by public key: a generator is committed once, matching the genesis snapshot's own rule.
              generators = generators.distinctBy(g => tech.hearth.common.state.ByteStr(g.publicKey())).map(WithState.genesisGeneratorFor)
            )
          )
        )

    def withGenesisAssets(assets: GenesisAssetSettings*): WavesSettings =
      if (assets.isEmpty) ws
      else
        ws.copy(blockchainSettings =
          ws.blockchainSettings.copy(
            genesisSettings = ws.blockchainSettings.genesisSettings.copy(
              assets = assets
            )
          )
        )

    def configure(transformF: FunctionalitySettings => FunctionalitySettings): WavesSettings = {
      val functionalitySettings = transformF(ws.blockchainSettings.functionalitySettings)
      ws.copy(blockchainSettings = ws.blockchainSettings.copy(functionalitySettings = functionalitySettings))
    }

    def withFeatures(fs: BlockchainFeature*): WavesSettings =
      configure(_.copy(preActivatedFeatures = fs.map(_.id -> 0).toMap))

    def addFeatures(fs: BlockchainFeature*): WavesSettings = configure { functionalitySettings =>
      val newFeatures = functionalitySettings.preActivatedFeatures ++ fs.map(_.id -> 0)
      functionalitySettings.copy(preActivatedFeatures = newFeatures)
    }

    def setFeaturesHeight(fs: (BlockchainFeature, Int)*): WavesSettings = configure { functionalitySettings =>
      val newFeatures = functionalitySettings.preActivatedFeatures ++ fs.map { case (f, height) => (f.id, height) }
      functionalitySettings.copy(preActivatedFeatures = newFeatures)
    }

    def withActivationPeriod(period: Int): WavesSettings =
      configure(_.copy(featureCheckBlocksPeriod = period, blocksForFeatureActivation = period))

    def noFeatures(): WavesSettings = {
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

  lazy val SettingsFromDefaultConfig: WavesSettings = {
    val settings = WavesSettings.fromRootConfig(loadConfig(None))
    // The default config is TESTNET, but genesis balances are now part of the genesis snapshot built from the settings,
    // and tests declare their own via withDomain(balances = ...). So start from an empty genesis.
    settings.copy(blockchainSettings =
      settings.blockchainSettings.copy(genesisSettings =
        settings.blockchainSettings.genesisSettings.copy(
          balances = Seq.empty,
          timestamp = genesisTimestamp
        )
      )
    )
  }

  def domainSettingsWithFS(fs: FunctionalitySettings): WavesSettings =
    SettingsFromDefaultConfig.copy(
      blockchainSettings = SettingsFromDefaultConfig.blockchainSettings.copy(functionalitySettings = fs)
    )

  def domainSettingsWithPreactivatedFeatures(fs: BlockchainFeature*): WavesSettings =
    domainSettingsWithFeatures(fs.map(_ -> 0)*)

  def domainSettingsWithFeatures(fs: (BlockchainFeature, Int)*): WavesSettings = {
    val defaultFS = SettingsFromDefaultConfig
      .noFeatures()
      .blockchainSettings
      .functionalitySettings

    domainSettingsWithFS(defaultFS.copy(preActivatedFeatures = fs.map { case (f, h) =>
      f.id -> h
    }.toMap))
  }

  val NG: WavesSettings = domainSettingsWithPreactivatedFeatures()

  val ScriptsAndSponsorship: WavesSettings = NG

  val RideV3: WavesSettings = ScriptsAndSponsorship

  val RideV4: WavesSettings = RideV3

  val RideV4WithRewards: WavesSettings = RideV4

  val RideV5: WavesSettings = RideV4

  val RideV6: WavesSettings = RideV5

  val ConsensusImprovements: WavesSettings = RideV6

  val BlockRewardDistribution: WavesSettings = ConsensusImprovements

  val TransactionStateSnapshot: WavesSettings = BlockRewardDistribution

  val DeterministicFinality: WavesSettings = TransactionStateSnapshot

  def mostRecent: WavesSettings = RideV6
}
