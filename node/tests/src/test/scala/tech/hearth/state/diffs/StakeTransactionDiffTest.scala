package tech.hearth.state.diffs

import tech.hearth.TestValues
import tech.hearth.account.{Address, PublicKey}
import tech.hearth.db.WithDomain
import tech.hearth.db.WithState.AddrWithBalance
import tech.hearth.history.Domain
import tech.hearth.state.{GenerationPeriod, Height}
import tech.hearth.test.*
import tech.hearth.test.DomainPresets.*
import tech.hearth.transaction.{Proofs, StakeTransaction, TxHelpers}

/** The transaction half of staking: what a StakeTransaction records, when the HRTH it names is locked, and when it
  * is released again. The boundary half - what the stake earns, and how it reaches the next period - is
  * StakingPayoutTest's.
  *
  * Everything here goes through a real domain, since none of it needs state a transaction cannot produce.
  */
class StakeTransactionDiffTest extends FreeSpec with WithDomain {
  // The miner and the staker are deliberately different accounts: a miner's balance grows by the block reward and
  // its fee share on every block it forges, which would make every balance assertion below depend on how many
  // blocks a case happens to append.
  private val miner = TxHelpers.defaultSigner

  private val sender  = TxHelpers.signer(11)
  private val address = sender.toAddress

  // Starts poor and is credited during the test, so its windowed effective balance lags far behind what it holds
  private val newcomer        = TxHelpers.signer(12)
  private val newcomerAddress = newcomer.toAddress

  // Long enough that the block after a boundary block is still inside the same period: a transaction is validated
  // against the period of the block carrying it, so both the stake transactions here and the commitment below have
  // to land before the next boundary, not on it. Periods are [1, 4], [5, 8], [9, 12], ...
  private val PeriodLength = 4

  private val StakerBalance = 1000.hearth

  private def withStakeDomain[A](f: Domain => A): A =
    withDomain(
      DeterministicFinality.configure(_.copy(generationPeriodLength = PeriodLength)),
      AddrWithBalance.enoughBalances(miner) :+ AddrWithBalance(address, StakerBalance) :+ AddrWithBalance(newcomerAddress, 1.hearth)
    )(f)

  private def period(d: Domain): GenerationPeriod = d.blockchain.currentGenerationPeriod.get

  private def stakedFor(d: Domain, p: GenerationPeriod): Seq[(Address, Long)] =
    d.blockchain.stakes(p).map(s => s.address -> s.amount)

  private def staked(d: Domain): Long = d.blockchain.hearthPortfolio(address).staked

  /** Advances to the first block of the next period, committing the sender as its generator first so that it can
    * actually mine there. Leaves the chain on the boundary block itself, which is where the payout and the
    * carry-forward both happen.
    */
  private def crossPeriodBoundary(d: Domain): Unit = {
    val nextStart = d.blockchain.currentGenerationPeriod.get.next.start
    // The commitment goes in the very next block, which PeriodLength guarantees is still inside the current period -
    // CommitToGenerationTransactionDiff would reject it in the boundary block itself, where "the next period" has
    // already moved on by one.
    d.appendBlock(TxHelpers.commitToGeneration(nextStart, miner))
    while (d.blockchain.height < nextStart.toInt) d.appendBlock()
  }

