package tech.hearth.state

import cats.syntax.either.*
import tech.hearth.block.Block.BlockId
import tech.hearth.block.FinalizationVoting
import tech.hearth.db.WithDomain
import tech.hearth.db.WithState.AddrWithBalance
import tech.hearth.history.Domain
import tech.hearth.network.{EndorseBlock, MessageCodec, PeerDatabase}
import tech.hearth.test.DomainPresets.*
import tech.hearth.test.{FreeSpec, NumericExt, WithResourceManager}
import tech.hearth.transaction.TxHelpers
import tech.hearth.utils.EmbeddedChannelOps
import tech.hearth.wallet.Wallet
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.channel.group.DefaultChannelGroup
import io.netty.util.concurrent.GlobalEventExecutor

class BlockEndorserSpec extends FreeSpec, WithDomain, WithResourceManager, EmbeddedChannelOps {
  private val defaultSettings = {
    val base = DomainPresets.DeterministicFinality
    base
      .copy(
        synchronizationSettings = base.synchronizationSettings.copy(maxRollback = 2),
        // This node's own generators: the endorser signs with the keys the settings name, not with what the wallet holds
        minerSettings = base.minerSettings.copy(accounts = Seq(Domain.walletMiningAccount(0), Domain.walletMiningAccount(1)))
      )
      .configure(
        _.copy(
          generationPeriodLength = 2
        )
      )
  }

  "vote" - {
    "starts voting with increased height" in withManager { manager =>
      val generator1 = TxHelpers.signer(0)
      val generator2 = TxHelpers.signer(1)
      val generators = Seq(generator1, generator2)

      var actualFilter = Option.empty[EndorsementFilter]
      withDomain(defaultSettings, AddrWithBalance.enoughBalances(generator1, generator2)) { d =>
        val endorsementStorage = new EndorsementStorage {
          override def tryAdd(msg: EndorseBlock): Either[String, Boolean] = false.asRight
          override def startVoting(filter: EndorsementFilter): Boolean = {
            actualFilter = Some(filter)
            true
          }
          override def tryCollectAndClear(endorsedId: BlockId): Option[FinalizationVoting] = None
        }

        val channels = manager(new DefaultChannelGroup(GlobalEventExecutor.INSTANCE))
        val endorser =
          new BlockEndorser.InMemory(d.settings.synchronizationSettings.maxRollback, d.blockchain, d.generatorKeys, endorsementStorage, channels)

        log.debug("Append block 2 with commitments")
        val txs                   = generators.map(x => TxHelpers.commitToGeneration(generationPeriodStart = Height(3), x))
        val block2WithCommitments = d.createBlock(txs, generator = generator1, strictTime = true)
        d.appender.appendBlock(block2WithCommitments)

        log.debug("Append blocks 3 and 4 of new period")
        (3 to 4).foreach { _ =>
          val block = d.createBlock(generator = generator1, strictTime = true)
          d.appender.appendBlock(block)
        }

        endorser.vote(d.blockchain.currentGeneratorSet.getOrElse(Seq.empty))
        actualFilter.value.finalizedHeight shouldBe Height(2) // 4 - maxRollback
      }
    }

    "don't broadcast" - {
      "if not enough generating balance" in withManager { manager =>
        val generator1         = Wallet.generateNewAccount(Domain.DefaultWalletSeed, nonce = 0)
        val generator2         = Wallet.generateNewAccount(Domain.DefaultWalletSeed, nonce = 1)
        val otherNodeGenerator = TxHelpers.signer(0)
        val generators         = Seq(generator1, generator2, otherNodeGenerator)
        val generator2Index    = 1

        withDomain(defaultSettings, AddrWithBalance.enoughBalances(generators*), generators = generators) { d =>
          val endorsementStorage = new EndorsementStorage {
            override def tryAdd(msg: EndorseBlock): Either[String, Boolean]                  = true.asRight
            override def startVoting(filter: EndorsementFilter): Boolean                     = true
            override def tryCollectAndClear(endorsedId: BlockId): Option[FinalizationVoting] = None
          }

          val channels = manager(new DefaultChannelGroup(GlobalEventExecutor.INSTANCE))
          val channel1 = manager(new EmbeddedChannel(new MessageCodec(PeerDatabase.NoOp)))
          channels.add(channel1)
          val endorser =
            new BlockEndorser.InMemory(d.settings.synchronizationSettings.maxRollback, d.blockchain, d.generatorKeys, endorsementStorage, channels)

          log.debug("Append block 2 with commitments")
          val txs                   = generators.map(x => TxHelpers.commitToGeneration(generationPeriodStart = Height(3), x))
          val block2WithCommitments = d.createBlock(txs, generator = generator1, strictTime = true)
          d.appender.appendBlock(block2WithCommitments)

          log.debug("Append block 3 of new period with spending all HRTH by generator2")
          d.appender.appendBlock(
            d.createBlock(
              txs = Seq(
                TxHelpers.transfer(
                  from = generator2,
                  to = generator1.toAddress,
                  // Everything it can spend but the fee and a hearth - its deposits are locked, and it holds two of
                  // them, being committed for both the genesis period and the one under test
                  amount = d.blockchain.balance(generator2.toAddress) - d.blockchain.generationDeposit(generator2.toAddress) - 2.hearth,
                  fee = 1.hearth
                )
              ),
              generator = generator1,
              strictTime = true
            )
          )

          log.debug("Append block 4")
          d.appender.appendBlock(d.createBlock(generator = otherNodeGenerator, strictTime = true))

          endorser.vote(d.blockchain.currentGeneratorSet.getOrElse(Nil))
          val xs = channel1.sentEndorsements
          xs should not be empty
          withClue("generator2 didn't endorse: ") {
            xs.count(_.endorserIndex == generator2Index) shouldBe 0
          }
        }
      }
    }
  }
}
