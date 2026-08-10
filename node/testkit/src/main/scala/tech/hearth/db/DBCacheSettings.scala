package tech.hearth.db

import com.typesafe.config.ConfigFactory
import tech.hearth.settings.HearthSettings

trait DBCacheSettings {
  lazy val dbSettings        = HearthSettings.fromRootConfig(ConfigFactory.load()).dbSettings
  lazy val maxCacheSize: Int = dbSettings.maxCacheSize
}
