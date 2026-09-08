package tech.hearth.settings

import com.typesafe.config.ConfigFactory
import tech.hearth.test.FlatSpec
import pureconfig.ConfigSource

import scala.concurrent.duration.*

class MinerSettingsSpecification extends FlatSpec {
  private val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

  private def minerSettings(enable: String, accounts: String): MinerSettings = {
    val config = ConfigFactory
      .parseString(s"""
                      |hearth {
                      |  miner {
                      |    enable = $enable
                      |    quorum = 1
                      |    interval-after-last-block-then-generation-is-allowed = 1d
                      |    no-quorum-mining-delay = 5s
                      |    micro-block-interval = 5s
                      |    minimal-block-generation-offset = 500ms
                      |    max-transactions-in-micro-block = 400
                      |    min-micro-block-age = 3s
                      |    accounts = [$accounts]
                      |    supported-features = []
                      |  }
                      |}
      """.stripMargin)
      .resolve()

    ConfigSource.fromConfig(config).at("hearth.miner").loadOrThrow[MinerSettings]
  }

  "MinerSettings" should "require an account when mining is enabled" in {
    intercept[IllegalArgumentException] {
      minerSettings(enable = "yes", accounts = "")
    }.getMessage should include("hearth.miner.accounts")
  }

  it should "allow no accounts when mining is disabled" in {
    minerSettings(enable = "no", accounts = "").accounts shouldBe empty
  }

  it should "read values" in {
    val config = ConfigFactory
      .parseString(s"""
                      |hearth {
                      |  miner {
                      |    enable = yes
                      |    quorum = 1
                      |    interval-after-last-block-then-generation-is-allowed = 1d
                      |    no-quorum-mining-delay = 5s
                      |    micro-block-interval = 5s
                      |    minimal-block-generation-offset = 500ms
                      |    max-transactions-in-micro-block = 400
                      |    min-micro-block-age = 3s
                      |    accounts = [{ mnemonic = "$mnemonic" }]
                      |    supported-features = [1, 2, 4]
                      |    desired-rewards = 700000000
                      |  }
                      |}
      """.stripMargin)
      .resolve()

    val settings = ConfigSource.fromConfig(config).at("hearth.miner").loadOrThrow[MinerSettings]

    settings.enable should be(true)
    settings.quorum should be(1)
    settings.microBlockInterval should be(5.seconds)
    settings.noQuorumMiningDelay should be(5.seconds)
    settings.minimalBlockGenerationOffset should be(500.millis)
    settings.maxTransactionsInMicroBlock should be(400)
    settings.minMicroBlockAge should be(3.seconds)
    settings.supportedFeatures shouldBe Seq(1, 2, 4)
    settings.desiredRewards should be(Some(700000000L))
  }
}
