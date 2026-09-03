package tech.hearth.settings

import com.typesafe.config.ConfigFactory
import tech.hearth.account.NetworkId
import tech.hearth.common.state.ByteStr
import tech.hearth.state.EmissionCurve
import tech.hearth.test.FlatSpec

import scala.concurrent.duration.*

class BlockchainSettingsSpecification extends FlatSpec {
  "BlockchainSettings" should "read custom values" in {
    val config = loadConfig(
      ConfigFactory.parseString(
        """hearth {
          |  directory = "/hearth"
          |  data-directory = "/hearth/data"
          |  blockchain {
          |    type = CUSTOM
          |    custom {
          |      network-id = "chrth"
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
          |        c-emit = 9500000000000000
          |        initial-reward = 600000000
          |        decay-ratio-fixed = "340282322045415694657836056900309514630"
          |        half-life-blocks = 5256000
          |      }
          |      genesis {
          |        timestamp = 1460678400000
          |        block-timestamp = 1460678400000
          |        signature = "aa11bb22cc33dd44"
          |        state-hash = "ee55ff66aa77bb88"
          |        block-id = "1122334455667788"
          |        initial-base-target = 153722867
          |        average-block-delay = 60s
          |      }
          |      predefined-snapshots = [
          |        {
          |          height = 1
          |          assets = [
          |            {id = "aabbccddeeff00112233445566778899", name = "Asset", description = "Desc", decimals = 4, quantity = 1000, min-fee = 100000}
          |          ]
          |          generators = [
          |            {public-key = "HEXPUBLICKEY", endorser-public-key = "HEXBLSKEY", vrf-public-key = "HEXVRFKEY"}
          |          ]
          |          balances = [
          |            {recipient = "ADDRESS1", hearth = 50000000000001},
          |            {recipient = "ADDRESS2", hearth = 49999999999999, assets {aabbccddeeff00112233445566778899 = 1000}}
          |          ]
          |        }
          |      ]
          |    }
          |  }
          |}""".stripMargin
      )
    )
    val settings = BlockchainSettings.fromRootConfig(config)

    settings.networkId should be(NetworkId.unsafeFromString("chrth"))
    settings.functionalitySettings.featureCheckBlocksPeriod should be(10000)
    settings.functionalitySettings.blocksForFeatureActivation should be(9000)
    settings.functionalitySettings.preActivatedFeatures should be(Map(19 -> 100, 20 -> 200))
    settings.functionalitySettings.maxTransactionTimeBackOffset should be(55.seconds)
    settings.functionalitySettings.maxTransactionTimeForwardOffset should be(12.days)
    settings.rewardsSettings.cEmit should be(9500000000000000L)
    settings.rewardsSettings.initialReward should be(600000000L)
    settings.rewardsSettings.decayRatioFixed should be(BigInt("340282322045415694657836056900309514630"))
    settings.rewardsSettings.halfLifeBlocks should be(5256000L)
    settings.genesisSettings.blockTimestamp should be(1460678400000L)
    settings.genesisSettings.timestamp should be(1460678400000L)
    settings.genesisSettings.signature should be(ByteStr.decodeBase16("aa11bb22cc33dd44").toOption)
    settings.genesisSettings.stateHash should be(ByteStr.decodeBase16("ee55ff66aa77bb88").toOption)
    settings.genesisSettings.blockId should be(ByteStr.decodeBase16("1122334455667788").toOption)
    settings.genesisSettings.initialBaseTarget should be(153722867)
    settings.genesisSettings.averageBlockDelay should be(60.seconds)
    val genesisSnapshot = settings.predefinedSnapshots.find(_.height == 1).get
    genesisSnapshot.assets should be(
      Seq(
        GenesisAssetSettings(
          ByteStr.decodeBase16("aabbccddeeff00112233445566778899").get,
          "Asset",
          decimals = 4,
          quantity = 1000,
          minFee = 100000,
          description = "Desc"
        )
      )
    )
    genesisSnapshot.generators should be(Seq(GenesisGeneratorSettings("HEXPUBLICKEY", "HEXBLSKEY", "HEXVRFKEY")))
    genesisSnapshot.balances should be(
      Seq(
        GenesisBalanceSettings("ADDRESS1", 50000000000001L),
        GenesisBalanceSettings("ADDRESS2", 49999999999999L, Map("aabbccddeeff00112233445566778899" -> 1000L))
      )
    )
    // Derived from the genesis balances rather than configured
    settings.initialBalance should be(100000000000000L)
  }

