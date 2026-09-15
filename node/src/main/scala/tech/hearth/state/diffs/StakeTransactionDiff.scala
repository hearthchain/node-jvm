package tech.hearth.state.diffs

import cats.syntax.either.*
import tech.hearth.lang.ValidationError
import tech.hearth.state.*
import tech.hearth.transaction.StakeTransaction
import tech.hearth.transaction.TxValidationError.{ActivationError, GenericError}

/** StakeTransaction semantics: set the sender's HRTH stake, effective from the next generation period.
  *
  * The transaction writes `tx.amount` into the sender's [[StakeRecord.pending]] half, leaving [[StakeRecord.active]]
  * - what the current period pays out on - untouched. Three of the four required behaviours fall straight out of
  * that one write:
  *
  *   - *the last one wins.* Several StakeTransactions in one period each overwrite `pending`, so the last one
  *     applied is what the boundary activates. Nothing needs to detect or reject the earlier ones.
  *   - *HRTH is reserved immediately.* [[StakeRecord.locked]] is `max(active, pending)`, so raising the stake locks
  *     the larger amount as soon as this snapshot applies - BalanceDiffValidation reads it through
  *     `Blockchain.lockedStake` and rejects the transaction outright if the sender cannot cover it.
  *   - *`amount == 0` releases at the start of the next period, not now.* Lowering the stake leaves `active` the
  *     larger of the two, so `locked` does not move until BlockDiffer's period-boundary hook runs
  *     [[StakeRecord.activated]] and the two converge.
  *
  * The fourth - that a stake earns only from the next period on - is the payout's side of the same split: it reads
  * `active`, which this transaction never touches, so a stake set during period E cannot earn for E.
  *
  * `periodStart` has to name the next period exactly, the same rule and the same message shape as
  * CommitToGenerationTransactionDiff's. Accepting an arbitrary future period would mean storing more than one
  * pending amount per address, and accepting the current one would contradict "starting with the next epoch".
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
      before         = blockchain.stake(sender)
      updated        = before.copy(pending = tx.amount.value)
      (joined, left) = StakeRecord.membership(Seq(sender -> (before, updated)))
      snapshot <- StateSnapshot.build(
        blockchain,
        portfolios = Map(sender -> Portfolio(balance = -tx.fee.value)),
        stakes = Map(sender -> updated),
        stakersJoined = joined,
        stakersLeft = left
      )
    } yield snapshot
  }

}
