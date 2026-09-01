package tech.hearth.transaction.validation.impl

import tech.hearth.transaction.AssetIdLength
import tech.hearth.transaction.ReserveTransaction
import tech.hearth.transaction.TxValidationError.GenericError
import tech.hearth.transaction.validation.*

object ReserveTxValidator extends TxValidator[ReserveTransaction] {
  override def validate(tx: ReserveTransaction): ValidatedV[ReserveTransaction] =
    // An asset id of any other length can never name an issued asset, so reject it structurally rather than paying
    // for the state lookup ReserveTransactionDiff would otherwise do. Everything else is bounds-checked by
    // TxPositiveAmount; see ReserveTransactionDiff for the real semantics.
    V.seq(tx)(
      V.cond(tx.assetId.id.arr.length == AssetIdLength, GenericError(s"assetId must be $AssetIdLength bytes"))
    )
}
