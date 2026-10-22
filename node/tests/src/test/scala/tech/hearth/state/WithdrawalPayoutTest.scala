package tech.hearth.state

import tech.hearth.TestValues
import tech.hearth.account.Address
import tech.hearth.common.state.ByteStr
import tech.hearth.common.utils.EitherExt2.*
import tech.hearth.db.WithDomain
import tech.hearth.db.WithState.AddrWithBalance
import tech.hearth.history.Domain
import tech.hearth.settings.GenesisAssetSettings
import tech.hearth.test.*
import tech.hearth.test.StakingFixtures.*
import tech.hearth.transaction.Asset.IssuedAsset
import tech.hearth.transaction.TxHelpers

/** The payout half of a withdrawal: returning what is still unsettled of a pending request at its period start.
  * The ledgers are seeded through a SnapshotBlockchain, for the reason WithdrawTransactionDiffTest gives, and
  * WithdrawalPayout is called directly. WithdrawalStorageTest takes a real Withdraw through the append path.
  */
class WithdrawalPayoutTest extends FreeSpec with WithDomain {
  private val client = TxHelpers.signer(11).toAddress
  private val miner  = TxHelpers.secondSigner.toAddress
  private val asset  = IssuedAsset(ByteStr.fill(32)(7))
  private val triple = (client, miner, asset)

  // Periods are [1, 4], [5, 8], [9, 12]: a Withdraw in the first is paid at 9
  private val payoutAt = Height(9)

  private def withPayoutDomain[A](f: Domain => A): A =
    withDomain(
      stakingSettings(credAsset = None),
      AddrWithBalance.enoughBalances(TxHelpers.defaultSigner),
      assets = Seq(GenesisAssetSettings(asset.id.toString, "RESV", decimals = 0, quantity = 0, minFee = TestValues.fee))
    )(f)

  private def withRequest(blockchain: Blockchain, reserved: Long, settled: Long, requested: Long, withdrawn: Long = 0L): Blockchain = {
    val snapshot = StateSnapshot
      .build(
        blockchain,
        reservedAmounts = Map(triple -> reserved),
        settledAmounts = Map(triple -> settled),
        withdrawnAmounts = Map(triple -> withdrawn),
        pendingWithdrawals = Map(triple -> Some(PendingWithdrawal(requested, payoutAt)))
      )
      .explicitGet()
    SnapshotBlockchain(blockchain, snapshot)
  }

  "WithdrawalPayout" - {
    "returns the requested amount to the client at the payout height, recording it as withdrawn" in withPayoutDomain { d =>
      val snapshot =
        WithdrawalPayout.atPeriodBoundary(withRequest(d.blockchain, reserved = 100L, settled = 0L, requested = 60L), payoutAt).explicitGet()

      snapshot.balances((client, asset)) shouldBe 60L
      snapshot.withdrawnAmounts(triple) shouldBe 60L
      snapshot.pendingWithdrawals(triple) shouldBe None
    }

    "returns only what is still unsettled when the miner settled after the request" in withPayoutDomain { d =>
      val snapshot =
        WithdrawalPayout.atPeriodBoundary(withRequest(d.blockchain, reserved = 100L, settled = 70L, requested = 60L), payoutAt).explicitGet()

      snapshot.balances((client, asset)) shouldBe 30L
      snapshot.withdrawnAmounts(triple) shouldBe 30L
    }

    "clears a request with nothing left to pay without writing a balance or withdrawn entry" in withPayoutDomain { d =>
      val snapshot =
        WithdrawalPayout.atPeriodBoundary(withRequest(d.blockchain, reserved = 100L, settled = 100L, requested = 60L), payoutAt).explicitGet()

      snapshot.balances shouldBe empty
      snapshot.withdrawnAmounts shouldBe empty
      snapshot.pendingWithdrawals(triple) shouldBe None
    }

    "adds to what earlier withdrawals returned" in withPayoutDomain { d =>
      val blockchain = withRequest(d.blockchain, reserved = 100L, settled = 10L, requested = 50L, withdrawn = 20L)
      val snapshot   = WithdrawalPayout.atPeriodBoundary(blockchain, payoutAt).explicitGet()

      snapshot.balances((client, asset)) shouldBe 50L
      snapshot.withdrawnAmounts(triple) shouldBe 70L
    }

    "sums one client's payouts from several miners into its balance" in withPayoutDomain { d =>
      val otherMiner = TxHelpers.signer(12).toAddress
      val other      = (client, otherMiner, asset)
      val snapshot = StateSnapshot
        .build(
          d.blockchain,
          reservedAmounts = Map(other -> 50L),
          pendingWithdrawals = Map(other -> Some(PendingWithdrawal(30L, payoutAt)))
        )
        .explicitGet()
      val blockchain = withRequest(SnapshotBlockchain(d.blockchain, snapshot), reserved = 100L, settled = 0L, requested = 60L)
      val paid       = WithdrawalPayout.atPeriodBoundary(blockchain, payoutAt).explicitGet()

      paid.balances((client, asset)) shouldBe 90L
      paid.withdrawnAmounts shouldBe Map(triple -> 60L, other -> 30L)
    }

    "does nothing at any other height, period start or not" in withPayoutDomain { d =>
      val blockchain = withRequest(d.blockchain, reserved = 100L, settled = 0L, requested = 60L)

      WithdrawalPayout.atPeriodBoundary(blockchain, Height(8)).explicitGet() shouldBe StateSnapshot.empty
      WithdrawalPayout.atPeriodBoundary(blockchain, Height(5)).explicitGet() shouldBe StateSnapshot.empty
    }

    "skips an index entry whose request is due at another height" in withPayoutDomain { d =>
      val requested = withRequest(d.blockchain, reserved = 100L, settled = 0L, requested = 60L)
      // As a rolled-back or already paid request would leave it: listed as due, but resolving to another payout
      val stale = new Blockchain {
        export requested.{withdrawalsDue as _, *}
        override def withdrawalsDue(at: Height): Seq[(Address, Address, IssuedAsset)] = Seq(triple)
      }

      WithdrawalPayout.atPeriodBoundary(stale, Height(5)).explicitGet() shouldBe StateSnapshot.empty
    }
  }
}
