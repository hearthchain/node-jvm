package com.wavesplatform.test

import com.wavesplatform.database.{RDB, TestStorageFactory}
import com.wavesplatform.db.DBCacheSettings
import com.wavesplatform.db.WithState
import com.wavesplatform.db.WithState.AddrWithBalance
import com.wavesplatform.events.BlockchainUpdateTriggers
import com.wavesplatform.history.Domain
import com.wavesplatform.settings.WavesSettings
import com.wavesplatform.transaction.TxHelpers
import com.wavesplatform.{NTPTime, TestHelpers}
import org.scalatest.{BeforeAndAfterAll, Suite}

import java.nio.file.Files
import com.wavesplatform.test.DomainPresets.*

trait SharedDomain extends BeforeAndAfterAll with NTPTime with DBCacheSettings { suite: Suite =>
  private val path = Files.createTempDirectory(s"rocks-temp-${getClass.getSimpleName}").toAbsolutePath
  private val rdb  = RDB.open(dbSettings.copy(directory = path.toAbsolutePath.toString))

  def settings: WavesSettings               = DomainPresets.DeterministicFinality
  def genesisBalances: Seq[AddrWithBalance] = Seq.empty

  // Genesis balances are part of the genesis snapshot, which is built from the settings the state is created with.
  // defaultSigner is always committed as the generator here, so it also has to be funded for its genesis deposit -
  // unless the spec's own genesisBalances already fund it.
  protected lazy val domainSettings: WavesSettings = {
    val balancesWithMiner =
      if (genesisBalances.exists(_.address == TxHelpers.defaultSigner.toAddress)) genesisBalances
      else AddrWithBalance(TxHelpers.defaultSigner.toAddress) +: genesisBalances
    settings
      .withGenesisBalances(balancesWithMiner*)
      .withGenesisGenerators(TxHelpers.defaultSigner)
  }

  private lazy val (bui, ldb) = TestStorageFactory(domainSettings, rdb, ntpTime, BlockchainUpdateTriggers.noop)

  lazy val domain: Domain = Domain(rdb, bui, ldb, domainSettings)

  override protected def beforeAll(): Unit = {
    if (genesisBalances.nonEmpty) domain.appendBlock(WithState.createGenesisBlock(domainSettings))
    super.beforeAll()
  }

  override protected def afterAll(): Unit = {
    super.afterAll()
    bui.shutdown()
    ldb.close()
    rdb.close()
    TestHelpers.deleteRecursively(path)
  }
}
