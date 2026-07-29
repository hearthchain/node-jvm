package com.wavesplatform

import com.typesafe.config.ConfigFactory
import com.wavesplatform.account.AddressScheme
import com.wavesplatform.block.Block
import com.wavesplatform.common.utils.EitherExt2.*
import com.wavesplatform.settings.GenesisSettings
import com.wavesplatform.test.FreeSpec
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

  private lazy val generated: GenesisSettings = {
    val confBody = GenesisBlockGenerator.createConfig(GenesisBlockGenerator.parseSettings(input))
    ConfigSource.fromConfig(ConfigFactory.parseString(confBody)).at("genesis").loadOrThrow[GenesisSettings]
  }

  "the generated genesis config" - {
    "carries the commitments a node checks on start" in {
      generated.stateHash shouldBe defined
      generated.blockId shouldBe defined
      generated.signature shouldBe defined
    }

    // The generator used to sign a block that was not the genesis block, so the config it emitted could not be started
    "is accepted by the node that builds the genesis block from it" in {
      val block = Block.genesis(generated).explicitGet()

      block.header.stateHash shouldBe generated.stateHash
      block.id() shouldBe generated.blockId.value
      block.signature shouldBe generated.signature.value
      block.signatureValid() shouldBe true
    }

    "commits every miner in the distribution, and no one else" in {
      generated.generators should have size 2
      generated.balances should have size 3
    }
  }
}
