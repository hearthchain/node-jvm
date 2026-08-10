package tech.hearth.history

import tech.hearth.database.{DBExt, Keys, RDB, RocksDBWriter}
import tech.hearth.events.BlockchainUpdateTriggers
import tech.hearth.mining.Miner
import tech.hearth.settings.HearthSettings
import tech.hearth.state.{BlockchainUpdaterImpl, Height}
import tech.hearth.utils.{ScorexLogging, Time, UnsupportedFeature, forceStopApplication}
import org.rocksdb.RocksDB

object StorageFactory extends ScorexLogging {
  private val StorageVersion = 2

  def apply(
      settings: HearthSettings,
      rdb: RDB,
      time: Time,
      blockchainUpdateTriggers: BlockchainUpdateTriggers,
      miner: Miner = Miner.StrictDisabledMiner
  ): (BlockchainUpdaterImpl, RocksDBWriter) = {
    checkVersion(rdb.db)
    val rocksDBWriter = RocksDBWriter(rdb, settings.blockchainSettings, settings.dbSettings, settings.enableLightMode)
    val bui = new BlockchainUpdaterImpl(
      rocksDBWriter,
      settings,
      time,
      blockchainUpdateTriggers,
      miner
    )
    (bui, rocksDBWriter)
  }

  private def checkVersion(db: RocksDB): Unit = db.readWrite { rw =>
    val version = rw.get(Keys.version)
    val height  = rw.get(Keys.height)
    if (version != StorageVersion) {
      if (height == Height(0)) {
        // The storage is empty, set current version
        rw.put(Keys.version, StorageVersion)
      } else {
        // Here we've detected that the storage is not empty and doesn't contain version
        log.error(
          s"Storage version $version is not compatible with expected version $StorageVersion! Please, rebuild node's state, use import or sync from scratch."
        )
        log.error("FOR THIS REASON THE NODE STOPPED AUTOMATICALLY")
        forceStopApplication(UnsupportedFeature)
      }
    }
  }
}