  "StakeTransactionDiff" - {
    "rejects a periodStart that is not the next period's start" in withStakeDomain { d =>
      val next = period(d).next.start

      d.appendBlockE(TxHelpers.stake(sender, periodStart = Height(1))) should produce("Expected the next period start height")
      d.appendBlockE(TxHelpers.stake(sender, periodStart = next + 2)) should produce("Expected the next period start height")
    }

    "rejects a negative amount before the transaction is even built" in {
      StakeTransaction.create(
        PublicKey(sender.publicKey),
        periodStart = Height(3),
        amount = -1,
        fee = TestValues.fee,
        timestamp = TxHelpers.timestamp,
        proofs = Proofs.empty
      ) should produce("NegativeAmount")
    }

    "files the stake under the next period, leaving the current one alone" in withStakeDomain { d =>
      d.appendBlock(TxHelpers.stake(sender, periodStart = period(d).next.start, amount = 10.hearth))

      stakedFor(d, period(d)) shouldBe empty
      stakedFor(d, period(d).next) shouldBe Seq(address -> 10.hearth)
    }

    "locks the HRTH immediately, before the stake is earning" in withStakeDomain { d =>
      val tx = TxHelpers.stake(sender, periodStart = period(d).next.start, amount = 10.hearth)
      d.appendBlock(tx)

      val portfolio = d.blockchain.hearthPortfolio(address)
      portfolio.staked shouldBe 10.hearth
      // Still fully owned: only the fee leaves the balance, the stake comes out of what is spendable
      portfolio.balance shouldBe (StakerBalance - tx.fee.value)
      portfolio.spendableBalance shouldBe (portfolio.balance - 10.hearth)
    }

    "lets the last transaction of a period win" in withStakeDomain { d =>
      val next = period(d).next.start
      d.appendBlock(
        TxHelpers.stake(sender, periodStart = next, amount = 10.hearth),
        TxHelpers.stake(sender, periodStart = next, amount = 25.hearth),
        TxHelpers.stake(sender, periodStart = next, amount = 7.hearth)
      )

      stakedFor(d, period(d).next) shouldBe Seq(address -> 7.hearth)
      // The lock follows the last one too, rather than the largest one seen along the way
      staked(d) shouldBe 7.hearth
    }

    "rejects staking more than the sender can cover" in withStakeDomain { d =>
      val amount = d.blockchain.balance(address)

      d.appendBlockE(TxHelpers.stake(sender, periodStart = period(d).next.start, amount = amount)) should produce("not enough funds to stake")
    }

    "stops the sender spending what it has staked" in withStakeDomain { d =>
      d.appendBlock(TxHelpers.stake(sender, periodStart = period(d).next.start, amount = 10.hearth))

      // Everything that is left, fee included, so only the staked HRTH could possibly cover it
      val spendable = d.blockchain.hearthPortfolio(address).spendableBalance
      d.appendBlockE(TxHelpers.transfer(sender, amount = spendable)) should produce("trying to spend a stake")
    }

    // Forging weight follows the period's own stake, so it moves only where the committee itself does. Were it to
    // follow the lock, any committed generator could zero its own generating balance mid-period with one cheap
    // transaction, which is a lever over EndorsementFilter's quorum denominator.
    "leaves the sender's generating balance alone until the stake is earning" in withStakeDomain { d =>
      d.appendBlock(TxHelpers.stake(sender, periodStart = period(d).next.start, amount = 10.hearth))

      staked(d) shouldBe 10.hearth
      d.blockchain.generatingBalance(address) shouldBe d.blockchain.effectiveBalance(address, 1000, None)
    }

    "takes the stake out of the sender's generating balance from the period it earns in" in withStakeDomain { d =>
      d.appendBlock(TxHelpers.stake(sender, periodStart = period(d).next.start, amount = 10.hearth))

      crossPeriodBoundary(d)

      // Compared against the windowed effective balance the provider itself starts from, so that the assertion pins
      // the subtraction rather than a particular balance the fee and the window happen to produce
      d.blockchain.generatingBalance(address) shouldBe (d.blockchain.effectiveBalance(address, 1000, None) - 10.hearth)
    }

    // effectiveBalance is a minimum over a 1000-block window while the stake subtracted from it is the current
    // period's, so an address credited inside that window and staking most of it drives the difference negative.
    // Without the clamp that negative would reach consensus as a generating balance.
    "floors the generating balance at zero rather than going negative" in withStakeDomain { d =>
      val next = period(d).next.start
      d.appendBlock(TxHelpers.transfer(miner, to = newcomerAddress, amount = 500.hearth))
      d.appendBlock(TxHelpers.stake(newcomer, periodStart = next, amount = 400.hearth))

      crossPeriodBoundary(d)

      // The window still remembers the 1 HRTH it started with, so the stake dwarfs the windowed balance
      d.blockchain.stakedForPeriod(newcomerAddress) should be > d.blockchain.effectiveBalance(newcomerAddress, 1000, None)
      d.blockchain.generatingBalance(newcomerAddress) shouldBe 0L
    }

    "starts earning at the start of the next period" in withStakeDomain { d =>
      d.appendBlock(TxHelpers.stake(sender, periodStart = period(d).next.start, amount = 10.hearth))

      crossPeriodBoundary(d)

      stakedFor(d, period(d)) shouldBe Seq(address -> 10.hearth)
      staked(d) shouldBe 10.hearth
    }

    "keeps a stake in place across further periods with no transaction to renew it" in withStakeDomain { d =>
      d.appendBlock(TxHelpers.stake(sender, periodStart = period(d).next.start, amount = 10.hearth))

      crossPeriodBoundary(d)
      crossPeriodBoundary(d)

      // Carried into each new period by StakingPayout, without a transaction to restate it
      stakedFor(d, period(d)) shouldBe Seq(address -> 10.hearth)
      staked(d) shouldBe 10.hearth
    }

    "raising a stake locks the larger amount at once" in withStakeDomain { d =>
      d.appendBlock(TxHelpers.stake(sender, periodStart = period(d).next.start, amount = 10.hearth))
      crossPeriodBoundary(d)

      d.appendBlock(TxHelpers.stake(sender, periodStart = period(d).next.start, amount = 30.hearth))

      stakedFor(d, period(d)) shouldBe Seq(address -> 10.hearth)
      stakedFor(d, period(d).next) shouldBe Seq(address -> 30.hearth)
      staked(d) shouldBe 30.hearth
    }

    "lowering a stake frees nothing until the next period starts" in withStakeDomain { d =>
      d.appendBlock(TxHelpers.stake(sender, periodStart = period(d).next.start, amount = 30.hearth))
      crossPeriodBoundary(d)

      d.appendBlock(TxHelpers.stake(sender, periodStart = period(d).next.start, amount = 10.hearth))
      stakedFor(d, period(d)) shouldBe Seq(address -> 30.hearth)
      staked(d) shouldBe 30.hearth

      crossPeriodBoundary(d)
      stakedFor(d, period(d)) shouldBe Seq(address -> 10.hearth)
      staked(d) shouldBe 10.hearth
    }

    "releases everything at the next period's start when the amount is 0" in withStakeDomain { d =>
      d.appendBlock(TxHelpers.stake(sender, periodStart = period(d).next.start, amount = 10.hearth))
      crossPeriodBoundary(d)

      d.appendBlock(TxHelpers.stake(sender, periodStart = period(d).next.start, amount = 0))
      // Still locked: the stake it zeroes has one more period to earn in
      staked(d) shouldBe 10.hearth

      crossPeriodBoundary(d)
      staked(d) shouldBe 0L
      // And it is not carried forward again, so the next period's set no longer holds it
      crossPeriodBoundary(d)
      stakedFor(d, period(d)) shouldBe empty
    }

    "rejects a release from an address with nothing staked" in withStakeDomain { d =>
      d.appendBlockE(TxHelpers.stake(sender, periodStart = period(d).next.start, amount = 0)) should produce("nothing staked to release")
    }

    // A boundary block is the only one that writes two periods at once - StakingPayout's carry-forward for the
    // period it starts, and its own transactions for the one after - so it is the only block whose rollback has to
    // unwind both. The carry-forward only fires for an address with no entry yet for the starting period, which is
    // why the stake has to be a period old before the boundary under test.
    "restores both periods on rollback of a boundary block that also carries a stake" in withStakeDomain { d =>
      d.appendBlock(TxHelpers.stake(sender, periodStart = period(d).next.start, amount = 10.hearth))
      crossPeriodBoundary(d)

      // Now staked for the period the chain is in, with nothing yet filed for the next one: the boundary below is
      // the first at which the carry-forward has anything to do
      val starting = period(d).next
      stakedFor(d, period(d)) shouldBe Seq(address -> 10.hearth)
      stakedFor(d, starting) shouldBe empty

      d.appendBlock(TxHelpers.commitToGeneration(starting.start, miner))
      while (d.blockchain.height < starting.start.toInt - 1) d.appendBlock()
      val beforeBoundary = d.blockchain.lastBlockId.get

      // The boundary block: the carry-forward files 10 for `starting`, the transaction files 30 for the one after
      d.appendBlock(TxHelpers.stake(sender, periodStart = starting.next.start, amount = 30.hearth))
      d.blockchain.height shouldBe starting.start.toInt
      stakedFor(d, starting) shouldBe Seq(address -> 10.hearth)
      stakedFor(d, starting.next) shouldBe Seq(address -> 30.hearth)

      d.rollbackTo(beforeBoundary)
      stakedFor(d, starting) shouldBe empty
      stakedFor(d, starting.next) shouldBe empty
      staked(d) shouldBe 10.hearth
    }

    "restores the stake and the lock on rollback" in withStakeDomain { d =>
      val next = period(d).next.start
      d.appendBlock()
      val beforeStake = d.blockchain.lastBlockId.get

      d.appendBlock(TxHelpers.stake(sender, periodStart = next, amount = 10.hearth))
      stakedFor(d, period(d).next) shouldBe Seq(address -> 10.hearth)

      d.rollbackTo(beforeStake)
      stakedFor(d, period(d).next) shouldBe empty
      staked(d) shouldBe 0L
    }
  }
}