  it should "read testnet settings" in {
    val config = loadConfig(
      ConfigFactory.parseString(
        """hearth {
          |  directory = "/hearth"
          |  data-directory = "/hearth/data"
          |  blockchain {
          |    type = TESTNET
          |  }
          |}""".stripMargin
      )
    )
    val settings = BlockchainSettings.fromRootConfig(config)

    settings.networkId should be(NetworkId.Testnet)
    settings.functionalitySettings.maxTransactionTimeBackOffset should be(120.minutes)
    settings.functionalitySettings.maxTransactionTimeForwardOffset should be(90.minutes)
    settings.rewardsSettings.cEmit should be(9500000000000000L)
    settings.rewardsSettings.initialReward should be(12528345158L)
    settings.rewardsSettings.decayRatioFixed should be(BigInt("340281918165977088157076486680406733895"))
    settings.rewardsSettings.halfLifeBlocks should be(525_600L)
    settings.genesisSettings.timestamp should be(1478000000000L)
    settings.genesisSettings.signature should be(None)  // The genesis block is signed by Block.GenesisGenerator
    settings.initialBalance should be(500000000000000L) // 5% premine; the other 95% is emitted, not genesis-credited

    settings.predefinedSnapshots.find(_.height == 1).get.balances should be(
      Seq(
        GenesisBalanceSettings("thrth1x0welf80ljp2psdstmfywkhqmj9s7q5hjgzpvj", 300000000000000L),
        GenesisBalanceSettings("thrth1nw24ly6qrzatspdzy72t5lhpgcklw7ehcqpjhn", 100000000000000L),
        GenesisBalanceSettings("thrth1wpm9trpt4fm4ucmmq556f6j6arzxg7c4n9rgsj", 100000000000000L)
      )
    )
  }

  it should "read mainnet settings" in {
    val config = loadConfig(
      ConfigFactory.parseString(
        """hearth {
          |  directory = "/hearth"
          |  data-directory = "/hearth/data"
          |  blockchain {
          |    type = MAINNET
          |  }
          |}""".stripMargin
      )
    )
    val settings = BlockchainSettings.fromRootConfig(config)

    settings.networkId should be(NetworkId.Mainnet)
    settings.functionalitySettings.maxTransactionTimeBackOffset should be(120.minutes)
    settings.functionalitySettings.maxTransactionTimeForwardOffset should be(90.minutes)
    settings.rewardsSettings.cEmit should be(9500000000000000L)
    settings.rewardsSettings.initialReward should be(1252834515L)
    settings.rewardsSettings.decayRatioFixed should be(BigInt("340282322045415694657836056900309514630"))
    settings.rewardsSettings.halfLifeBlocks should be(5256000L)
    settings.genesisSettings.timestamp should be(1465742577614L)
    settings.genesisSettings.signature should be(None)  // The genesis block is signed by Block.GenesisGenerator
    settings.initialBalance should be(500000000000000L) // 5% premine; the other 95% is emitted, not genesis-credited
    settings.predefinedSnapshots.find(_.height == 1).get.balances should be(
      Seq(
        GenesisBalanceSettings("hrth1h3s3jrkjgd3f3c705tpczxxmrkxehg6v74gye7", 300000000000000L),
        GenesisBalanceSettings("hrth1nw24ly6qrzatspdzy72t5lhpgcklw7ehuhszwk", 100000000000000L),
        GenesisBalanceSettings("hrth1e5ecq68dxwl7r5gdslt23u5c0c875fjc5f9qu7", 100000000000000L)
      )
    )
  }

  it should "reject a decay ratio above 1.0 (a growing, not decaying, curve)" in {
    val oneFixed = BigInt(1) << EmissionCurve.FixedPointBits

    // Exactly 1.0 (flat, no decay) is fine - node/testkit relies on it (DefaultRewardsSettings/withFlatReward)
    noException should be thrownBy RewardsSettings(cEmit = 1, initialReward = 1, decayRatioFixed = oneFixed, halfLifeBlocks = 1)

    an[IllegalArgumentException] should be thrownBy
      RewardsSettings(cEmit = 1, initialReward = 1, decayRatioFixed = oneFixed + 1, halfLifeBlocks = 1)
  }

  it should "derive hardCap from this network's own premine + cEmit, not the global TotalHearth constant" in {
    val mainnet =
      BlockchainSettings(
        NetworkId.Mainnet,
        FunctionalitySettings.MAINNET,
        GenesisSettings.MAINNET,
        RewardsSettings.MAINNET,
        PredefinedSnapshotSettings.MAINNET
      )
    mainnet.hardCap should be(Constants.TotalHearth * Constants.UnitsInHearth)

    // STAGENET premines the full TotalHearth *and* still emits cEmit on top (see PredefinedSnapshotSettings.STAGENET's
    // comment) - its hardCap must reflect that instead of silently going negative against the global constant.
    val stagenet =
      BlockchainSettings(
        NetworkId.Stagenet,
        FunctionalitySettings.STAGENET,
        GenesisSettings.STAGENET,
        RewardsSettings.STAGENET,
        PredefinedSnapshotSettings.STAGENET
      )
    stagenet.hardCap should be(Constants.TotalHearth * Constants.UnitsInHearth + RewardsSettings.STAGENET.cEmit)
  }
}
