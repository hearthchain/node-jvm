package tech.hearth.settings

import com.typesafe.config.ConfigFactory

object TestSettings {
  val Default: WavesSettings = WavesSettings.fromRootConfig(ConfigFactory.load())
}
