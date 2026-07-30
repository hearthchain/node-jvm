package com.wavesplatform.it.sync

import com.typesafe.config.{Config, ConfigFactory}
import com.wavesplatform.common.utils.Base58
import com.wavesplatform.it.BaseFunSuite
import com.wavesplatform.it.NodeConfigs.Default
import com.wavesplatform.it.api.SyncHttpApi.*
import com.wavesplatform.it.sync.SeveralAccountMiningSuite.*
import com.wavesplatform.state.Height
import com.wavesplatform.test.*
import tech.hearth.crypto.{Crypto, Hex, SigningKey}

import scala.concurrent.duration.*

class SeveralAccountMiningSuite extends BaseFunSuite {

  override def nodeConfigs: Seq[Config] = Configs

  test("only private keys from config used for mining when specified") {
    miner.waitForHeight(Height(2), 1.minute)
    val minerBalance1 = miner.balance(MinerPk1.toAddress.toString).balance
    val fromHeight    = miner.height
    miner.waitForHeight(miner.height + 5, 2.minutes)
    val tx                  = miner.transfer(MinerPk1, notMiner.address, minerBalance1 - 10.waves, waitForTx = true)
    val minerTransferHeight = nodes.waitForTransaction(tx.id).height
    nodes.waitForHeight(Height(minerTransferHeight) + 5)
    val pkMiners = Set(MinerPk1.toAddress.toString, MinerPk2.toAddress.toString)
    notMiner.blockSeq(fromHeight, notMiner.height).foreach { block =>
      if (block.height <= minerTransferHeight) {
        pkMiners should contain(block.generator)
      } else {
        block.generator shouldBe MinerPk2.toAddress.toString
      }
    }
  }
}

object SeveralAccountMiningSuite {
  // MinerSettings has no raw private-keys list any more: an account is either a mnemonic (with derivation
  // nonces) or hex-encoded signingKey/vrfKey seeds, from which MinerImpl derives the runtime SigningKey/VrfKey.
  private def signingSeed(idx: Int): Array[Byte] =
    com.wavesplatform.crypto.secureHash(Base58.decode(Default(idx).getString("account-seed")))

  private def vrfSeed(signingSeed: Array[Byte]): Array[Byte] =
    Crypto.defaultBackend().sha256(SigningKey.fromSeed(signingSeed).publicKey())

  private def getNodeKeyPair(idx: Int): SigningKey = SigningKey.fromSeed(signingSeed(idx))

  val MinerPk1: SigningKey = getNodeKeyPair(2)
  val MinerPk2: SigningKey = getNodeKeyPair(3)

  private def accountConfig(idx: Int): String = {
    val seed = signingSeed(idx)
    s"""{ signing-key = "${Hex.encode(seed)}", vrf-key = "${Hex.encode(vrfSeed(seed))}" }"""
  }

  private val minerConfig =
    ConfigFactory.parseString(s"""
                                 |waves {
                                 |  blockchain.custom.genesis {
                                 |     average-block-delay = 3s
                                 |  }
                                 |  blockchain.custom.functionality {
                                 |    pre-activated-features.1 = 0
                                 |    min-block-time = 3s
                                 |  }
                                 |  miner {
                                 |    quorum = 0
                                 |    accounts = [${accountConfig(2)}, ${accountConfig(3)}]
                                 |  }
                                 |}""".stripMargin)

  private val nonMinerConfig =
    ConfigFactory.parseString(s"""
                                 |waves {
                                 |  blockchain.custom.genesis {
                                 |     average-block-delay = 3s
                                 |  }
                                 |  blockchain.custom.functionality {
                                 |    pre-activated-features.1 = 0
                                 |    min-block-time = 3s
                                 |  }
                                 |  miner {
                                 |    enable = no
                                 |  }
                                 |}""".stripMargin)

  val Configs: Seq[Config] = Seq(
    minerConfig.withFallback(Default.head),
    nonMinerConfig.withFallback(Default(1))
  )
}
