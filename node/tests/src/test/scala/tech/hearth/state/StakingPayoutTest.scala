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

/** The payout half of staking: how a finished period's tracked work becomes Cred in stakers' balances.
  *
  * `workDone` is written only by SettleTransactionDiff, and (see StartBoostTransactionDiffTest's own doc comment)
  * no fixture in this repo can drive a StartBoost to its accept path, so no real Settle can be appended either.
  * The work and the committee are therefore injected through a Blockchain wrapper over a real domain, the same
  * "inject the minimal necessary state directly" technique GeneratingBalanceProviderTest and
  * SettleTransactionDiffTest use, and StakingPayout is called directly rather than through a block append. The
  * stake records themselves are injected the same way, so that a case can start from a chosen `active`/`pending`
  * split without spending a period getting there - StakeTransactionDiffTest already covers that those splits are
  * what a real StakeTransaction produces.
  */
class StakingPayoutTest extends FreeSpec with WithDomain {
  private val credAsset = IssuedAsset(ByteStr.fill(32)(3))
  private val quantity  = 1000L

  private val alice     = TxHelpers.signer(11).toAddress
  private val bob       = TxHelpers.signer(12).toAddress
  private val validator = TxHelpers.signer(13).toAddress

  private val PeriodLength = 2
  // The chain sits at the last block of [1, 2] and the block being built is 3, the first of [3, 4]
  private val finished       = GenerationPeriod(Height(1), PeriodLength)
  private val newBlockHeight = Height(3)

  private def withPayoutDomain[A](credAssetId: Option[String] = Some(credAsset.id.toString))(f: Domain => A): A =
    withDomain(
      DeterministicFinality.configure(_.copy(generationPeriodLength = PeriodLength, credAsset = credAssetId)),
      AddrWithBalance.enoughBalances(TxHelpers.defaultSigner),
      assets = Seq(GenesisAssetSettings(credAsset.id.toString, "CRED", decimals = 0, quantity = 0, minFee = TestValues.fee))
    )(f)

  private def committedGenerator(address: Address): CommittedGenerator =
    CommittedGenerator(address, TxHelpers.defaultBlsKey.publicKey, ByteStr.empty)

  /** A Blockchain carrying a chosen staker set and a chosen amount of work for `finished`. */
  private def staking(blockchain: Blockchain, stakes: Seq[(Address, StakeRecord)], work: Long): Blockchain =
    new Blockchain {
      export blockchain.{committedGenerators as _, workDone as _, stake as _, stakers as _, *}
      override def committedGenerators(at: GenerationPeriod): IndexedSeq[CommittedGenerator] =
        if (at == finished) IndexedSeq(committedGenerator(validator)) else blockchain.committedGenerators(at)
      override def workDone(v: Address, p: GenerationPeriod): Long =
        if (p == finished && v == validator) work else blockchain.workDone(v, p)
      override def stake(address: Address): StakeRecord = stakes.toMap.getOrElse(address, StakeRecord.empty)
      override def stakers: Seq[Address]                = stakes.map(_._1)
    }

  private def payout(d: Domain, stakes: Seq[(Address, StakeRecord)], work: Long): StateSnapshot =
    StakingPayout.atPeriodBoundary(staking(d.blockchain, stakes, work), newBlockHeight).explicitGet()

  private def credBalance(snapshot: StateSnapshot, address: Address): Option[Long] =
    snapshot.balances.get((address, credAsset))

