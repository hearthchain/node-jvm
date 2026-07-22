package com.wavesplatform.settings

import com.typesafe.config.ConfigFactory
import com.wavesplatform.TestHelpers
import com.wavesplatform.db.WithState.AddrWithBalance
import com.wavesplatform.features.BlockchainFeatures

object TestSettings {
  val Default: WavesSettings = WavesSettings.fromRootConfig(ConfigFactory.load())
}
