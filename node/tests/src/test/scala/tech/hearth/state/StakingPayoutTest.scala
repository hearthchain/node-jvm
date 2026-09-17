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
import tech.hearth.test.DomainPresets.*
import tech.hearth.transaction.Asset.IssuedAsset
import tech.hearth.transaction.TxHelpers

/** The boundary half of staking: turning a finished period's tracked work into Cred, and carrying stakes into the
  * period now starting.
  *
  * `workDone` is written only by SettleTransactionDiff, and (see StartBoostTransactionDiffTest's own doc comment)
  * no fixture in this repo can drive a StartBoost to its accept path, so no real Settle can be appended either. The
  * work, the committee and the two periods' stakes are therefore injected through Blockchain wrappers over a real
  * domain - the same "inject the minimal necessary state directly" technique GeneratingBalanceProviderTest and
  * SettleTransactionDiffTest use - and StakingPayout is called directly rather than through a block append.
  * StakeTransactionDiffTest covers that a real StakeTransaction produces those entries.
  */
class StakingPayoutTest extends FreeSpec with WithDomain {
  private val credAsset = IssuedAsset(ByteStr.fill(32)(3))
  private val quantity  = 1000L

  private val alice     = TxHelpers.signer(11).toAddress
  private val bob       = TxHelpers.signer(12).toAddress
  private val carol     = TxHelpers.signer(14).toAddress
  private val validator = TxHelpers.signer(13).toAddress

  private val PeriodLength = 2
  // The chain sits at the last block of [1, 2] and the block being built is 3, the first of [3, 4]
  private val finished       = GenerationPeriod(Height(1), PeriodLength)
  private val starting       = finished.next
  private val newBlockHeight = starting.start

  private def withPayoutDomain[A](credAssetId: Option[String] = Some(credAsset.id.toString))(f: Domain => A): A =
    withDomain(
      DeterministicFinality.configure(_.copy(generationPeriodLength = PeriodLength, credAsset = credAssetId)),
      AddrWithBalance.enoughBalances(TxHelpers.defaultSigner),
      assets = Seq(GenesisAssetSettings(credAsset.id.toString, "CRED", decimals = 0, quantity = 0, minFee = TestValues.fee))
    )(f)

  private def committedGenerator(address: Address): CommittedGenerator =
    CommittedGenerator(address, TxHelpers.defaultBlsKey.publicKey, ByteStr.empty)

  private def staking(blockchain: Blockchain, staked: Map[GenerationPeriod, Seq[Stake]], work: Long): Blockchain =
    blockchainWithStakes(
      blockchainWithCommitteeWork(blockchain, finished, Seq(committedGenerator(validator)), Map(validator -> work)),
      staked
    )

  private def payout(
      d: Domain,
      staked: Seq[Stake],
      work: Long,
      restated: Seq[Stake] = Seq.empty
  ): StateSnapshot =
    StakingPayout
      .atPeriodBoundary(staking(d.blockchain, Map(finished -> staked, starting -> restated), work), newBlockHeight)
      .explicitGet()

  private def credBalance(snapshot: StateSnapshot, address: Address): Option[Long] =
    snapshot.balances.get((address, credAsset))

  private def carriedForward(snapshot: StateSnapshot): Seq[(Address, Long)] =
    snapshot.nextStakes.map(s => s.address -> s.amount)