  "StakingPayout" - {
    "does nothing away from a period boundary" in withPayoutDomain() { d =>
      val stakes = Seq(alice -> StakeRecord(100L, 100L))

      StakingPayout.atPeriodBoundary(staking(d.blockchain, stakes, 500L), Height(4)).explicitGet() shouldBe StateSnapshot.empty
    }

    "does nothing at the genesis block, which has no period before it" in withPayoutDomain() { d =>
      StakingPayout.atPeriodBoundary(staking(d.blockchain, Seq(alice -> StakeRecord(100L, 100L)), 500L), Height(1)).explicitGet() shouldBe
        StateSnapshot.empty
    }

    "splits the finished period's work pro-rata by active stake" in withPayoutDomain() { d =>
      val snapshot = payout(d, Seq(alice -> StakeRecord(75L, 75L), bob -> StakeRecord(25L, 25L)), work = 400L)

      credBalance(snapshot, alice) shouldBe Some(300L)
      credBalance(snapshot, bob) shouldBe Some(100L)
    }

    "mints exactly what it credited" in withPayoutDomain() { d =>
      val snapshot = payout(d, Seq(alice -> StakeRecord(75L, 75L), bob -> StakeRecord(25L, 25L)), work = 400L)

      snapshot.assetVolumes shouldBe Map(credAsset -> BigInt(400L))
    }

    "never mints truncation dust" in withPayoutDomain() { d =>
      // 10 split three ways floors to 3 + 3 + 3; the leftover ember is not minted at all
      val carol    = TxHelpers.signer(14).toAddress
      val stakes   = Seq(alice -> StakeRecord(1L, 1L), bob -> StakeRecord(1L, 1L), carol -> StakeRecord(1L, 1L))
      val snapshot = payout(d, stakes, work = 10L)

      Seq(alice, bob, carol).flatMap(credBalance(snapshot, _)) shouldBe Seq(3L, 3L, 3L)
      snapshot.assetVolumes shouldBe Map(credAsset -> BigInt(9L))
    }

    "credits nothing to a staker whose share floors to zero" in withPayoutDomain() { d =>
      val snapshot = payout(d, Seq(alice -> StakeRecord(1000L, 1000L), bob -> StakeRecord(1L, 1L)), work = 100L)

      credBalance(snapshot, alice) shouldBe Some(99L)
      credBalance(snapshot, bob) shouldBe None
      snapshot.assetVolumes shouldBe Map(credAsset -> BigInt(99L))
    }

    "pays a stake that is only pending nothing at all" in withPayoutDomain() { d =>
      // Alice staked during the finished period, so her stake is pending and earns from the *next* one
      val snapshot = payout(d, Seq(alice -> StakeRecord(active = 0L, pending = 100L)), work = 400L)

      credBalance(snapshot, alice) shouldBe None
      snapshot.assetVolumes shouldBe empty
      // ... but it is activated here, which is what makes it earn next time
      snapshot.stakes shouldBe Map(alice -> StakeRecord(100L, 100L))
    }

    "emits nothing when nobody is staked" in withPayoutDomain() { d =>
      val snapshot = payout(d, Seq.empty, work = 400L)

      snapshot.balances shouldBe empty
      snapshot.assetVolumes shouldBe empty
    }

    "emits nothing when the period produced no work" in withPayoutDomain() { d =>
      val snapshot = payout(d, Seq(alice -> StakeRecord(100L, 100L)), work = 0L)

      snapshot.balances shouldBe empty
      snapshot.assetVolumes shouldBe empty
    }

    "emits nothing on a network with no cred asset, but still activates stakes" in withPayoutDomain(credAssetId = None) { d =>
      val snapshot = payout(d, Seq(alice -> StakeRecord(0L, 100L)), work = 400L)

      snapshot.balances shouldBe empty
      snapshot.assetVolumes shouldBe empty
      snapshot.stakes shouldBe Map(alice -> StakeRecord(100L, 100L))
    }

    "adds to the cred asset's existing volume rather than replacing it" in withPayoutDomain() { d =>
      // Give the asset a non-zero starting volume the way a predefined snapshot would, then pay out on top
      val withVolume = SnapshotBlockchain(d.blockchain, StateSnapshot(assetVolumes = Map(credAsset -> BigInt(quantity))))
      val snapshot =
        StakingPayout.atPeriodBoundary(staking(withVolume, Seq(alice -> StakeRecord(100L, 100L)), 400L), newBlockHeight).explicitGet()

      snapshot.assetVolumes shouldBe Map(credAsset -> BigInt(quantity + 400L))
    }

    "writes no stake record for a staker whose record does not change" in withPayoutDomain() { d =>
      val snapshot = payout(d, Seq(alice -> StakeRecord(100L, 100L)), work = 400L)

      snapshot.stakes shouldBe empty
      snapshot.stakers shouldBe None
    }

    "drops a staker whose record empties, leaving the rest in place" in withPayoutDomain() { d =>
      val stakes   = Seq(alice -> StakeRecord(active = 100L, pending = 0L), bob -> StakeRecord(50L, 50L))
      val snapshot = payout(d, stakes, work = 300L)

      // Alice still earns for the period she was active in, and only then leaves
      credBalance(snapshot, alice) shouldBe Some(200L)
      credBalance(snapshot, bob) shouldBe Some(100L)
      snapshot.stakes shouldBe Map(alice -> StakeRecord.empty)
      snapshot.stakers shouldBe Some(Seq(bob))
    }
  }
}
