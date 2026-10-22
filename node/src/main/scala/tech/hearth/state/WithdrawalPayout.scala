package tech.hearth.state

import cats.syntax.either.*
import tech.hearth.account.Address
import tech.hearth.lang.ValidationError
import tech.hearth.transaction.TxValidationError.GenericError

/** Pays out the Withdraw requests due at the first block of a period (see WithdrawTransactionDiff).
  *
  * Each request returns what is still unsettled of the reservation, up to the amount asked for: whatever the miner
  * settled since the request landed comes off first. That is the point of the delay - the miner has had at least a
  * whole period to settle work it accepted before it saw the request, and the client never gets back funds the
  * enclave already spent. The request is cleared either way, even when nothing is left to pay.
  */
object WithdrawalPayout {

  /** The snapshot this height contributes, or [[StateSnapshot.empty]] when nothing is due at it.
    *
    * @param blockchain
    *   positioned at the block *before* `newBlockHeight`.
    * @param newBlockHeight
    *   the height of the block being built.
    */
  def atPeriodBoundary(blockchain: Blockchain, newBlockHeight: Height): Either[ValidationError, StateSnapshot] = {
    val due =
      if (!blockchain.generationPeriodOf(newBlockHeight).exists(_.start == newBlockHeight)) Seq.empty
      else
        blockchain.withdrawalsDue(newBlockHeight).flatMap { case key @ (client, miner, asset) =>
          blockchain.pendingWithdrawal(client, miner, asset).filter(_.payoutAt == newBlockHeight).map(key -> _)
        }

    if (due.isEmpty) Right(StateSnapshot.empty)
    else {
      val paid = due.map { case (key @ (client, miner, asset), pending) =>
        val withdrawn = blockchain.withdrawnAmount(client, miner, asset)
        // Settle keeps settled + withdrawn within reserved, so this is never negative, and neither sum can overflow
        val unsettled = blockchain.reservedAmount(client, miner, asset) - blockchain.settledAmount(client, miner, asset) - withdrawn
        (key, withdrawn, math.min(pending.amount, unsettled))
      }
      for {
        portfolios <- paid
          .collect { case ((client, _, asset), _, amount) if amount > 0 => Map(client -> Portfolio.build(asset, amount)) }
          .foldLeft(Map.empty[Address, Portfolio].asRight[String])((acc, p) => acc.flatMap(Portfolio.combine(_, p)))
          .leftMap(GenericError(_))
        snapshot <- StateSnapshot.build(
          blockchain,
          portfolios = portfolios,
          // Nothing paid writes no withdrawn entry, keeping no-op writes out of the state hash
          withdrawnAmounts = paid.collect { case (key, withdrawn, amount) if amount > 0 => key -> (withdrawn + amount) }.toMap,
          pendingWithdrawals = due.map { case (key, _) => key -> None }.toMap
        )
      } yield snapshot
    }
  }
}
