package tech.hearth.state

import tech.hearth.account.{Address, NetworkId}
import tech.hearth.api.common.AddressTransactions
import tech.hearth.common.state.ByteStr
import tech.hearth.database.{RDB, RocksDBWriter, TestStorageFactory}
import tech.hearth.events.BlockchainUpdateTriggers
import tech.hearth.history.DefaultRewardsSettings
import tech.hearth.settings.{BlockchainSettings, FunctionalitySettings, GenesisSettings, TestSettings}
import tech.hearth.test.DomainPresets.*
import tech.hearth.transaction.{Transaction, TransactionType}
import tech.hearth.utils.SystemTime
import monix.execution.Scheduler

package object utils {

  def addressTransactions(
      rdb: RDB,
      snapshot: => Option[(Height, StateSnapshot)],
      address: Address,
      types: Set[TransactionType],
      fromId: Option[ByteStr]
  )(implicit s: Scheduler): Seq[(Height, Transaction)] =
    AddressTransactions
      .allAddressTransactions(rdb, snapshot, address, None, types, fromId)
      .map { case (tm, tx, _) => tm.height -> tx }
      .toListL
      .runSyncUnsafe(scala.concurrent.duration.Duration(60, "s"))

  object TestRocksDB {

    def withFunctionalitySettings(
        rdb: RDB,
        fs: FunctionalitySettings
    ): RocksDBWriter =
      TestStorageFactory(
        TestSettings.Default.withFunctionalitySettings(fs),
        rdb,
        SystemTime,
        BlockchainUpdateTriggers.noop
      )._2

    def createTestBlockchainSettings(fs: FunctionalitySettings): BlockchainSettings =
      BlockchainSettings(NetworkId.Testnet, fs, GenesisSettings.TESTNET, DefaultRewardsSettings)
  }
}
