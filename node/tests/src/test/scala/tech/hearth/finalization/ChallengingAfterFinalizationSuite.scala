package tech.hearth.finalization

import tech.hearth.TestValues
import tech.hearth.common.state.ByteStr
import tech.hearth.consensus.GeneratingBalanceProvider.MinimalEffectiveBalanceForGenerator2
import tech.hearth.crypto.DigestLength
import tech.hearth.db.WithState.AddrWithBalance
import tech.hearth.history.{Domain, defaultVrfKey}
import tech.hearth.state.*
import tech.hearth.test.DomainPresets.*
import tech.hearth.test.TestSchedulerOps
import tech.hearth.transaction.{CommitToGenerationTransaction, TxHelpers}
import tech.hearth.wallet.Wallet
import org.scalatest.time.SpanSugar.convertLongToGrainOfTime

class ChallengingAfterFinalizationSuite extends BaseFinalizationSpec, TestSchedulerOps {
  private val thisNodeAcc        = Wallet.generateNewAccount(Domain.DefaultWalletSeed, nonce = 0)
  private val committedGenerator = TxHelpers.defaultSigner

  private val baseSettings = DomainPresets.DeterministicFinality
  private val defaultSettings = baseSettings
    .copy(minerSettings =
      baseSettings.minerSettings.copy(
        quorum = 0,
        microBlockInterval = 100.millis,
        // A challenger is a generator like any other, and it has to be one of this node's own accounts for
        // BlockChallenger to pick it up.
        accounts = Seq(Domain.walletMiningAccount(0))
      )
    )
    .configure(_.copy(generationPeriodLength = 2))

  // Two deposits each: committed in genesis for the current period, and again for the period under test - the first
  // block of a period is checked against the committed set of the parent's period, so a generator that only commits
  // for the period it starts cannot produce that block. Both accounts get the same balance so that the challenged
  // block's deliberately-worsened timestamp below is what decides the race, not an unfair balance edge.
  private val accountBalance =
    MinimalEffectiveBalanceForGenerator2 + TestValues.commitToGenerationFee + 2 * CommitToGenerationTransaction.DepositInWavelets

  "Anyone can challenge" in withDomain(
    defaultSettings,
    Seq(committedGenerator, thisNodeAcc).map(kp => AddrWithBalance(kp.toAddress, accountBalance)),
    generators = Seq(committedGenerator, thisNodeAcc)
  ) { d =>
    d.wallet.generateNewAccounts(1)

    log.debug("Append block2")
    d.appender.appendBlock(d.createBlock(strictTime = true, generator = committedGenerator))
    d.appendMicroBlock(
      TxHelpers.commitToGeneration(Height(3), sender = committedGenerator),
      TxHelpers.commitToGeneration(Height(3), sender = thisNodeAcc)
    )

    log.debug("Append block3 with invalid state hash and challenge")
    val invalidStateHash = ByteStr.fill(DigestLength)(1)
    val invalidBlock = d.createBlock(
      strictTime = true,
      generator = committedGenerator,
      stateHash = Some(Some(invalidStateHash)),
      timestamp = Some(d.nextBlockTime(committedGenerator, defaultVrfKey) + 1L) // HACK: challenger block timestamp will be better
    )
    d.appender.appendBlock(invalidBlock, requireAppended = false)

    withClue("Challenged: ") {
      d.blockchain.height shouldBe 3
      d.lastBlockId should not be invalidBlock.id()
      d.lastBlock.header.generator.toAddress shouldBe thisNodeAcc.toAddress
      d.lastBlock.header.challengedHeader should not be empty
    }

    withClue("Empty finalization header: ") {
      d.lastBlock.header.finalizationVoting shouldBe empty
    }
  }
}
