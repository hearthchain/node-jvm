package tech.hearth.state

import tech.hearth.test.FreeSpec
import tech.hearth.transaction.TxHelpers

/** `Stake.applied` is the rule three independent paths resolve a period with (RocksDB, the liquid snapshot, the
  * warm cache), so its ordering is not an implementation detail: it reaches the stored byte layout through
  * StakingPayout's carry-forward, and the doc comment claims the order depends only on the entries.
  */
class StakeSetTest extends FreeSpec {
  private val alice = TxHelpers.signer(11).toAddress
  private val bob   = TxHelpers.signer(12).toAddress
  private val carol = TxHelpers.signer(13).toAddress

  private def commitment(address: tech.hearth.account.Address, amount: Long) =
    StakeCommitment(address, Height(5), amount)

  "Stake.applied" - {
    "appends an address that was not staked for the period" in {
      val applied = Stake.applied(Stake.of(alice -> 1L), Seq(commitment(bob, 2L)))

      applied.toSeq shouldBe Seq(alice -> 1L, bob -> 2L)
    }

    // The order reaches the stored layout through the carry-forward, so a restatement must not reshuffle the set:
    // a warm cache and a cold load would otherwise disagree about a period they both resolve correctly.
    "keeps a restated address in its original position rather than moving it to the end" in {
      val base    = Stake.of(alice -> 1L, bob -> 2L, carol -> 3L)
      val applied = Stake.applied(base, Seq(commitment(alice, 9L)))

      applied.toSeq shouldBe Seq(alice -> 9L, bob -> 2L, carol -> 3L)
    }

    "lets the last restatement of an address win" in {
      val applied = Stake.applied(Stake.empty, Seq(commitment(alice, 1L), commitment(alice, 2L), commitment(alice, 3L)))

      applied.toSeq shouldBe Seq(alice -> 3L)
    }

    "keeps a release, so a carry-forward cannot resurrect the stake it replaced" in {
      Stake.applied(Stake.of(alice -> 5L), Seq(commitment(alice, 0L))).toSeq shouldBe Seq(alice -> 0L)
    }

    "leaves the set untouched when nothing is restated" in {
      val base = Stake.of(alice -> 1L, bob -> 2L)

      Stake.applied(base, Seq.empty) shouldBe base
    }
  }
}
