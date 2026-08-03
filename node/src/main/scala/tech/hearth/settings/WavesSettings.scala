package tech.hearth.settings

import com.typesafe.config.{Config, ConfigFactory}
import tech.hearth.metrics.Metrics
import scala.concurrent.duration.FiniteDuration
import pureconfig.*

case class WavesSettings(
    directory: String,
    ntpServer: String,
    maxTxErrorLogSize: Int,
    dbSettings: DBSettings,
    extensions: Seq[String],
    extensionsShutdownTimeout: FiniteDuration,
    networkSettings: NetworkSettings,
    walletSettings: WalletSettings,
    blockchainSettings: BlockchainSettings,
    minerSettings: MinerSettings,
    restAPISettings: RestAPISettings,
    synchronizationSettings: SynchronizationSettings,
    utxSettings: UtxSettings,
    rewardsSettings: RewardsVotingSettings,
    metrics: Metrics.Settings,
    enableLightMode: Boolean,
    autoShutdownOnUnsupportedFeature: Boolean,
    config: Config
)

object WavesSettings {
  def fromRootConfig(rootConfig: Config): WavesSettings = {
    val waves             = rootConfig.getConfig("waves")
    val wavesConfigSource = ConfigSource.fromConfig(waves)

    val directory                 = wavesConfigSource.at("directory").loadOrThrow[String]
    val ntpServer                 = wavesConfigSource.at("ntp-server").loadOrThrow[String]
    val maxTxErrorLogSize         = wavesConfigSource.at("max-tx-error-log-size").loadOrThrow[Int]
    val dbSettings                = wavesConfigSource.at("db").loadOrThrow[DBSettings]
    val extensions                = wavesConfigSource.at("extensions").loadOrThrow[Seq[String]]
    val extensionsShutdownTimeout = wavesConfigSource.at("extensions-shutdown-timeout").loadOrThrow[FiniteDuration]
    val networkSettings           = wavesConfigSource.at("network").loadOrThrow[NetworkSettings]
    val walletSettings            = wavesConfigSource.at("wallet").loadOrThrow[WalletSettings]
    val blockchainSettings        = wavesConfigSource.at("blockchain").loadOrThrow[BlockchainSettings]
    val minerSettings             = wavesConfigSource.at("miner").loadOrThrow[MinerSettings]
    val restAPISettings           = wavesConfigSource.at("rest-api").loadOrThrow[RestAPISettings]
    val synchronizationSettings   = wavesConfigSource.at("synchronization").loadOrThrow[SynchronizationSettings]
    val utxSettings               = wavesConfigSource.at("utx").loadOrThrow[UtxSettings]
    val rewardsSettings           = wavesConfigSource.at("rewards").loadOrThrow[RewardsVotingSettings]
    val metrics                   = wavesConfigSource.at("metrics").loadOrThrow[Metrics.Settings]
    val enableLightMode           = wavesConfigSource.at("enable-light-mode").loadOrThrow[Boolean]

    val autoShutdownOnUnsupportedFeature =
      wavesConfigSource.at("auto-shutdown-on-unsupported-feature").loadOrThrow[Boolean]

    WavesSettings(
      directory,
      ntpServer,
      maxTxErrorLogSize,
      dbSettings,
      extensions,
      extensionsShutdownTimeout,
      networkSettings,
      walletSettings,
      blockchainSettings,
      minerSettings,
      restAPISettings,
      synchronizationSettings,
      utxSettings,
      rewardsSettings,
      metrics,
      enableLightMode,
      autoShutdownOnUnsupportedFeature,
      rootConfig
    )
  }

  def default(): WavesSettings = fromRootConfig(ConfigFactory.load())
}
