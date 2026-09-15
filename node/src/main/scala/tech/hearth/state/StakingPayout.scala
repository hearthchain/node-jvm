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
    val period = GenerationPeriod.from(newBlockHeight, blockchain.settings.functionalitySettings)

    // The genesis period has no predecessor to pay out for, and nothing has staked before genesis either, so the
    // very first block is excluded by `prev` rather than by a height check of its own.
    period.prev.filter(_ => newBlockHeight == period.start) match {
      case None           => Right(StateSnapshot.empty)
      case Some(finished) => build(blockchain, finished)
    }
  }

  private def build(blockchain: Blockchain, finished: GenerationPeriod): Either[ValidationError, StateSnapshot] = {
    val stakers = blockchain.stakers
    val records = stakers.map(address => address -> blockchain.stake(address))

    for {
      configured <- blockchain.settings.functionalitySettings.credAssetParsed.leftMap(GenericError(_))
      // Both halves have to hold for anything to be paid: a network can run without a Cred economy at all, and a
      // configured cred asset that no predefined snapshot ever issued has no volume to raise. Either way the
      // boundary still normalises stakes below - only the payout is skipped, never the activation.
      credAsset = configured.flatMap(asset => blockchain.assetDescription(asset).map(asset -> _))
      payouts   = credAsset.fold(Map.empty[Address, Long])(_ => distribute(blockchain, finished, records))
      credited  = payouts.values.sum
      snapshot <- StateSnapshot.build(
        blockchain,
        portfolios = credAsset.fold(Map.empty[Address, Portfolio]) { case (asset, _) =>
          payouts.view.mapValues(Portfolio.build(asset, _)).toMap
        },
        updatedAssetVolumes = credAsset.filter(_ => credited > 0).fold(Map.empty[IssuedAsset, BigInt]) { case (asset, description) =>
          Map(asset -> (description.totalVolume + BigInt(credited)))
        },
        stakes = activated(records),
        stakers = remainingStakers(stakers, records)
      )
    } yield snapshot
  }

  /** Each staker's share of the finished period's issuance, by `active` stake. Entries that floor to zero are left
    * out entirely rather than written as a no-op balance, and the truncation dust they leave behind is never
    * minted - `credited` below is the sum of what was actually handed out, not the issuance it was computed from.
    */
  private def distribute(
      blockchain: Blockchain,
      finished: GenerationPeriod,
      records: Seq[(Address, StakeRecord)]
  ): Map[Address, Long] = {
    val issued      = blockchain.committedGenerators(finished).view.map(g => BigInt(blockchain.workDone(g.address, finished))).sum
    val totalStaked = records.view.map { case (_, r) => BigInt(r.active) }.sum

    // With nobody staked there is no one the issuance could be owed to, so it is not emitted at all rather than
    // carried forward - accumulating it would hand a windfall to whoever stakes first.
    if (issued <= 0 || totalStaked <= 0) Map.empty
    else
      records.view
        .map { case (address, record) => address -> (issued * BigInt(record.active) / totalStaked).toLong }
        .filter { case (_, owed) => owed > 0 }
        .toMap
  }

  /** Only the records that actually change: a stake nobody touched last period is already `(x, x)` and activating
    * it is a no-op, so a steady staker set costs no writes at all here.
    */
  private def activated(records: Seq[(Address, StakeRecord)]): Map[Address, StakeRecord] =
    records.view.collect { case (address, record) if record.activated != record => address -> record.activated }.toMap

  /** The staker set with everyone whose record has just emptied dropped - which is where a stake set to 0 finally
    * leaves, a period after the transaction that zeroed it. `None` when nobody left, so an unchanged set is not
    * rewritten every boundary.
    */
  private def remainingStakers(stakers: Seq[Address], records: Seq[(Address, StakeRecord)]): Option[Seq[Address]] = {
    val leaving = records.collect { case (address, record) if record.activated.isEmpty => address }.toSet
    Option.when(leaving.nonEmpty)(stakers.filterNot(leaving.contains))
  }
}
