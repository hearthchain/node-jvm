package tech.hearth.events

import cats.Monoid
import com.google.protobuf.ByteString
import tech.hearth.TestValues
import tech.hearth.common.state.ByteStr
import tech.hearth.db.WithState.AddrWithBalance
import tech.hearth.events.FakeObserver.*
import tech.hearth.events.api.grpc.protobuf.SubscribeRequest
import tech.hearth.events.protobuf.BlockchainUpdated as PBBlockchainUpdated
import tech.hearth.events.protobuf.StateUpdate.StakingPayout as PBStakingPayout
import tech.hearth.history.Domain
import tech.hearth.protobuf.*
import tech.hearth.settings.GenesisAssetSettings
import tech.hearth.state.{GenerationPeriod, Height, PeriodPayout, StakerPayout}
import tech.hearth.test.*
import tech.hearth.test.DomainPresets.*
import tech.hearth.transaction.Asset.IssuedAsset
import tech.hearth.transaction.TxHelpers

/** The staking payout on the BlockchainUpdates stream: a real StakeTransaction, the boundary block that pays it, and the
  * update the repo serves for that block. The work a payout splits is injected (see TestStorageFactory), since nothing
  * a fixture can append produces any.
  */
class StakingPayoutStateUpdateSpec extends FreeSpec with WithBUDomain {
  private val miner  = TxHelpers.defaultSigner
  private val staker = TxHelpers.signer(11)

  private val credAsset = IssuedAsset(ByteStr.fill(32)(3))

  // Periods are [1, 4], [5, 8], [9, 12]: see CommonStakingApiTest for why 4
  private val PeriodLength = 4
  private val second       = GenerationPeriod(Height(5), PeriodLength)

  private def withStakingRepo(f: (Domain, Repo) => Unit): Unit =
    withDomainAndRepo(
      DeterministicFinality.configure(_.copy(generationPeriodLength = PeriodLength, credAsset = Some(credAsset.id.toString))),
      AddrWithBalance.enoughBalances(miner, staker),
      assets = Seq(GenesisAssetSettings(credAsset.id.toString, "CRED", decimals = 0, quantity = 0, minFee = TestValues.fee)),
      work = Map((miner.toAddress, second) -> 400L)
    )(f)

  private def crossPeriodBoundary(d: Domain): Unit = {
    val nextStart = d.blockchain.currentGenerationPeriod.get.next.start
    d.appendBlock(TxHelpers.commitToGeneration(nextStart, miner))
    while (d.blockchain.height < nextStart.toInt) d.appendBlock()
  }

  private def stakingPayoutAt(repo: Repo, height: Height): Option[PBStakingPayout] =
    repo.getBlockUpdate(height).getUpdate.getAppend.stateUpdate.flatMap(_.stakingPayout)

  "StateUpdate.stakingPayout" - {
    "carries the payout on the update of the block that paid it" in withStakingRepo { (d, repo) =>
      d.appendBlock(TxHelpers.stake(staker, periodStart = second.start, amount = 100.hearth))
      crossPeriodBoundary(d)
      crossPeriodBoundary(d) // block 9 pays for [5, 8]
      d.appendBlock()

      stakingPayoutAt(repo, Height(9)) shouldBe Some(
        PBStakingPayout(
          periodStart = 5,
          periodEnd = 8,
          assetId = credAsset.id.toByteString,
          issued = 400L,
          totalStaked = ByteString.copyFrom(BigInt(100.hearth).toByteArray),
          payouts = Seq(PBStakingPayout.Payout(staker.toAddress.toByteString, 100.hearth, 400L))
        )
      )
    }

    "carries the undone payout on the update that rolls its block back" in withStakingRepo { (d, repo) =>
      d.appendBlock(TxHelpers.stake(staker, periodStart = second.start, amount = 100.hearth))
      val subscription = repo.createFakeObserver(SubscribeRequest.of(2, Int.MaxValue))
      crossPeriodBoundary(d)
      crossPeriodBoundary(d)
      d.appendBlock()

      d.rollbackTo(second.end.toInt)

      // One rollback event per removed block: the one landing on height 8 is block 9's, the block that paid
      def rollsBackTheBoundary(u: PBBlockchainUpdated) = u.update.isRollback && u.height == second.end.toInt
      val rollback = subscription.fetchUntil(_.exists(e => rollsBackTheBoundary(e.getUpdate))).map(_.getUpdate).filter(rollsBackTheBoundary).head

      rollback.getRollback.getRollbackStateUpdate.stakingPayout.map(_.periodStart) shouldBe Some(5)
    }

    // A period whose every share floors to zero credits nothing, so the payout is all the boundary block changed, and
    // serde drops an update that says it is empty
    "is not empty when all it carries is a payout" in {
      val payout = PeriodPayout(second, credAsset, 1L, BigInt(3), Seq(StakerPayout(staker.toAddress, 3L, 0L)))
      Monoid.empty[StateUpdate].copy(stakingPayout = Some(payout)).isEmpty shouldBe false
    }

    "survives a round trip through protobuf" in {
      val payout = PeriodPayout(second, credAsset, 400L, BigInt(Long.MaxValue) * 3, Seq(StakerPayout(staker.toAddress, 100L, 400L)))
      val update = Monoid.empty[StateUpdate].copy(stakingPayout = Some(payout))
      StateUpdate.fromPB(StateUpdate.toPB(update)) shouldBe update
    }
  }
}
