package tech.hearth.mining

import com.typesafe.config.ConfigFactory
import pureconfig.ConfigSource
import tech.hearth.crypto.{Hex, SigningKey}
import tech.hearth.settings.MinerSettings
import tech.hearth.test.FlatSpec

class GeneratorKeysSpec extends FlatSpec {
  private val seed     = "196bd8403a3cdcf4991edb4928419c71049d0c86f3707b9f441454e0888e61ad"
  private val scalar   = "cd5e9dc1e2e0ec4d1a1a83a1b71b6c3a04e6b2b8b8fd4b6f7b0e21f0a1c2d300"
  private val vrf      = "cf6443690b05217f3422a00a47a333554136a3168353b09bf5414288c1ed6c13"
  private val bls      = "b766c281c9b58983cc09eb653fee3ba02a223bc780d6af230d5c659c9b03ac2e"
  private val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

  private def generatorKeys(account: String): GeneratorKeys = {
    val config = ConfigFactory
      .parseString(s"""hearth.miner {
                      |  enable = yes
                      |  quorum = 1
                      |  interval-after-last-block-then-generation-is-allowed = 1d
                      |  no-quorum-mining-delay = 5s
                      |  micro-block-interval = 5s
                      |  minimal-block-generation-offset = 500ms
                      |  max-transactions-in-micro-block = 400
                      |  min-micro-block-age = 3s
                      |  accounts = [{ $account }]
                      |  supported-features = []
                      |}""".stripMargin)
      .resolve()

    GeneratorKeys.fromSettings(ConfigSource.fromConfig(config).at("hearth.miner").loadOrThrow[MinerSettings])
  }

  "GeneratorKeys" should "build a signing key from a seed" in {
    generatorKeys(s"""signing-key-seed = "$seed", vrf-key = "$vrf", bls-key = "$bls"""").accounts.head.signingKey.toAddress shouldBe SigningKey
      .fromSeed(Hex.decode(seed))
      .toAddress
  }

  it should "build a signing key from a scalar" in {
    generatorKeys(s"""signing-key-scalar = "$scalar", vrf-key = "$vrf", bls-key = "$bls"""").accounts.head.signingKey.toAddress shouldBe SigningKey
      .fromScalar(Hex.decode(scalar))
      .toAddress
  }

  it should "reject an account with both a seed and a scalar" in {
    intercept[IllegalArgumentException] {
      generatorKeys(s"""signing-key-seed = "$seed", signing-key-scalar = "$scalar", vrf-key = "$vrf", bls-key = "$bls"""")
    }.getMessage should include("signing-key-seed and signing-key-scalar are mutually exclusive")
  }

  it should "reject an account with neither a seed nor a scalar" in {
    intercept[IllegalArgumentException] {
      generatorKeys(s"""vrf-key = "$vrf", bls-key = "$bls"""")
    }.getMessage should include("signing-key-seed or signing-key-scalar is required when mnemonic is not provided")
  }

  it should "reject an account mixing a mnemonic with an explicit scalar" in {
    intercept[IllegalArgumentException] {
      generatorKeys(s"""mnemonic = "$mnemonic", signing-key-scalar = "$scalar"""")
    }.getMessage should include("when mnemonic is specified, explicit private keys can not be specified")
  }
}
