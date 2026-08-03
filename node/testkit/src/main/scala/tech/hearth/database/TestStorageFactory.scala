package tech.hearth.database

import com.google.common.util.concurrent.MoreExecutors
import tech.hearth.events.BlockchainUpdateTriggers
import tech.hearth.settings.WavesSettings
import tech.hearth.state.BlockchainUpdaterImpl
import tech.hearth.utils.Time

object TestStorageFactory {
  def apply(
      settings: WavesSettings,
      rdb: RDB,
      time: Time,
      blockchainUpdateTriggers: BlockchainUpdateTriggers
  ): (BlockchainUpdaterImpl, RocksDBWriter) = {
    val rocksDBWriter: RocksDBWriter = RocksDBWriter(
      rdb,
      settings.blockchainSettings,
      settings.dbSettings,
      settings.enableLightMode,
      Some(MoreExecutors.newDirectExecutorService())
    )
    (
      new BlockchainUpdaterImpl(rocksDBWriter, settings, time, blockchainUpdateTriggers),
      rocksDBWriter
    )
  }
}
