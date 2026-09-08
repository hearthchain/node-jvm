package tech.hearth.settings

import com.typesafe.config.ConfigFactory
import tech.hearth.test.FlatSpec
import pureconfig.ConfigSource

class WalletSettingsSpecification extends FlatSpec {
  "WalletSettings" should "read values from config" in {
    val config = loadConfig(
      ConfigFactory.parseString("""hearth.wallet {
                                  |  password: "some string as password"
                                  |  mnemonic: "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
                                  |}""".stripMargin)
    )
    val settings = ConfigSource.fromConfig(config).at("hearth.wallet").loadOrThrow[WalletSettings]

    settings.mnemonic should be(Some("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"))
    settings.password should be(Some("some string as password"))
  }
}
