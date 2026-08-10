package tech.hearth.settings

import com.typesafe.config.{Config, ConfigFactory}
import tech.hearth.metrics.Metrics
import scala.concurrent.duration.FiniteDuration
import pureconfig.*

case class HearthSettings(
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

object HearthSettings {
  def fromRootConfig(rootConfig: Config): HearthSettings = {
    val hearth             = rootConfig.getConfig("hearth")
    val hearthConfigSource = ConfigSource.fromConfig(hearth)

    val directory                 = hearthConfigSource.at("directory").loadOrThrow[String]
    val ntpServer                 = hearthConfigSource.at("ntp-server").loadOrThrow[String]
    val maxTxErrorLogSize         = hearthConfigSource.at("max-tx-error-log-size").loadOrThrow[Int]
    val dbSettings                = hearthConfigSource.at("db").loadOrThrow[DBSettings]
    val extensions                = hearthConfigSource.at("extensions").loadOrThrow[Seq[String]]
    val extensionsShutdownTimeout = hearthConfigSource.at("extensions-shutdown-timeout").loadOrThrow[FiniteDuration]
    val networkSettings           = hearthConfigSource.at("network").loadOrThrow[NetworkSettings]
    val walletSettings            = hearthConfigSource.at("wallet").loadOrThrow[WalletSettings]
    val blockchainSettings        = hearthConfigSource.at("blockchain").loadOrThrow[BlockchainSettings]
    val minerSettings             = hearthConfigSource.at("miner").loadOrThrow[MinerSettings]
    val restAPISettings           = hearthConfigSource.at("rest-api").loadOrThrow[RestAPISettings]
    val synchronizationSettings   = hearthConfigSource.at("synchronization").loadOrThrow[SynchronizationSettings]
    val utxSettings               = hearthConfigSource.at("utx").loadOrThrow[UtxSettings]
    val rewardsSettings           = hearthConfigSource.at("rewards").loadOrThrow[RewardsVotingSettings]
    val metrics                   = hearthConfigSource.at("metrics").loadOrThrow[Metrics.Settings]
    val enableLightMode           = hearthConfigSource.at("enable-light-mode").loadOrThrow[Boolean]

    val autoShutdownOnUnsupportedFeature =
      hearthConfigSource.at("auto-shutdown-on-unsupported-feature").loadOrThrow[Boolean]

    HearthSettings(
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

  def default(): HearthSettings = fromRootConfig(ConfigFactory.load())
}
