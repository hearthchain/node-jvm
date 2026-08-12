package tech.hearth.transaction.validation.impl

import cats.data.Validated.Valid
import tech.hearth.transaction.WithdrawTransaction
import tech.hearth.transaction.validation.*

object WithdrawTxValidator extends TxValidator[WithdrawTransaction] {
  override def validate(tx: WithdrawTransaction): ValidatedV[WithdrawTransaction] =
    Valid(tx) // Semantics not implemented yet; see TransactionDiffer
}
