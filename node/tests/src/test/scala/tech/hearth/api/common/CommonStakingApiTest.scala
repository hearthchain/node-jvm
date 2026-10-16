package tech.hearth.api.common

import tech.hearth.TestValues
import tech.hearth.common.state.ByteStr
import tech.hearth.db.WithDomain
import tech.hearth.db.WithState.AddrWithBalance
import tech.hearth.history.Domain
import tech.hearth.settings.GenesisAssetSettings
import tech.hearth.state.{AddressPayout, GenerationPeriod, Height, PeriodPayout, StakerPayout}
import tech.hearth.test.*
import tech.hearth.test.DomainPresets.*
import tech.hearth.transaction.Asset.IssuedAsset
import tech.hearth.transaction.TxHelpers

/** Payouts read back after the real append path stored them: a StakeTransaction, the boundary block that pays it, and
  * then storage. The work a payout splits is injected (see TestStorageFactory), since nothing a fixture can append
  * produces any.
  */
class CommonStakingApiTest extends FreeSpec with WithDomain {
  private val miner  = TxHelpers.defaultSigner
  private val staker = TxHelpers.signer(11)

  private val credAsset = IssuedAsset(ByteStr.fill(32)(3))

  // Periods are [1, 4], [5, 8], [9, 12], ...: long enough that the block after a boundary block is still inside the
  // same period, which the commitment and the stake both need (see StakeTransactionDiffTest)
  private val PeriodLength = 4
  private val first        = GenerationPeriod(Height(1), PeriodLength)
  private val second       = first.next
  private val third        = second.next

  private def withStakingDomain[A](work: Map[GenerationPeriod, Long])(f: Domain => A): A =
    withDomain(
      DeterministicFinality.configure(_.copy(generationPeriodLength = PeriodLength, credAsset = Some(credAsset.id.toString))),
      AddrWithBalance.enoughBalances(miner, staker),
      assets = Seq(GenesisAssetSettings(credAsset.id.toString, "CRED", decimals = 0, quantity = 0, minFee = TestValues.fee)),
      work = work.map { case (period, amount) => (miner.toAddress, period) -> amount }
    )(f)

  private def api(d: Domain): CommonStakingApi = CommonStakingApi(d.rdb, d.blockchain)

  /** Moves to the first block of the next period, committing the miner for it on the way. */
  private def crossPeriodBoundary(d: Domain): Unit = {
    val nextStart = d.blockchain.currentGenerationPeriod.get.next.start
    d.appendBlock(TxHelpers.commitToGeneration(nextStart, miner))
    while (d.blockchain.height < nextStart.toInt) d.appendBlock()
  }

  private def stakeForNextPeriod(d: Domain, amount: Long): Unit =
    d.appendBlock(TxHelpers.stake(staker, periodStart = d.blockchain.currentGenerationPeriod.get.next.start, amount = amount))

  private def expectedPayout(period: GenerationPeriod, credited: Long): PeriodPayout =
    PeriodPayout(period, credAsset, credited, BigInt(100.hearth), Seq(StakerPayout(staker.toAddress, 100.hearth, credited)))

  "CommonStakingApi" - {
    "reads back a payout once the block that paid it is stored" in withStakingDomain(Map(second -> 400L)) { d =>
      stakeForNextPeriod(d, 100.hearth)
      crossPeriodBoundary(d) // the stake is in force for [5, 8]
      crossPeriodBoundary(d) // block 9 pays for [5, 8]
      d.appendBlock()        // and is no longer the liquid block

      api(d).payout(second) shouldBe Some(expectedPayout(second, 400L))
    }

    "reads a payout from the liquid block that paid it" in withStakingDomain(Map(second -> 400L)) { d =>
      stakeForNextPeriod(d, 100.hearth)
      crossPeriodBoundary(d)
      crossPeriodBoundary(d)

      api(d).payout(second) shouldBe Some(expectedPayout(second, 400L))
    }

    "forgets a payout when the block that paid it is rolled back" in withStakingDomain(Map(second -> 400L)) { d =>
      stakeForNextPeriod(d, 100.hearth)
      crossPeriodBoundary(d)
      crossPeriodBoundary(d)
      d.appendBlock()

      d.rollbackTo(second.end.toInt)

      api(d).payout(second) shouldBe None
      api(d).payouts(staker.toAddress, Height(1), Height(Int.MaxValue)) shouldBe empty
    }

    "lists what an address was paid, oldest period first" in withStakingDomain(Map(second -> 400L, third -> 200L)) { d =>
      stakeForNextPeriod(d, 100.hearth)
      crossPeriodBoundary(d)
      crossPeriodBoundary(d) // pays for [5, 8]
      crossPeriodBoundary(d) // pays for [9, 12], the stake having stayed in force
      d.appendBlock()

      api(d).payouts(staker.toAddress, Height(1), Height(Int.MaxValue)) shouldBe Seq(
        AddressPayout(second, credAsset, 100.hearth, 400L),
        AddressPayout(third, credAsset, 100.hearth, 200L)
      )
    }

    "includes the liquid block's payout in an address's history" in withStakingDomain(Map(second -> 400L, third -> 200L)) { d =>
      stakeForNextPeriod(d, 100.hearth)
      crossPeriodBoundary(d)
      crossPeriodBoundary(d)
      crossPeriodBoundary(d) // block 13 pays for [9, 12] and is still liquid

      api(d).payouts(staker.toAddress, Height(1), Height(Int.MaxValue)).map(_.period) shouldBe Seq(second, third)
    }

    "lists only the periods starting within the requested range" in withStakingDomain(Map(second -> 400L, third -> 200L)) { d =>
      stakeForNextPeriod(d, 100.hearth)
      crossPeriodBoundary(d)
      crossPeriodBoundary(d)
      crossPeriodBoundary(d)
      d.appendBlock()

      api(d).payouts(staker.toAddress, third.start, third.start).map(_.period) shouldBe Seq(third)
      api(d).payouts(staker.toAddress, Height(1), third.start - 1).map(_.period) shouldBe Seq(second)
    }

    // Why payouts are recorded rather than recomputed: the boundary after a release prunes the staker from the
    // candidate index, and the period's total stake can no longer be rebuilt from state
    "keeps a period's payout after the staker released and the stake index was pruned" in withStakingDomain(Map(second -> 400L)) { d =>
      stakeForNextPeriod(d, 100.hearth)
      crossPeriodBoundary(d)
      stakeForNextPeriod(d, 0) // released from [9, 12] on
      crossPeriodBoundary(d)   // pays for [5, 8]
      crossPeriodBoundary(d)   // prunes the released stake
      d.appendBlock()

      d.blockchain.stakes(second) shouldBe empty
      api(d).payout(second) shouldBe Some(expectedPayout(second, 400L))
    }
  }
}
