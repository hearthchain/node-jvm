package tech.hearth

import com.typesafe.config.ConfigFactory
import tech.hearth.account.AddressScheme
import tech.hearth.block.Block
import tech.hearth.common.utils.EitherExt2.*
import tech.hearth.settings.{BlockchainSettings, FunctionalitySettings, GenesisSettings, PredefinedSnapshotSettings, RewardsSettings}
import tech.hearth.state.GenesisBlockHeight
import tech.hearth.test.FreeSpec
import org.scalatest.EitherValues
import pureconfig.ConfigSource

class GenesisBlockGeneratorSpec extends FreeSpec with EitherValues {
  // The generator sets AddressScheme.current from network-type; keep it at what the rest of the suite runs with
  private val networkType = AddressScheme.current.chainId.toChar

  private val input = ConfigFactory.parseString(
    s"""genesis-generator {
       |  network-type = "$networkType"
       |  base-target = 153722867
       |  average-block-delay = 60s
       |  timestamp = 1500635421931
       |  distributions = [
       |    { seed-text = "foo", nonce = 0, amount = 1028000000000000 }
       |    { seed-text = "foo", nonce = 1, amount = 375000000000000 }
       |    { seed-text = "bar", amount = 4215000000000000, miner = false }
       |  ]
       |}""".stripMargin
  )

  private lazy val (generatedGenesis, generatedSnapshot): (GenesisSettings, PredefinedSnapshotSettings) = {
    val confBody = GenesisBlockGenerator.createConfig(GenesisBlockGenerator.parseSettings(input))
    val parsed   = ConfigFactory.parseString(confBody)
    val genesis  = ConfigSource.fromConfig(parsed).at("genesis").loadOrThrow[GenesisSettings]
    val snapshot = ConfigSource
      .fromConfig(parsed)
      .at("predefined-snapshots")
      .loadOrThrow[Seq[PredefinedSnapshotSettings]]
      .find(_.height == GenesisBlockHeight.toInt)
      .value
    (genesis, snapshot)
  }

  private lazy val generatedBlockchainSettings: BlockchainSettings =
    // functionalitySettings/rewardsSettings/addressSchemeCharacter don't affect Block.genesis
    BlockchainSettings(networkType, FunctionalitySettings(), generatedGenesis, RewardsSettings.MAINNET, Seq(generatedSnapshot))

  "the generated genesis config" - {
    "carries the commitments a node checks on start" in {
      generatedGenesis.stateHash shouldBe defined
      generatedGenesis.blockId shouldBe defined
      generatedGenesis.signature shouldBe defined
    }

    // The generator used to sign a block that was not the genesis block, so the config it emitted could not be started
    "is accepted by the node that builds the genesis block from it" in {
      val block = Block.genesis(generatedBlockchainSettings).explicitGet()

      block.header.stateHash shouldBe generatedGenesis.stateHash
      block.id() shouldBe generatedGenesis.blockId.value
      block.signature shouldBe generatedGenesis.signature.value
      block.signatureValid() shouldBe true
    }

    "commits every miner in the distribution, and no one else" in {
      generatedSnapshot.generators should have size 2
      generatedSnapshot.balances should have size 3
    }
  }
}
