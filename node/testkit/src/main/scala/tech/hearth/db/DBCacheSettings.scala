package tech.hearth.db

import com.typesafe.config.ConfigFactory
import tech.hearth.settings.WavesSettings

trait DBCacheSettings {
  lazy val dbSettings        = WavesSettings.fromRootConfig(ConfigFactory.load()).dbSettings
  lazy val maxCacheSize: Int = dbSettings.maxCacheSize
}
