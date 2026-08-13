package tech.hearth.transaction.validation.impl

import cats.data.Validated.Valid
import tech.hearth.transaction.SettleTransaction
import tech.hearth.transaction.validation.*

object SettleTxValidator extends TxValidator[SettleTransaction] {
  override def validate(tx: SettleTransaction): ValidatedV[SettleTransaction] =
    Valid(tx) // Semantics not implemented yet; see TransactionDiffer
}
