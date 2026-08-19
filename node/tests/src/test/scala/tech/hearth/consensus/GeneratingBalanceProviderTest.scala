package tech.hearth.consensus

import tech.hearth.account.Address
import tech.hearth.common.state.ByteStr
import tech.hearth.db.WithDomain
import tech.hearth.db.WithState.AddrWithBalance
import tech.hearth.state.{Blockchain, CommittedGenerator, GenerationPeriod, Height}
import tech.hearth.test.*
import tech.hearth.test.DomainPresets.*
import tech.hearth.transaction.TxHelpers

/** GeneratingBalanceProvider.balance's workBoost wiring (see CLAUDE.md's "workBoost") is exercised by wrapping a
  * real domain's Blockchain (so effectiveBalance/generationPeriodOf are the genuine ones) with a Blockchain that
  * injects committedGenerators/workDone for a specific period, the same "inject the minimal necessary state
  * directly" technique SettleTransactionDiffTest/ReserveTransactionDiffTest use - rather than driving the domain
  * through a real period boundary (which needs a real CommitToGenerationTransaction committing a generator for a
  * *later* period, well beyond the scope of what this is testing). generationPeriodLength = 1 makes the genesis
  * period exactly [1, 1], so its predecessor (GenerationPeriod.prev) is still well-defined and a height-1 balance
  * lookup already draws on it - no need to advance the chain past genesis at all.
  */
class GeneratingBalanceProviderTest extends FreeSpec with WithDomain {
  private val sender    = TxHelpers.defaultSigner
  private val validator = TxHelpers.secondSigner.toAddress
  private val other     = TxHelpers.signer(12).toAddress

  private val workPeriod = GenerationPeriod(Height(1), 1)

  private def committedGenerator(address: Address): CommittedGenerator =
    CommittedGenerator(address, TxHelpers.defaultBlsKey.publicKey, ByteStr.empty)

  private def withCommitteeWork(blockchain: Blockchain, committee: Seq[Address], work: Map[Address, Long]): Blockchain =
    new Blockchain {
      export blockchain.{committedGenerators as _, workDone as _, *}
      override def committedGenerators(at: GenerationPeriod): IndexedSeq[CommittedGenerator] =
        if (at == workPeriod) committee.map(committedGenerator).toIndexedSeq else blockchain.committedGenerators(at)
      override def workDone(v: Address, p: GenerationPeriod): Long =
        if (p == workPeriod) work.getOrElse(v, 0L) else blockchain.workDone(v, p)
    }

  "GeneratingBalanceProvider.balance" - {
    "boosts a validator's generating balance using the previous period's tracked work, relative to the committee total" in withDomain(
      DeterministicFinality.configure(_.copy(generationPeriodLength = 1)),
      AddrWithBalance.enoughBalances(sender) :+ AddrWithBalance(validator, 100.hearth) :+ AddrWithBalance(other, 100.hearth)
    ) { d =>
      val rawBalance = d.blockchain.generatingBalance(validator)

      val boosted = withCommitteeWork(d.blockchain, Seq(validator, other), Map(validator -> 60L, other -> 40L)).generatingBalance(validator)

      boosted should be > rawBalance
      boosted shouldBe WorkBoost(rawBalance, 60L, 100L)
    }

    "applies no boost to a validator with no tracked work, even when others in the committee have some" in withDomain(
      DeterministicFinality.configure(_.copy(generationPeriodLength = 1)),
      AddrWithBalance.enoughBalances(sender) :+ AddrWithBalance(validator, 100.hearth) :+ AddrWithBalance(other, 100.hearth)
    ) { d =>
      val rawBalance = d.blockchain.generatingBalance(validator)

      withCommitteeWork(d.blockchain, Seq(validator, other), Map(other -> 100L)).generatingBalance(validator) shouldBe rawBalance
    }

    "applies no boost when nothing was tracked in the previous period at all" in withDomain(
      DeterministicFinality.configure(_.copy(generationPeriodLength = 1)),
      AddrWithBalance.enoughBalances(sender) :+ AddrWithBalance(validator, 100.hearth)
    ) { d =>
      // No override applied: even the real committee (just defaultSigner, genesis-committed) has tracked no work,
      // so totalWork is 0 and the boosted value has to equal the raw ingredient GeneratingBalanceProvider itself
      // combines from - bypassing GeneratingBalanceProvider entirely confirms nothing was silently added.
      d.blockchain.generatingBalance(validator) shouldBe d.blockchain.effectiveBalance(validator, 1000, None)
    }
  }
}
