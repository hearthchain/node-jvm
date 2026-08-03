package tech.hearth.transaction.validation.impl

import cats.data.ValidatedNel
import tech.hearth.lang.ValidationError
import tech.hearth.transaction.transfer.TransferTransaction
import tech.hearth.transaction.validation.TxValidator

object TransferTxValidator extends TxValidator[TransferTransaction] {
  override def validate(transaction: TransferTransaction): ValidatedNel[ValidationError, TransferTransaction] = {
    import transaction.*
    V.seq(transaction)(
      V.transferAttachment(attachment)
    )
  }
}
