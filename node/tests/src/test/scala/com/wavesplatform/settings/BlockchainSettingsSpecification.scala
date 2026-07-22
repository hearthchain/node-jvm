package com.wavesplatform.settings

import com.typesafe.config.ConfigFactory
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.test.FlatSpec

import scala.concurrent.duration.*

class BlockchainSettingsSpecification extends FlatSpec {
  "BlockchainSettings" should "read custom values" in {
    val config = loadConfig(
      ConfigFactory.parseString(
        """waves {
          |  directory = "/waves"
          |  data-directory = "/waves/data"
          |  blockchain {
          |    type = CUSTOM
          |    custom {
          |      address-scheme-character = "C"
          |      functionality {
          |        feature-check-blocks-period = 10000
          |        blocks-for-feature-activation = 9000
          |        generation-balance-depth-from-50-to-1000-after-height = 4
          |        block-version-3-after-height = 18
          |        pre-activated-features {
          |          19 = 100
          |          20 = 200
          |        }
          |        double-features-periods-after-height = 21
          |        max-transaction-time-back-offset = 55s
          |        max-transaction-time-forward-offset = 12d
          |        lease-expiration = 1000000
          |        light-node-block-fields-absence-interval = 123
          |      }
          |      rewards {
          |        term = 100000
          |        term-after-capped-reward-feature = 50000
          |        initial = 600000000
          |        min-increment = 50000000
          |        voting-interval = 10000
          |      }
          |      genesis {
          |        timestamp = 1460678400000
          |        block-timestamp = 1460678400000
          |        signature = "021a89a98f742fc15c313e3049"
          |        initial-base-target = 153722867
          |        average-block-delay = 60s
          |        assets = [
          |          {id = "b4e393d26c2a3f66159e", issuer = "BASE58ISSUERKEY", name = "Asset", description = "Desc", decimals = 4, quantity = 1000}
          |        ]
          |        generators = [
          |          {public-key = "BASE58PUBLICKEY", endorser-public-key = "BASE58BLSKEY", vrf-public-key = "BASE58VRFKEY"}
          |        ]
          |        balances = [
          |          {recipient = "ADDRESS1", waves = 50000000000001},
          |          {recipient = "ADDRESS2", waves = 49999999999999, assets {b4e393d26c2a3f66159e = 1000}}
          |        ]
          |      }
          |    }
          |  }
          |}""".stripMargin
      )
    )
    val settings = BlockchainSettings.fromRootConfig(config)

    settings.addressSchemeCharacter should be('C')
    settings.functionalitySettings.featureCheckBlocksPeriod should be(10000)
    settings.functionalitySettings.blocksForFeatureActivation should be(9000)
    settings.functionalitySettings.preActivatedFeatures should be(Map(19 -> 100, 20 -> 200))
    settings.functionalitySettings.maxTransactionTimeBackOffset should be(55.seconds)
    settings.functionalitySettings.maxTransactionTimeForwardOffset should be(12.days)
    settings.rewardsSettings.initial should be(600000000)
    settings.rewardsSettings.minIncrement should be(50000000)
    settings.rewardsSettings.term should be(100000)
    settings.rewardsSettings.termAfterCappedRewardFeature should be(50000)
    settings.rewardsSettings.votingInterval should be(10000)
    settings.genesisSettings.blockTimestamp should be(1460678400000L)
    settings.genesisSettings.timestamp should be(1460678400000L)
    settings.genesisSettings.signature should be(ByteStr.decodeBase16("021a89a98f742fc15c313e3049").toOption)
    settings.genesisSettings.initialBaseTarget should be(153722867)
    settings.genesisSettings.averageBlockDelay should be(60.seconds)
    settings.genesisSettings.assets should be(
      Seq(
        GenesisAssetSettings(
          ByteStr.decodeBase16("b4e393d26c2a3f66159e").get,
          "BASE58ISSUERKEY",
          "Asset",
          decimals = 4,
          quantity = 1000,
          description = "Desc"
        )
      )
    )
    settings.genesisSettings.generators should be(Seq(GenesisGeneratorSettings("BASE58PUBLICKEY", "BASE58BLSKEY", "BASE58VRFKEY")))
    settings.genesisSettings.balances should be(
      Seq(
        GenesisBalanceSettings("ADDRESS1", 50000000000001L),
        GenesisBalanceSettings("ADDRESS2", 49999999999999L, Map("b4e393d26c2a3f66159e" -> 1000L))
      )
    )
    // Derived from the genesis balances rather than configured
    settings.genesisSettings.initialBalance should be(100000000000000L)
  }

