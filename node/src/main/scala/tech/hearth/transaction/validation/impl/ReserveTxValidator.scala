package tech.hearth.transaction.validation.impl

import cats.data.Validated.Valid
import tech.hearth.transaction.ReserveTransaction
import tech.hearth.transaction.validation.*

object ReserveTxValidator extends TxValidator[ReserveTransaction] {
  override def validate(tx: ReserveTransaction): ValidatedV[ReserveTransaction] =
    Valid(tx) // Semantics not implemented yet; see TransactionDiffer
}
