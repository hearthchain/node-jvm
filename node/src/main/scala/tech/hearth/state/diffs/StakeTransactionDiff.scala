package tech.hearth.state.diffs

import cats.syntax.either.*
import tech.hearth.lang.ValidationError
import tech.hearth.state.*
import tech.hearth.transaction.StakeTransaction
import tech.hearth.transaction.TxValidationError.{ActivationError, GenericError}

/** StakeTransaction semantics: stake `amount` of HRTH for the generation period starting at `periodStart`.
  *
  * The transaction records exactly its own two fields, against the sender, for the period it names - nothing is
  * derived and nothing already on chain is read to write it. Every behaviour falls out of the period keying:
  *
  *   - *the last one wins.* Several StakeTransactions in one period all name the same next period, and a period's
  *     entries are folded in height order with the last winning (see RocksDBWriter.loadStakes), so the final one
  *     applied is simply the one in force.
  *   - *HRTH is reserved immediately.* `Blockchain.lockedStake` is the larger of this period's stake and the next
  *     one's, the same "this period and the next" window `generationDeposit` counts deposits over, so raising a
  *     stake locks the new amount as soon as this snapshot applies. BalanceDiffValidation rejects the transaction
  *     if the sender cannot cover it.
  *   - *`amount == 0` releases at the start of the next period, not now.* A zero entry for the next period leaves
  *     this period's larger stake as the `max`, so nothing is freed until this period ends.
  *   - *a stake earns only from the next period on.* The payout and the forging-weight charge both read the period
  *     they are in, and this transaction only ever writes the next one.
  *
  * `periodStart` has to name the next period exactly, the same rule and the same message as
  * CommitToGenerationTransactionDiff's. A stake for an arbitrary later period would sit unreachable behind
  * StakingPayout's carry-forward, and one for the current period would contradict "starting with the next epoch".
  */
object StakeTransactionDiff {
  def apply(blockchain: Blockchain)(tx: StakeTransaction): Either[ValidationError, StateSnapshot] = {
    val sender = tx.sender.toAddress

    for {
      current <- blockchain.currentGenerationPeriod.toRight(ActivationError("DeterministicFinality is not yet activated"))
      next = current.next
      _ <- Either.raiseUnless(tx.periodStart == next.start) {
        GenericError(s"Expected the next period start height ${next.start}, got ${tx.periodStart}")
      }
      snapshot <- StateSnapshot.build(
        blockchain,
        portfolios = Map(sender -> Portfolio(balance = -tx.fee.value)),
        nextStakes = Seq(StakeCommitment(sender, tx.periodStart, tx.amount.value))
      )
    } yield snapshot
  }
}
