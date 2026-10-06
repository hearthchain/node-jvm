package tech.hearth.state

import cats.syntax.either.*
import tech.hearth.account.Address
import tech.hearth.lang.ValidationError
import tech.hearth.transaction.Asset.IssuedAsset
import tech.hearth.transaction.TxValidationError.GenericError

/** What happens to stakes and to Cred at the first block of every generation period.
  *
  * One thing, driven off the period that has just *ended*: every staker is credited its pro-rata share of that
  * period's Cred issuance, and the cred asset's total volume rises by exactly what was credited - this is new
  * supply, minted here and nowhere else.
  * Nothing else: a stake is a balance, and a balance needs no carrying from one period to the next. What a period
  * pays out on is simply the amount each address had set before that period began (see `Blockchain.stakeAt`), so
  * this hook writes no stake state at all - only the credited Cred, and the asset volume it minted.
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
    // Through generationPeriodOf rather than GenerationPeriod.from, so that this hook stays behind the same gate
    // every other period-aware path is behind if that gate is ever reintroduced.
    val boundary = for {
      starting <- blockchain.generationPeriodOf(newBlockHeight)
      // The genesis period has no predecessor to pay out for, and nothing has staked before genesis either, so the
      // very first block is excluded by `prev` rather than by a height check of its own.
      finished <- starting.prev if newBlockHeight == starting.start
    } yield finished

    boundary.fold(Right(StateSnapshot.empty))(build(blockchain, _))
  }

  private def build(blockchain: Blockchain, finished: GenerationPeriod): Either[ValidationError, StateSnapshot] =
    for {
      configured <- blockchain.settings.functionalitySettings.credAssetParsed.leftMap(GenericError(_))
      // Both halves have to hold for anything to be paid: a network can run without a Cred economy at all, and a
      // configured cred asset that no predefined snapshot ever issued has no volume to raise.
      credAsset <- Right(configured.flatMap(asset => blockchain.assetDescription(asset).map(asset -> _)))
      snapshot <- credAsset match {
        case None => Right(StateSnapshot.empty)
        case Some((asset, description)) =>
          for {
            payouts <- distribute(blockchain.totalWork(finished), blockchain.stakes(finished))
            credited = payouts.values.sum
            s <- StateSnapshot.build(
              blockchain,
              portfolios = payouts.view.mapValues(Portfolio.build(asset, _)).toMap,
              updatedAssetVolumes = if (credited > 0) Map(asset -> (description.totalVolume + BigInt(credited))) else Map.empty[IssuedAsset, BigInt]
            )
          } yield s
      }
    } yield snapshot

  /** Each staker's share of the finished period's issuance, by what it staked for that period. Entries that floor to zero are left
    * out entirely rather than written as a no-op balance, and the truncation dust they leave behind is never
    * minted - `credited` above is the sum of what was actually handed out, not the issuance it was computed from.
    *
    * Rejects rather than wraps when a period's work does not fit a Long. A single staker's share is the whole of
    * `issued`, so `BigInt.toLong` below would silently truncate it to arbitrary low bits, minting a wrong supply
    * instead of failing - the same trap WorkBoost guards with `require(isValidLong)` on the other consumer of this
    * sum. Guarding `issued` covers every share and their total too, since the shares sum to at most `issued`.
    */
  private def distribute(issued: BigInt, staked: Seq[Stake]): Either[ValidationError, Map[Address, Long]] = {
    val totalStaked = staked.view.map(s => BigInt(s.amount)).sum

    Either
      .raiseUnless(issued.isValidLong)(GenericError(s"Staking issuance overflowed a Long: $issued"))
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
