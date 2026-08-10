package tech.hearth.settings

import com.typesafe.config.ConfigFactory

object TestSettings {
  val Default: HearthSettings = HearthSettings.fromRootConfig(ConfigFactory.load())
}
