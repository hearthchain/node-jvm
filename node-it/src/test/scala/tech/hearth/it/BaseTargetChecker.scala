package tech.hearth.it

import com.typesafe.config.ConfigFactory.{defaultApplication, defaultReference}
import tech.hearth.block.Block
import tech.hearth.common.utils.EitherExt2.*
import tech.hearth.consensus.PoSSelector
import tech.hearth.database.RDB
import tech.hearth.events.BlockchainUpdateTriggers
import tech.hearth.history.StorageFactory
import tech.hearth.settings.*
import tech.hearth.transaction.Asset.Hearth
import tech.hearth.transaction.TxHelpers.vrfKeyOf
import tech.hearth.utils.NTP
import pureconfig.ConfigSource

object BaseTargetChecker {
  def main(args: Array[String]): Unit = {
    val sharedConfig = Docker
      .genesisOverride()
      .withFallback(Docker.configTemplate)
      .withFallback(defaultApplication())
      .withFallback(defaultReference())
      .resolve()

    val settings                       = HearthSettings.fromRootConfig(sharedConfig)
    val db                             = RDB.open(settings.dbSettings.copy(directory = "/tmp/tmp-db"))
    val ntpTime                        = new NTP("ntp.pool.org")
    val (blockchainUpdater, rdbWriter) = StorageFactory(settings, db, ntpTime, BlockchainUpdateTriggers.noop)
    val poSSelector                    = PoSSelector(blockchainUpdater, settings.synchronizationSettings.maxBaseTarget)

    try {
      val genesisBlock =
        Block
          .genesis(
            settings.blockchainSettings
          )
          .explicitGet()
      blockchainUpdater.processBlock(genesisBlock, genesisBlock.header.generationSignature, snapshot = None, generatorSet = Seq.empty)

      NodeConfigs.Default.map(_.withFallback(sharedConfig)).collect {
        case cfg if ConfigSource.fromConfig(cfg).at("hearth.miner.enable").loadOrThrow[Boolean] =>
          val account = keyPairFromSeed(cfg.getString("account-seed")).explicitGet()
          val address = account.toAddress
          val balance = blockchainUpdater.balance(address, Hearth)
          val timeDelay = poSSelector
            .getValidBlockDelay(blockchainUpdater.height, vrfKeyOf(account), genesisBlock.header.baseTarget, balance)
            .explicitGet()

          f"$address: ${timeDelay * 1e-3}%10.3f s"
      }
    } finally {
      ntpTime.close()
      rdbWriter.close()
      db.close()
    }
  }
}
