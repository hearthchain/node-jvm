package tech.hearth.it.sync

import com.typesafe.config.{Config, ConfigFactory}
import tech.hearth.common.utils.Base16
import tech.hearth.it.BaseFunSuite
import tech.hearth.it.NodeConfigs.Default
import tech.hearth.it.api.SyncHttpApi.*
import tech.hearth.it.sync.SeveralAccountMiningSuite.*
import tech.hearth.state.Height
import tech.hearth.test.*
import tech.hearth.crypto.{Hex, SigningKey}

import scala.concurrent.duration.*

class SeveralAccountMiningSuite extends BaseFunSuite {

  override def nodeConfigs: Seq[Config] = Configs

  test("only private keys from config used for mining when specified") {
    miner.waitForHeight(Height(2), 1.minute)
    val fromHeight = miner.height
    miner.waitForHeight(miner.height + 5, 2.minutes)
    // available (unlike the regular balance) excludes the generation deposit MinerPk1 reserves as a committed
    // generator (see CLAUDE.md's "Balance snapshots" notes), and is read only now - not before the 5-block wait
    // above, during which it also earns mining rewards - so the spent amount is actually spendable.
    val minerBalance1       = miner.balanceDetails(MinerPk1.toAddress.toString).available
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
    tech.hearth.crypto.secureHash(Base16.decode(Default(idx).getString("account-seed")))

  private def getNodeKeyPair(idx: Int): SigningKey = SigningKey.fromSeed(signingSeed(idx))

  val MinerPk1: SigningKey = getNodeKeyPair(2)
  val MinerPk2: SigningKey = getNodeKeyPair(3)

  // vrf-key/bls-key can't be re-derived from account-seed the way the signing key is above: nodes.conf generates
  // them independently per node index, and genesis has already committed node03/node04 (indices 2 and 3) under
  // those exact template values - a self-derived vrf-key here would register a different VRF public key than the
  // one genesis committed, and every block this account mines would fail with "Invalid VRF proof". Using the
  // template's own values for the same index keeps this test's accounts consistent with what genesis expects.
  private def templateMinerAccount(idx: Int): Config = Default(idx).getConfigList("waves.miner.accounts").get(0)

  private def accountConfig(idx: Int): String = {
    val seed            = signingSeed(idx)
    val templateAccount = templateMinerAccount(idx)
    s"""{ signing-key = "${Hex.encode(seed)}", vrf-key = "${templateAccount.getString("vrf-key")}", bls-key = "${templateAccount
        .getString("bls-key")}" }"""
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
