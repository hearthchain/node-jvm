package tech.hearth.state.diffs

import tech.hearth.lang.ValidationError
import tech.hearth.state.*
import tech.hearth.transaction.lease.*

object LeaseTransactionsDiff {
  def lease(blockchain: Blockchain)(tx: LeaseTransaction): Either[ValidationError, StateSnapshot] =
    DiffsCommon
      .processLease(blockchain, tx.amount, tx.sender, tx.recipient, tx.fee.value, tx.id(), TransactionId(tx.id()))

  def leaseCancel(blockchain: Blockchain)(tx: LeaseCancelTransaction): Either[ValidationError, StateSnapshot] =
    DiffsCommon
      .processLeaseCancel(blockchain, tx.sender, tx.fee.value, tx.leaseId, tx.id())
}
