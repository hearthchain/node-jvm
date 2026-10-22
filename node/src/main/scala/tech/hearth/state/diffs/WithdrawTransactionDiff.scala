package tech.hearth.state.diffs

import cats.syntax.either.*
import tech.hearth.lang.ValidationError
import tech.hearth.state.*
import tech.hearth.transaction.TxValidationError.GenericError
import tech.hearth.transaction.WithdrawTransaction

/** WithdrawTransaction semantics: the client asks for `amount` of what it reserved with `fromMiner` back, and gets it
  * at the first block of the period after next (WithdrawalPayout), not here. Until then the reservation stays open
  * to Settle in full, so work the enclave accepted before the miner saw this request is still paid for: the payout
  * returns at most what is unsettled by then.
  *
  * The miner does not have to be registered: a miner that stopped attesting can no longer settle at all, and must
  * not be able to keep its clients' reservations locked either.
  */
object WithdrawTransactionDiff {
  def apply(blockchain: Blockchain)(tx: WithdrawTransaction): Either[ValidationError, StateSnapshot] = {
    val client = tx.sender.toAddress
    val key    = (client, tx.fromMiner, tx.assetId)
    for {
      period <- blockchain.currentGenerationPeriod.toRight(GenericError("DeterministicFinality is not yet activated"))
      reserved = blockchain.reservedAmount(client, tx.fromMiner, tx.assetId)
      _ <- Either.raiseUnless(reserved > 0)(GenericError(s"$client has no open reservation with ${tx.fromMiner} for ${tx.assetId}"))
      // One at a time: a second request would either move the first one's payout or need its own, and neither buys
      // the client anything a request for the larger amount doesn't
      _ <- blockchain.pendingWithdrawal(client, tx.fromMiner, tx.assetId).toLeft(()).leftMap { p =>
        GenericError(s"$client already has a withdrawal pending from ${tx.fromMiner} for ${tx.assetId}, paid out at ${p.payoutAt}")
      }
      unsettled = blockchain.unsettledAmount(client, tx.fromMiner, tx.assetId)
      _ <- Either.raiseUnless(tx.amount.value <= unsettled) {
        GenericError(s"Withdrawal of ${tx.amount.value} from ${tx.fromMiner} for ${tx.assetId} exceeds the $unsettled still unsettled")
      }
      snapshot <- StateSnapshot.build(
        blockchain,
        portfolios = Map(client -> Portfolio.build(tx.feeAssetId, -tx.fee.value)),
        pendingWithdrawals = Map(key -> Some(PendingWithdrawal(tx.amount.value, period.next.next.start)))
      )
    } yield snapshot
  }
}
