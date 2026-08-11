package tech.hearth.settings

import java.io.File

import com.typesafe.config.ConfigFactory
import tech.hearth.test.FlatSpec

class HearthSettingsSpecification extends FlatSpec {

  private def config(configName: String) = {
    HearthSettings.fromRootConfig(
      tech.hearth.settings.loadConfig(
        ConfigFactory.parseFile(new File(s"hearth-$configName.conf"))
      )
    )
  }

  def testConfig(configName: String)(additionalChecks: HearthSettings => Unit = _ => ()): Unit = {
    "HearthSettings" should s"read values from default config with $configName overrides" in {
      val settings = config(configName)

      val expected = ConfigFactory
        .parseString(s"hearth.directory = ${tech.hearth.settings.defaultDirectory(settings.config)}")
        .withFallback(ConfigFactory.load())
        .resolve()
        .getString("hearth.directory")

      settings.directory should be(expected)
      settings.networkSettings should not be null
      settings.walletSettings should not be null
      settings.blockchainSettings should not be null
      settings.minerSettings should not be null
      settings.restAPISettings should not be null
      settings.synchronizationSettings should not be null
      settings.utxSettings should not be null
      additionalChecks(settings)
    }
  }

  testConfig("mainnet")()
  testConfig("testnet")()
  testConfig("devnet")()

  "HearthSettings" should "resolve folders correctly" in {
    val config = loadConfig(ConfigFactory.parseString(s"""hearth {
                                                         |  directory = "/xxx"
                                                         |  data-directory = "/xxx/data"
                                                         |  ntp-server = "example.com"
                                                         |}""".stripMargin))

    val settings = HearthSettings.fromRootConfig(config.resolve())

    settings.directory should be("/xxx")
    settings.dbSettings.directory should be("/xxx/data")
    settings.ntpServer should be("example.com")
    settings.networkSettings.file should be(Some(new File("/xxx/peers.dat")))
    settings.walletSettings.file should be(Some(new File("/xxx/wallet/wallet.dat")))
  }

}
