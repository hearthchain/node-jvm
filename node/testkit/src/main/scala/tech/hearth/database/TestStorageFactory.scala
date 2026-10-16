package tech.hearth.database

import com.google.common.util.concurrent.MoreExecutors
import tech.hearth.account.Address
import tech.hearth.events.BlockchainUpdateTriggers
import tech.hearth.settings.HearthSettings
import tech.hearth.state.{BlockchainUpdaterImpl, GenerationPeriod}
import tech.hearth.utils.Time

object TestStorageFactory {

  /** @param work
    *   `workDone` to report from storage regardless of what was written. Only SettleTransactionDiff writes work, and no
    *   fixture can drive a Settle (see "Testing" in docs/notes/cred-economy.md), so this is how a domain test reaches a
    *   staking payout through the real block append path.
    */
  def apply(
      settings: HearthSettings,
      rdb: RDB,
      time: Time,
      blockchainUpdateTriggers: BlockchainUpdateTriggers,
      work: Map[(Address, GenerationPeriod), Long] = Map.empty
  ): (BlockchainUpdaterImpl, RocksDBWriter) = {
    val executor = MoreExecutors.newDirectExecutorService()
    val rocksDBWriter: RocksDBWriter =
      if (work.isEmpty) RocksDBWriter(rdb, settings.blockchainSettings, settings.dbSettings, settings.enableLightMode, Some(executor))
      else
        new RocksDBWriter(rdb, settings.blockchainSettings, settings.dbSettings, settings.enableLightMode, executor) {
          override def workDone(validator: Address, period: GenerationPeriod): Long =
            work.getOrElse((validator, period), super.workDone(validator, period))
        }
    (
      new BlockchainUpdaterImpl(rocksDBWriter, settings, time, blockchainUpdateTriggers),
      rocksDBWriter
    )
  }
}
