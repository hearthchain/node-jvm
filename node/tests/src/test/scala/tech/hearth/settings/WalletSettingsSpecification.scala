package tech.hearth.settings

import com.typesafe.config.ConfigFactory
import tech.hearth.common.state.ByteStr
import tech.hearth.test.FlatSpec
import pureconfig.ConfigSource

class WalletSettingsSpecification extends FlatSpec {
  "WalletSettings" should "read values from config" in {
    val config = loadConfig(ConfigFactory.parseString("""hearth.wallet {
                                                        |  password: "some string as password"
                                                        |  seed: "aabbccdd"
                                                        |}""".stripMargin))
    val settings = ConfigSource.fromConfig(config).at("hearth.wallet").loadOrThrow[WalletSettings]

    settings.seed should be(Some(ByteStr.decodeBase16("aabbccdd").get))
    settings.password should be(Some("some string as password"))
  }
}
