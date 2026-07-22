package com.wavesplatform.state

import com.wavesplatform.account.Address
import com.wavesplatform.api.common.AddressTransactions
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.database.{RDB, RocksDBWriter, TestStorageFactory}
import com.wavesplatform.events.BlockchainUpdateTriggers
import com.wavesplatform.settings.{BlockchainSettings, FunctionalitySettings, GenesisSettings, RewardsSettings, TestSettings}
import com.wavesplatform.test.DomainPresets.*
import com.wavesplatform.transaction.{Transaction, TransactionType}
import com.wavesplatform.utils.SystemTime
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
      BlockchainSettings('T', fs, GenesisSettings.TESTNET.copy(balances = Seq.empty), RewardsSettings.TESTNET)
  }
}