  "StakingPayout" - {
    "does nothing away from a period boundary" in withPayoutDomain() { d =>
      val blockchain = staking(d.blockchain, Map(finished -> Seq(Stake(alice, 100L))), 500L)

      StakingPayout.atPeriodBoundary(blockchain, Height(4)).explicitGet() shouldBe StateSnapshot.empty
    }

    "does nothing at the genesis block, which has no period before it" in withPayoutDomain() { d =>
      val blockchain = staking(d.blockchain, Map(finished -> Seq(Stake(alice, 100L))), 500L)

      StakingPayout.atPeriodBoundary(blockchain, Height(1)).explicitGet() shouldBe StateSnapshot.empty
    }

    "splits the finished period's work pro-rata by what was staked for it" in withPayoutDomain() { d =>
      val snapshot = payout(d, Seq(Stake(alice, 75L), Stake(bob, 25L)), work = 400L)

      credBalance(snapshot, alice) shouldBe Some(300L)
      credBalance(snapshot, bob) shouldBe Some(100L)
    }

    "mints exactly what it credited" in withPayoutDomain() { d =>
      val snapshot = payout(d, Seq(Stake(alice, 75L), Stake(bob, 25L)), work = 400L)

      snapshot.assetVolumes shouldBe Map(credAsset -> BigInt(400L))
    }

    "never mints truncation dust" in withPayoutDomain() { d =>
      // 10 split three ways floors to 3 + 3 + 3; the leftover ember is not minted at all
      val snapshot = payout(d, Seq(Stake(alice, 1L), Stake(bob, 1L), Stake(carol, 1L)), work = 10L)

      Seq(alice, bob, carol).flatMap(credBalance(snapshot, _)) shouldBe Seq(3L, 3L, 3L)
      snapshot.assetVolumes shouldBe Map(credAsset -> BigInt(9L))
    }

    "credits nothing to a staker whose share floors to zero" in withPayoutDomain() { d =>
      val snapshot = payout(d, Seq(Stake(alice, 1000L), Stake(bob, 1L)), work = 100L)

      credBalance(snapshot, alice) shouldBe Some(99L)
      credBalance(snapshot, bob) shouldBe None
      snapshot.assetVolumes shouldBe Map(credAsset -> BigInt(99L))
    }

    "pays nothing for a stake that was only declared for the period now starting" in withPayoutDomain() { d =>
      // Alice staked during the finished period, so her stake is filed under the starting one and earns from it on
      val snapshot = payout(d, staked = Seq.empty, work = 400L, restated = Seq(Stake(alice, 100L)))

      credBalance(snapshot, alice) shouldBe None
      snapshot.assetVolumes shouldBe empty
    }

    "emits nothing when nobody is staked" in withPayoutDomain() { d =>
      val snapshot = payout(d, Seq.empty, work = 400L)

      snapshot.balances shouldBe empty
      snapshot.assetVolumes shouldBe empty
    }

    "emits nothing when the period produced no work" in withPayoutDomain() { d =>
      val snapshot = payout(d, Seq(Stake(alice, 100L)), work = 0L)

      snapshot.balances shouldBe empty
      snapshot.assetVolumes shouldBe empty
    }

    "emits nothing on a network with no cred asset, but still carries stakes forward" in withPayoutDomain(credAssetId = None) { d =>
      val snapshot = payout(d, Seq(Stake(alice, 100L)), work = 400L)

      snapshot.balances shouldBe empty
      snapshot.assetVolumes shouldBe empty
      carriedForward(snapshot) shouldBe Seq(alice -> 100L)
    }

    "adds to the cred asset's existing volume rather than replacing it" in withPayoutDomain() { d =>
      // Give the asset a non-zero starting volume the way a predefined snapshot would, then pay out on top
      val withVolume = SnapshotBlockchain(d.blockchain, StateSnapshot(assetVolumes = Map(credAsset -> BigInt(quantity))))
      val blockchain = staking(withVolume, Map(finished -> Seq(Stake(alice, 100L))), 400L)

      val snapshot = StakingPayout.atPeriodBoundary(blockchain, newBlockHeight).explicitGet()

      snapshot.assetVolumes shouldBe Map(credAsset -> BigInt(quantity + 400L))
    }

    // A stake stays in force until a transaction changes it, but a period holds only what was staked for it
    "carries an untouched stake forward into the period now starting" in withPayoutDomain() { d =>
      val snapshot = payout(d, Seq(Stake(alice, 100L), Stake(bob, 50L)), work = 300L)

      carriedForward(snapshot) should contain theSameElementsAs Seq(alice -> 100L, bob -> 50L)
      snapshot.nextStakes.map(_.periodStart).distinct shouldBe Seq(starting.start)
    }

    "leaves a stake already restated for the starting period alone" in withPayoutDomain() { d =>
      val snapshot = payout(d, Seq(Stake(alice, 100L), Stake(bob, 50L)), work = 300L, restated = Seq(Stake(alice, 20L)))

      // Alice sent a Stake during the finished period; that is exactly the stake that supersedes the old one
      carriedForward(snapshot) shouldBe Seq(bob -> 50L)
    }

    "does not resurrect a stake that was released" in withPayoutDomain() { d =>
      // Alice staked 0 during the finished period, which is filed under the starting one as a release
      val snapshot = payout(d, Seq(Stake(alice, 100L)), work = 300L, restated = Seq(Stake(alice, 0L)))

      // She still earns for the period she was staked in, and only then stops
      credBalance(snapshot, alice) shouldBe Some(300L)
      carriedForward(snapshot) shouldBe empty
    }

    "does not carry a zero stake forward" in withPayoutDomain() { d =>
      val snapshot = payout(d, Seq(Stake(alice, 0L), Stake(bob, 50L)), work = 300L)

      carriedForward(snapshot) shouldBe Seq(bob -> 50L)
    }

    // A single staker's share is the whole of `issued`, so BigInt.toLong would truncate it into an arbitrary
    // amount of minted supply rather than failing - the guard has to reject, not wrap.
    "rejects a period whose total work does not fit a Long instead of minting a wrapped amount" in withPayoutDomain() { d =>
      val overflowing = Seq(TxHelpers.signer(15).toAddress, TxHelpers.signer(16).toAddress).map(committedGenerator)
      val blockchain = blockchainWithStakes(
        blockchainWithCommitteeWork(d.blockchain, finished, overflowing, overflowing.map(_.address -> Long.MaxValue).toMap),
        Map(finished -> Seq(Stake(alice, 100L)))
      )

      StakingPayout.atPeriodBoundary(blockchain, newBlockHeight) should produce("overflowed a Long")
    }
  }
}
