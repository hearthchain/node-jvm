package tech.hearth.consensus

/** Simplified placeholder for hearth-tokenomics-spec S7.1's workBoost curve (EMA-smoothed, median-normalized,
  * saturating) - see CLAUDE.md's "workBoost" section for why this isn't the full formula. Boosts a validator's
  * generating balance by up to MaxBoost extra multiples of itself, scaled by its share of the total tracked work
  * across every committed generator of the period the work is drawn from:
  *
  *   boosted = balance + balance * MaxBoost * work / totalWork
  *
  * totalWork <= 0 (nothing tracked that period) or work <= 0 (this validator specifically had none) means no
  * boost. The bound below (never exceeding balance * (1 + MaxBoost), the same bound the spec's own formula
  * guarantees by construction) holds only when the caller guarantees work <= totalWork - i.e. that `work` is
  * genuinely one of `totalWork`'s own addends. That's on the caller: GeneratingBalanceProvider only ever passes a
  * `totalWork` summed over a period's committedGenerators, so it's SettleTransactionDiff's job to only ever write
  * workDone for a validator that's actually a member of that same committee (see its own "committed generator"
  * check) - if that check were ever missing, `work` could exceed `totalWork` and this bound would break; the
  * `require` below turns that into a loud failure instead of a silently wrapped, possibly negative balance.
  *
  * BigInt intermediate arithmetic, not Double: this feeds directly into which blocks are valid, so it has to be
  * exact and platform-independent (see "Why fixed-point BigInt, not Math.pow" in CLAUDE.md). totalWork is BigInt
  * specifically because it's a sum over an unbounded number of committed generators (see
  * GeneratingBalanceProvider.workContext) and so isn't itself safe to accumulate as a plain Long.
  */
object WorkBoost {

  // hearth-tokenomics-spec S7.1's own governance range is 2-3; the conservative end is chosen since no real
  // network data exists yet to justify going higher (see "Bounded amplification (Lemma)": at MaxBoost=2 an
  // attacker needs > 1/4 of stake to control forging, vs > 1/(2+MaxBoost) in general).
  val MaxBoost = 2

  def apply(balance: Long, work: Long, totalWork: BigInt): Long =
    if (totalWork <= 0 || work <= 0) balance
    else {
      val boosted = BigInt(balance) + (BigInt(balance) * MaxBoost * work) / totalWork
      require(boosted.isValidLong, s"workBoost overflowed a Long: balance=$balance, work=$work, totalWork=$totalWork")
      boosted.toLong
    }
}
