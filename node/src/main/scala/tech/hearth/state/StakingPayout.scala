package tech.hearth.state

import cats.syntax.either.*
import tech.hearth.account.Address
import tech.hearth.lang.ValidationError
import tech.hearth.transaction.Asset.IssuedAsset
import tech.hearth.transaction.TxValidationError.GenericError

/** What happens to stakes and to Cred at the first block of every generation period.
  *
  * Two things, in this order, both driven off the period that has just *ended*:
  *
  *   1. every staker is credited its pro-rata share of that period's Cred issuance, and the cred asset's total
  *      volume rises by exactly what was credited - this is new supply, minted here and nowhere else;
  *   2. every stake record is normalised, `active := pending` ([[StakeRecord.activated]]), which is what makes a
  *      stake set during the finished period start earning in this one, and what finally releases the HRTH of a
  *      stake that was lowered or zeroed.
  *
  * Issuance is the finished period's total tracked work: the sum of `workDone` over the generators committed for
  * it, which SettleTransactionDiff accumulates from the burned share of every settlement. So the Cred paid to
  * stakers in a period is exactly a function of what the miners demonstrably did in the period before, with no
  * separate controller or governance constant in between. `docs/notes/cred-economy.md` records the fuller
  * EMA-smoothed controller this stands in for, and why replacing this one function is all that switching to it
  * needs.
  *
  * All arithmetic is exact `BigInt`, never `Double`: this mints supply and moves balances, so it is bound by the
  * same no-floating-point rule, and for the same cross-client reason, as the emission curve (see "Why fixed-point
  * `BigInt`, not `Math.pow`" in `docs/notes/economics.md`). Both the work sum and the staked total are sums over
  * an unbounded set of accounts and so are not guaranteed to fit a `Long` even though each addend is.
  */
object StakingPayout {

  /** The snapshot this height contributes, or [[StateSnapshot.empty]] when it is not a period boundary.
    *
    * @param blockchain
    *   positioned at the block *before* `newBlockHeight`, which is the last block of the finished period.
    * @param newBlockHeight
    *   the height of the block being built.
    */
  def atPeriodBoundary(blockchain: Blockchain, newBlockHeight: Height): Either[ValidationError, StateSnapshot] = {
    val starting = GenerationPeriod.from(newBlockHeight, blockchain.settings.functionalitySettings)

    // The genesis period has no predecessor to pay out for, and nothing has staked before genesis either, so the
    // very first block is excluded by `prev` rather than by a height check of its own.
    starting.prev.filter(_ => newBlockHeight == starting.start) match {
      case None           => Right(StateSnapshot.empty)
      case Some(finished) => build(blockchain, finished, starting)
    }
  }

  private def build(blockchain: Blockchain, finished: GenerationPeriod, starting: GenerationPeriod): Either[ValidationError, StateSnapshot] = {
    val staked = blockchain.stakes(finished)

    for {
      configured <- blockchain.settings.functionalitySettings.credAssetParsed.leftMap(GenericError(_))
      // Both halves have to hold for anything to be paid: a network can run without a Cred economy at all, and a
      // configured cred asset that no predefined snapshot ever issued has no volume to raise. Either way the
      // carry-forward below still happens - only the payout is skipped, or a stake could never be released.
      credAsset = configured.flatMap(asset => blockchain.assetDescription(asset).map(asset -> _))
      payouts <- if (credAsset.isEmpty) Right(Map.empty[Address, Long]) else distribute(blockchain, finished, staked)
      credited = payouts.values.sum
      snapshot <- StateSnapshot.build(
        blockchain,
        portfolios = credAsset.fold(Map.empty[Address, Portfolio]) { case (asset, _) =>
          payouts.view.mapValues(Portfolio.build(asset, _)).toMap
        },
        updatedAssetVolumes = credAsset.filter(_ => credited > 0).fold(Map.empty[IssuedAsset, BigInt]) { case (asset, description) =>
          Map(asset -> (description.totalVolume + BigInt(credited)))
        },
        nextStakes = carriedForward(blockchain, staked, starting)
      )
    } yield snapshot
  }

  /** A stake stays in force until a transaction changes it, but a period only holds what was staked *for* it - so
    * every stake that survived the finished period is restated for the one now starting.
    *
    * An address that already has an entry for the starting period is left alone: it sent a StakeTransaction during
    * the finished period, and that is precisely the stake that supersedes this one - including when it staked 0,
    * which is how a release finally takes effect here. A stake of 0 is never carried forward either, so a released
    * stake stops costing reads and disappears from the set the next payout walks.
    */
  private def carriedForward(blockchain: Blockchain, staked: IndexedSeq[Stake], starting: GenerationPeriod): Seq[StakeCommitment] = {
    val alreadyStated = blockchain.stakes(starting).view.map(_.address).toSet
    staked.view
      .filter(stake => stake.amount > 0 && !alreadyStated(stake.address))
      .map(stake => StakeCommitment(stake.address, starting.start, stake.amount))
      .toSeq
  }

  /** Each staker's share of the finished period's issuance, by `active` stake. Entries that floor to zero are left
    * out entirely rather than written as a no-op balance, and the truncation dust they leave behind is never
    * minted - `credited` above is the sum of what was actually handed out, not the issuance it was computed from.
    *
    * Rejects rather than wraps when a period's work does not fit a Long. A single staker's share is the whole of
    * `issued`, so `BigInt.toLong` below would silently truncate it to arbitrary low bits, minting a wrong supply
    * instead of failing - the same trap WorkBoost guards with `require(isValidLong)` on the other consumer of this
    * sum. Guarding `issued` covers every share and their total too, since the shares sum to at most `issued`.
    */
  private def distribute(
      blockchain: Blockchain,
      finished: GenerationPeriod,
      staked: IndexedSeq[Stake]
  ): Either[ValidationError, Map[Address, Long]] = {
    val issued      = blockchain.totalWork(finished)
    val totalStaked = staked.view.map(s => BigInt(s.amount)).sum

    Either
      .raiseUnless(issued.isValidLong)(GenericError(s"Staking issuance for $finished overflowed a Long: $issued"))
      .map { _ =>
        // With nobody staked there is no one the issuance could be owed to, so it is not emitted at all rather than
        // carried forward - accumulating it would hand a windfall to whoever stakes first.
        if (issued <= 0 || totalStaked <= 0) Map.empty
        else
          staked.view
            .map(stake => stake.address -> (issued * BigInt(stake.amount) / totalStaked).toLong)
            .filter { case (_, owed) => owed > 0 }
            .toMap
      }
  }

}
