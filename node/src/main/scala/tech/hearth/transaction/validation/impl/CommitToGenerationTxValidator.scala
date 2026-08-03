package tech.hearth.transaction.validation.impl

import cats.data.Validated.Valid
import tech.hearth.transaction.CommitToGenerationTransaction
import tech.hearth.transaction.validation.*

object CommitToGenerationTxValidator extends TxValidator[CommitToGenerationTransaction] {
  override def validate(tx: CommitToGenerationTransaction): ValidatedV[CommitToGenerationTransaction] =
    Valid(tx) // Nothing to validate
}
