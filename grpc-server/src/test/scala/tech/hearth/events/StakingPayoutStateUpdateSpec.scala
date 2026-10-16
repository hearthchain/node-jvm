package tech.hearth.events

import cats.Monoid
import com.google.protobuf.ByteString
import tech.hearth.db.WithState.AddrWithBalance
import tech.hearth.events.FakeObserver.*
import tech.hearth.events.api.grpc.protobuf.SubscribeRequest
import tech.hearth.events.protobuf.BlockchainUpdated as PBBlockchainUpdated
import tech.hearth.events.protobuf.StateUpdate.StakingPayout as PBStakingPayout
import tech.hearth.history.Domain
import tech.hearth.protobuf.*
import tech.hearth.state.{Height, PeriodPayout, StakerPayout}
import tech.hearth.test.*
import tech.hearth.test.StakingFixtures.*
import tech.hearth.transaction.TxHelpers

/** The staking payout on the BlockchainUpdates stream: a real StakeTransaction, the boundary block that pays it, and the
  * update the repo serves for that block. The work a payout splits is injected (see TestStorageFactory), since nothing
  * a fixture can append produces any.
  */
class StakingPayoutStateUpdateSpec extends FreeSpec with WithBUDomain {
  private val miner  = TxHelpers.defaultSigner
  private val staker = TxHelpers.signer(11)

  private val second = FirstPeriod.next

  private def withStakingRepo(f: (Domain, Repo) => Unit): Unit =
    withDomainAndRepo(
      stakingSettings(),
      AddrWithBalance.enoughBalances(miner, staker),
      assets = Seq(CredGenesis),
      work = Map((miner.toAddress, second) -> 400L)
    )(f)

  private def stakingPayoutAt(repo: Repo, height: Height): Option[PBStakingPayout] =
    repo.getBlockUpdate(height).getUpdate.getAppend.stateUpdate.flatMap(_.stakingPayout)

  "StateUpdate.stakingPayout" - {
    "carries the payout on the update of the block that paid it" in withStakingRepo { (d, repo) =>
      stakeForNextPeriod(d, staker, 100.hearth)
      crossPeriodBoundary(d)
      crossPeriodBoundary(d) // block 9 pays for [5, 8]
      d.appendBlock()

      stakingPayoutAt(repo, Height(9)) shouldBe Some(
        PBStakingPayout(
          periodStart = 5,
          periodEnd = 8,
          assetId = CredAsset.id.toByteString,
          issued = 400L,
          totalStaked = ByteString.copyFrom(BigInt(100.hearth).toByteArray),
          payouts = Seq(PBStakingPayout.Payout(staker.toAddress.toByteString, 100.hearth, 400L))
        )
      )
    }

    // A payout record in a rollback would read exactly like a new payout to a consumer that skips the context, so the
    // rollback undoes it through the reversed Cred balances alone
    "leaves the payout off the update that rolls its block back, whose balances undo it" in withStakingRepo { (d, repo) =>
      stakeForNextPeriod(d, staker, 100.hearth)
      val subscription = repo.createFakeObserver(SubscribeRequest.of(2, Int.MaxValue))
      crossPeriodBoundary(d)
      crossPeriodBoundary(d)
      d.appendBlock()

      d.rollbackTo(second.end.toInt)

      // One rollback event per removed block: the one landing on height 8 is block 9's, the block that paid
      def rollsBackTheBoundary(u: PBBlockchainUpdated) = u.update.isRollback && u.height == second.end.toInt
      val rollback = subscription.fetchUntil(_.exists(e => rollsBackTheBoundary(e.getUpdate))).map(_.getUpdate).filter(rollsBackTheBoundary).head
      val update   = rollback.getRollback.getRollbackStateUpdate

      update.stakingPayout shouldBe None
      update.balances
        .find(b => b.address == staker.toAddress.toByteString && b.getAmountAfter.assetId == CredAsset.id.toByteString)
        .map(b => b.amountBefore -> b.getAmountAfter.amount) shouldBe Some(400L -> 0L)
    }

    // A period whose every share floors to zero credits nothing, so the payout is all the boundary block changed, and
    // serde drops an update that says it is empty
    "is not empty when all it carries is a payout" in {
      val payout = PeriodPayout(second, CredAsset, 1L, BigInt(3), Seq(StakerPayout(staker.toAddress, 3L, 0L)))
      Monoid.empty[StateUpdate].copy(stakingPayout = Some(payout)).isEmpty shouldBe false
    }

    "survives a round trip through protobuf" in {
      val payout = PeriodPayout(second, CredAsset, 400L, BigInt(Long.MaxValue) * 3, Seq(StakerPayout(staker.toAddress, 100L, 400L)))
      val update = Monoid.empty[StateUpdate].copy(stakingPayout = Some(payout))
      StateUpdate.fromPB(StateUpdate.toPB(update)) shouldBe update
    }
  }
}
