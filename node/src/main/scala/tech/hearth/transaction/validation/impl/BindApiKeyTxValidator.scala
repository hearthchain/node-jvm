package tech.hearth.transaction.validation.impl

import cats.data.Validated.Valid
import tech.hearth.transaction.BindApiKeyTransaction
import tech.hearth.transaction.validation.*

object BindApiKeyTxValidator extends TxValidator[BindApiKeyTransaction] {
  override def validate(tx: BindApiKeyTransaction): ValidatedV[BindApiKeyTransaction] =
    Valid(tx) // Semantics not implemented yet; see TransactionDiffer
}
