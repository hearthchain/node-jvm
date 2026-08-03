package tech.hearth.transaction.validation.impl

import cats.data.ValidatedNel
import tech.hearth.lang.ValidationError
import tech.hearth.transaction.lease.LeaseTransaction
import tech.hearth.transaction.validation.TxValidator
import tech.hearth.transaction.{TxPositiveAmount, TxValidationError}

object LeaseTxValidator extends TxValidator[LeaseTransaction] {
  override def validate(tx: LeaseTransaction): ValidatedNel[ValidationError, LeaseTransaction] = {
    import tx.*
    V.seq(tx)(
      V.noOverflow(amount.value, fee.value),
      V.cond(sender.toAddress != recipient, TxValidationError.ToSelf)
    )
  }

  def validateAmount(amount: Long): Either[ValidationError, TxPositiveAmount] =
    TxPositiveAmount.from(amount).left.map[ValidationError](_ => TxValidationError.NonPositiveAmount(amount, "waves"))
}
