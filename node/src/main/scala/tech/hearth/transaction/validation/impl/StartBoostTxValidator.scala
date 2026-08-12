package tech.hearth.transaction.validation.impl

import cats.data.Validated.Valid
import tech.hearth.transaction.StartBoostTransaction
import tech.hearth.transaction.validation.*

object StartBoostTxValidator extends TxValidator[StartBoostTransaction] {
  override def validate(tx: StartBoostTransaction): ValidatedV[StartBoostTransaction] =
    Valid(tx) // Semantics not implemented yet; see TransactionDiffer
}
