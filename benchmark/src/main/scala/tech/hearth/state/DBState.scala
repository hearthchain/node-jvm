package tech.hearth.state

import tech.hearth.Application
import tech.hearth.database.{RDB, RocksDBWriter}
import tech.hearth.settings.HearthSettings
import tech.hearth.utils.ScorexLogging
import org.openjdk.jmh.annotations.{Param, Scope, State, TearDown}

import java.io.File

@State(Scope.Benchmark)
abstract class DBState extends ScorexLogging {
  @Param(Array("hearth.conf"))
  var configFile = ""

  lazy val settings: HearthSettings = Application.loadApplicationConfig(Some(new File(configFile)).filter(_.exists()))

  lazy val rdb: RDB = RDB.open(settings.dbSettings)

  lazy val rocksDBWriter: RocksDBWriter = RocksDBWriter(
    rdb,
    settings.blockchainSettings,
    settings.dbSettings.copy(maxCacheSize = 1),
    settings.enableLightMode
  )

  tech.hearth.crypto.Address.setDefaultHrp(settings.blockchainSettings.networkId.value)

  @TearDown
  def close(): Unit = {
    rocksDBWriter.close()
    rdb.close()
  }
}
