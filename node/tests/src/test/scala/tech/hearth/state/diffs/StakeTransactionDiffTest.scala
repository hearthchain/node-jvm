package tech.hearth.state.diffs

import tech.hearth.TestValues
import tech.hearth.account.{Address, PublicKey}
import tech.hearth.db.WithDomain
import tech.hearth.db.WithState.AddrWithBalance
import tech.hearth.history.Domain
import tech.hearth.state.{GenerationPeriod, Height}
import tech.hearth.test.*
import tech.hearth.test.DomainPresets.*
import tech.hearth.transaction.{Proofs, StakeTransaction, Transaction, TxHelpers}

import scala.collection.mutable

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

  // Long enough that the block after a boundary block is still inside the same period: a transaction is validated
  // against the period of the block carrying it, so both the stake transactions here and the commitment below have
  // to land before the next boundary, not on it. Periods are [1, 4], [5, 8], [9, 12], ...
  private val PeriodLength = 4

  private val StakerBalance = 1000.hearth

  private def withStakeDomain[A](f: Domain => A): A =
    withDomain(
      DeterministicFinality.configure(_.copy(generationPeriodLength = PeriodLength)),
      AddrWithBalance.enoughBalances(miner) :+ AddrWithBalance(address, StakerBalance)
    )(f)

  private def period(d: Domain): GenerationPeriod = d.blockchain.currentGenerationPeriod.get

  private def stakedFor(d: Domain, p: GenerationPeriod): Seq[(Address, Long)] =
    d.blockchain.stakes(p).map(s => s.address -> s.amount)

  private def staked(d: Domain): Long = d.blockchain.hearthPortfolio(address).staked

  /** Advances to the first block of the next period, committing the sender as its generator first so that it can
    * actually mine there. Leaves the chain on the boundary block itself, which is where the payout and the
    * carry-forward both happen.
    */
  private def crossPeriodBoundary(d: Domain, afterEachBlock: Domain => Unit = _ => ()): Unit = {
    val nextStart = d.blockchain.currentGenerationPeriod.get.next.start
    // The commitment goes in the very next block, which PeriodLength guarantees is still inside the current period -
    // CommitToGenerationTransactionDiff would reject it in the boundary block itself, where "the next period" has
    // already moved on by one.
    d.appendBlock(TxHelpers.commitToGeneration(nextStart, miner))
    afterEachBlock(d)
    while (d.blockchain.height < nextStart.toInt) {
      d.appendBlock()
      afterEachBlock(d)
    }
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

    // In separate blocks, so each restatement reads the previous one back from state
    "moves the lock with every restatement of a stake that is not yet in force" in withStakeDomain { d =>
      def restake(amount: Long): Unit = {
        d.appendBlock(TxHelpers.stake(sender, periodStart = period(d).next.start, amount = amount))

        val portfolio = d.blockchain.hearthPortfolio(address)
        stakedFor(d, period(d).next) shouldBe Seq(address -> amount)
        portfolio.staked shouldBe amount
        portfolio.spendableBalance shouldBe (portfolio.balance - amount)
        portfolio.effectiveBalance(isBanned = false) shouldBe Right(portfolio.balance - amount)
      }

      restake(200.hearth)
      d.blockchain.generatingBalance(address) shouldBe (d.blockchain.balance(address) - 200.hearth)

      restake(300.hearth)
      val generatingAt300 = d.blockchain.balance(address) - 300.hearth
      d.blockchain.generatingBalance(address) shouldBe generatingAt300

      restake(100.hearth)
      d.blockchain.generatingBalance(address) shouldBe generatingAt300
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

    // A stake is a lock like a generation deposit: it costs forging weight from the block that locks it, not from
    // the period it starts earning in.
    "takes a stake out of the sender's generating balance as soon as it is locked" in withStakeDomain { d =>
      d.appendBlock(TxHelpers.stake(sender, periodStart = period(d).next.start, amount = 10.hearth))

      staked(d) shouldBe 10.hearth
      // The sender only ever pays fees, so its smallest balance in the window is its current one
      d.blockchain.generatingBalance(address) shouldBe (d.blockchain.balance(address) - 10.hearth)
    }

    "keeps it out once the stake is earning" in withStakeDomain { d =>
      d.appendBlock(TxHelpers.stake(sender, periodStart = period(d).next.start, amount = 10.hearth))

      crossPeriodBoundary(d)

      d.blockchain.generatingBalance(address) shouldBe (d.blockchain.balance(address) - 10.hearth)
    }

    // A release unlocks at the next period's start, and from there the freed HRTH matures through the window like
    // any other credit.
    "keeps a released stake out of the generating balance until the window has passed it" in withStakeDomain { d =>
      d.appendBlock(TxHelpers.stake(sender, periodStart = period(d).next.start, amount = 10.hearth))
      crossPeriodBoundary(d)
      d.appendBlock(TxHelpers.stake(sender, periodStart = period(d).next.start, amount = 0))
      crossPeriodBoundary(d)

      staked(d) shouldBe 0L
      d.blockchain.generatingBalance(address) shouldBe (d.blockchain.balance(address) - 10.hearth)
    }

    // Periods [1, 4], [5, 8], [9, 12]: locked by the stake at 2, released by the period start at 9. The release
    // at 6 writes the stake key but moves no lock, and 9 changes the lock with no balance change at all.
    "records the lock in balance snapshots from the block that sets it to the period start that releases it" in withStakeDomain { d =>
      d.appendBlock(TxHelpers.stake(sender, periodStart = period(d).next.start, amount = 10.hearth))
      crossPeriodBoundary(d)
      d.appendBlock(TxHelpers.stake(sender, periodStart = period(d).next.start, amount = 0))
      crossPeriodBoundary(d)
      d.appendBlock()

      d.rocksDBWriter.height shouldBe 9
      d.rocksDBWriter.balanceSnapshots(address, 1, None).map(s => s.height.toInt -> s.staked) shouldBe Seq(
        9 -> 0L,
        6 -> 10.hearth,
        2 -> 10.hearth,
        1 -> 0L
      )
      // The liquid block's own snapshot comes from the lock as of that block
      d.blockchain.balanceSnapshots(address, 1, None).head.staked shouldBe 0L
    }

    // The tip's lock comes from Blockchain.lockedStake and every earlier height's from the stored stake history
    "derives the same lock from stored history as it did for the liquid block" in withStakeDomain { d =>
      val atTip                                   = mutable.Map.empty[Int, Long]
      def record(d: Domain): Unit                 = atTip(d.blockchain.height) = staked(d)
      def append(txs: Transaction*): Unit         = { d.appendBlock(txs*); record(d) }
      def restake(amount: Long): StakeTransaction = TxHelpers.stake(sender, periodStart = period(d).next.start, amount = amount)

      record(d)
      append(restake(200.hearth))
      append(restake(100.hearth))
      crossPeriodBoundary(d, record)
      append(restake(50.hearth))
      crossPeriodBoundary(d, record)
      append(restake(0))
      append()

      (1 to d.rocksDBWriter.height).foreach { h =>
        val id = d.blockchain.blockHeader(h).get.id()
        withClue(s"height $h: ")(d.rocksDBWriter.balanceSnapshots(address, h, Some(id)).head.staked shouldBe atTip(h))
      }
    }

    "keeps charging the larger amount after a stake is lowered mid-period" in withStakeDomain { d =>
      d.appendBlock(TxHelpers.stake(sender, periodStart = period(d).next.start, amount = 30.hearth))
      crossPeriodBoundary(d)

      d.appendBlock(TxHelpers.stake(sender, periodStart = period(d).next.start, amount = 10.hearth))
      d.blockchain.generatingBalance(address) shouldBe (d.blockchain.balance(address) - 30.hearth)

      // Unlocked down to 10 now, but the window still holds the heights where 30 was locked
      crossPeriodBoundary(d)
      staked(d) shouldBe 10.hearth
      d.blockchain.generatingBalance(address) shouldBe (d.blockchain.balance(address) - 30.hearth)
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

    // The reason the candidate index keeps a released address for one more period: the payout at the next boundary
    // is for the period it was still staked in, so dropping it the moment it released would silently underpay it.
    "still enumerates a stake released mid-period for the period it was staked in" in withStakeDomain { d =>
      d.appendBlock(TxHelpers.stake(sender, periodStart = period(d).next.start, amount = 10.hearth))
      crossPeriodBoundary(d)
      val staking = period(d)

      d.appendBlock(TxHelpers.stake(sender, periodStart = staking.next.start, amount = 0))

      // Zero from the next period on, but still staked for the one the payout is about to settle
      stakedFor(d, staking) shouldBe Seq(address -> 10.hearth)
      stakedFor(d, staking.next) shouldBe empty
    }

    "stops enumerating it once that period has passed" in withStakeDomain { d =>
      d.appendBlock(TxHelpers.stake(sender, periodStart = period(d).next.start, amount = 10.hearth))
      crossPeriodBoundary(d)
      d.appendBlock(TxHelpers.stake(sender, periodStart = period(d).next.start, amount = 0))
      crossPeriodBoundary(d)

      stakedFor(d, period(d)) shouldBe empty
      staked(d) shouldBe 0L
    }

    "rejects a release from an address with nothing staked" in withStakeDomain { d =>
      d.appendBlockE(TxHelpers.stake(sender, periodStart = period(d).next.start, amount = 0)) should produce("nothing staked to release")
    }

    // A boundary block is where the payout runs, so it is worth pinning that rolling one back leaves no stake
    // state behind - and, since a stake is a balance now, that the boundary wrote none of its own to begin with.
    "restores the stake on rollback of a boundary block that also carries one" in withStakeDomain { d =>
      d.appendBlock(TxHelpers.stake(sender, periodStart = period(d).next.start, amount = 10.hearth))
      crossPeriodBoundary(d)

      val starting = period(d).next
      d.appendBlock(TxHelpers.commitToGeneration(starting.start, miner))
      while (d.blockchain.height < starting.start.toInt - 1) d.appendBlock()
      val beforeBoundary = d.blockchain.lastBlockId.get

      d.appendBlock(TxHelpers.stake(sender, periodStart = starting.next.start, amount = 30.hearth))
      d.blockchain.height shouldBe starting.start.toInt
      stakedFor(d, starting) shouldBe Seq(address -> 10.hearth)
      stakedFor(d, starting.next) shouldBe Seq(address -> 30.hearth)

      d.rollbackTo(beforeBoundary)
      // The 10 is still in force everywhere: it was set a period ago and nothing at the boundary touched it
      stakedFor(d, starting) shouldBe Seq(address -> 10.hearth)
      stakedFor(d, starting.next) shouldBe Seq(address -> 10.hearth)
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
