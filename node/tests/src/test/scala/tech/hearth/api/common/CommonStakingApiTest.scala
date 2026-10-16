package tech.hearth.api.common

import tech.hearth.db.WithDomain
import tech.hearth.db.WithState.AddrWithBalance
import tech.hearth.history.Domain
import tech.hearth.state.{AddressPayout, GenerationPeriod, Height, PeriodPayout, StakerPayout}
import tech.hearth.test.*
import tech.hearth.test.StakingFixtures.*
import tech.hearth.transaction.TxHelpers

/** Payouts read back after the real append path stored them: a StakeTransaction, the boundary block that pays it, and
  * then storage. The work a payout splits is injected (see TestStorageFactory), since nothing a fixture can append
  * produces any.
  */
class CommonStakingApiTest extends FreeSpec with WithDomain {
  private val miner  = TxHelpers.defaultSigner
  private val staker = TxHelpers.signer(11)

  private val second = FirstPeriod.next
  private val third  = second.next

  private def withStakingDomain[A](work: Map[GenerationPeriod, Long])(f: Domain => A): A =
    withDomain(
      stakingSettings(),
      AddrWithBalance.enoughBalances(miner, staker),
      assets = Seq(CredGenesis),
      work = work.map { case (period, amount) => (miner.toAddress, period) -> amount }
    )(f)

  private def api(d: Domain): CommonStakingApi = CommonStakingApi(d.rdb, d.blockchain)

  private def expectedPayout(period: GenerationPeriod, credited: Long): PeriodPayout =
    PeriodPayout(period, CredAsset, credited, BigInt(100.hearth), Seq(StakerPayout(staker.toAddress, 100.hearth, credited)))

  "CommonStakingApi" - {
    "reads back a payout once the block that paid it is stored" in withStakingDomain(Map(second -> 400L)) { d =>
      stakeForNextPeriod(d, staker, 100.hearth)
      crossPeriodBoundary(d) // the stake is in force for [5, 8]
      crossPeriodBoundary(d) // block 9 pays for [5, 8]
      d.appendBlock()        // and is no longer the liquid block

      api(d).payout(second) shouldBe Some(expectedPayout(second, 400L))
    }

    "reads a payout from the liquid block that paid it" in withStakingDomain(Map(second -> 400L)) { d =>
      stakeForNextPeriod(d, staker, 100.hearth)
      crossPeriodBoundary(d)
      crossPeriodBoundary(d)

      api(d).payout(second) shouldBe Some(expectedPayout(second, 400L))
    }

    "forgets a payout when the block that paid it is rolled back" in withStakingDomain(Map(second -> 400L)) { d =>
      stakeForNextPeriod(d, staker, 100.hearth)
      crossPeriodBoundary(d)
      crossPeriodBoundary(d)
      d.appendBlock()

      d.rollbackTo(second.end.toInt)

      api(d).payout(second) shouldBe None
      api(d).payouts(staker.toAddress, Height(1), Height(Int.MaxValue)) shouldBe empty
    }

    "lists what an address was paid, oldest period first" in withStakingDomain(Map(second -> 400L, third -> 200L)) { d =>
      stakeForNextPeriod(d, staker, 100.hearth)
      crossPeriodBoundary(d)
      crossPeriodBoundary(d) // pays for [5, 8]
      crossPeriodBoundary(d) // pays for [9, 12], the stake having stayed in force
      d.appendBlock()

      api(d).payouts(staker.toAddress, Height(1), Height(Int.MaxValue)) shouldBe Seq(
        AddressPayout(second, CredAsset, 100.hearth, 400L),
        AddressPayout(third, CredAsset, 100.hearth, 200L)
      )
    }

    "includes the liquid block's payout in an address's history" in withStakingDomain(Map(second -> 400L, third -> 200L)) { d =>
      stakeForNextPeriod(d, staker, 100.hearth)
      crossPeriodBoundary(d)
      crossPeriodBoundary(d)
      crossPeriodBoundary(d) // block 13 pays for [9, 12] and is still liquid

      api(d).payouts(staker.toAddress, Height(1), Height(Int.MaxValue)).map(_.period) shouldBe Seq(second, third)
    }

    "lists only the periods starting within the requested range" in withStakingDomain(Map(second -> 400L, third -> 200L)) { d =>
      stakeForNextPeriod(d, staker, 100.hearth)
      crossPeriodBoundary(d)
      crossPeriodBoundary(d)
      crossPeriodBoundary(d)
      d.appendBlock()

      api(d).payouts(staker.toAddress, third.start, third.start).map(_.period) shouldBe Seq(third)
      api(d).payouts(staker.toAddress, Height(1), third.start - 1).map(_.period) shouldBe Seq(second)
    }

    // Why payouts are recorded rather than recomputed
    "keeps a period's payout after the staker released and the stake index was pruned" in withStakingDomain(Map(second -> 400L)) { d =>
      stakeForNextPeriod(d, staker, 100.hearth)
      crossPeriodBoundary(d)
      stakeForNextPeriod(d, staker, 0) // released from [9, 12] on
      crossPeriodBoundary(d)           // pays for [5, 8]
      crossPeriodBoundary(d)           // prunes the released stake
      d.appendBlock()

      d.blockchain.stakes(second) shouldBe empty
      api(d).payout(second) shouldBe Some(expectedPayout(second, 400L))
    }
  }
}