  it should "read testnet settings" in {
    val config = loadConfig(
      ConfigFactory.parseString(
        """waves {
          |  directory = "/waves"
          |  data-directory = "/waves/data"
          |  blockchain {
          |    type = TESTNET
          |  }
          |}""".stripMargin
      )
    )
    val settings = BlockchainSettings.fromRootConfig(config)

    settings.addressSchemeCharacter should be('T')
    settings.functionalitySettings.generationBalanceDepthFrom50To1000AfterHeight should be(0)
    settings.functionalitySettings.maxTransactionTimeBackOffset should be(120.minutes)
    settings.functionalitySettings.maxTransactionTimeForwardOffset should be(90.minutes)
    settings.rewardsSettings.initial should be(600000000)
    settings.rewardsSettings.minIncrement should be(50000000)
    settings.rewardsSettings.term should be(100000)
    settings.rewardsSettings.termAfterCappedRewardFeature should be(50000)
    settings.rewardsSettings.votingInterval should be(10000)
    settings.genesisSettings.timestamp should be(1478000000000L)
    settings.genesisSettings.signature should be(None) // The genesis block is signed by Block.GenesisGenerator
    settings.genesisSettings.initialBalance should be(10000000000000000L)

    settings.genesisSettings.balances should be(
      Seq(
        GenesisBalanceSettings("3My3KZgFQ3CrVHgz6vGRt8687sH4oAA1qp8", 400000000000000L),
        GenesisBalanceSettings("3NBVqYXrapgJP9atQccdBPAgJPwHDKkh6A8", 200000000000000L),
        GenesisBalanceSettings("3N5GRqzDBhjVXnCn44baHcz2GoZy5qLxtTh", 200000000000000L),
        GenesisBalanceSettings("3NCBMxgdghg4tUhEEffSXy11L6hUi6fcBpd", 200000000000000L),
        GenesisBalanceSettings("3N18z4B8kyyQ96PhN5eyhCAbg4j49CgwZJx", 9000000000000000L)
      )
    )
  }

  it should "read mainnet settings" in {
    val config = loadConfig(
      ConfigFactory.parseString(
        """waves {
          |  directory = "/waves"
          |  data-directory = "/waves/data"
          |  blockchain {
          |    type = MAINNET
          |  }
          |}""".stripMargin
      )
    )
    val settings = BlockchainSettings.fromRootConfig(config)

    settings.addressSchemeCharacter should be('W')
    settings.functionalitySettings.maxTransactionTimeBackOffset should be(120.minutes)
    settings.functionalitySettings.maxTransactionTimeForwardOffset should be(90.minutes)
    settings.rewardsSettings.initial should be(600000000)
    settings.rewardsSettings.minIncrement should be(50000000)
    settings.rewardsSettings.term should be(100000)
    settings.rewardsSettings.termAfterCappedRewardFeature should be(50000)
    settings.rewardsSettings.votingInterval should be(10000)
    settings.genesisSettings.timestamp should be(1465742577614L)
    settings.genesisSettings.signature should be(None) // The genesis block is signed by Block.GenesisGenerator
    settings.genesisSettings.initialBalance should be(10000000000000000L)
    settings.genesisSettings.balances should be(
      Seq(
        GenesisBalanceSettings("3PAWwWa6GbwcJaFzwqXQN5KQm7H96Y7SHTQ", 9999999500000000L),
        GenesisBalanceSettings("3P8JdJGYc7vaLu4UXUZc1iRLdzrkGtdCyJM", 100000000L),
        GenesisBalanceSettings("3PAGPDPqnGkyhcihyjMHe9v36Y4hkAh9yDy", 100000000L),
        GenesisBalanceSettings("3P9o3ZYwtHkaU1KxsKkFjJqJKS3dLHLC9oF", 100000000L),
        GenesisBalanceSettings("3PJaDyprvekvPXPuAtxrapacuDJopgJRaU3", 100000000L),
        GenesisBalanceSettings("3PBWXDFUc86N2EQxKJmW8eFco65xTyMZx6J", 100000000L)
      )
    )
  }
}
