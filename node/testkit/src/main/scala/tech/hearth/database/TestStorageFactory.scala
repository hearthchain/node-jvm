package tech.hearth.database

import com.google.common.util.concurrent.MoreExecutors
import tech.hearth.account.Address
import tech.hearth.events.BlockchainUpdateTriggers
import tech.hearth.settings.HearthSettings
import tech.hearth.state.{BlockchainUpdaterImpl, GenerationPeriod}
import tech.hearth.utils.Time

object TestStorageFactory {

  /** @param work
    *   `workDone` to report regardless of what was written, for a domain test to reach a staking payout through the
    *   real append path. A diff-level test injects work with WithState.blockchainWithCommitteeWork instead.
    */
  def apply(
      settings: HearthSettings,
      rdb: RDB,
      time: Time,
      blockchainUpdateTriggers: BlockchainUpdateTriggers,
      work: Map[(Address, GenerationPeriod), Long] = Map.empty
  ): (BlockchainUpdaterImpl, RocksDBWriter) = {
    val rocksDBWriter =
      new RocksDBWriter(rdb, settings.blockchainSettings, settings.dbSettings, settings.enableLightMode, MoreExecutors.newDirectExecutorService()) {
        override def workDone(validator: Address, period: GenerationPeriod): Long =
          work.getOrElse((validator, period), super.workDone(validator, period))
      }
    (
      new BlockchainUpdaterImpl(rocksDBWriter, settings, time, blockchainUpdateTriggers),
      rocksDBWriter
    )
  }
}
