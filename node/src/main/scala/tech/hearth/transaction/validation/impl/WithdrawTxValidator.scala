package tech.hearth.transaction.validation.impl

import tech.hearth.transaction.AssetIdLength
import tech.hearth.transaction.WithdrawTransaction
import tech.hearth.transaction.TxValidationError.GenericError
import tech.hearth.transaction.validation.*

object WithdrawTxValidator extends TxValidator[WithdrawTransaction] {
  override def validate(tx: WithdrawTransaction): ValidatedV[WithdrawTransaction] =
    // Same structural check as ReserveTxValidator: no reservation can exist for an asset id of another length. See
    // WithdrawTransactionDiff for the real semantics.
    V.seq(tx)(
      V.cond(tx.assetId.id.arr.length == AssetIdLength, GenericError(s"assetId must be $AssetIdLength bytes"))
    )
}
