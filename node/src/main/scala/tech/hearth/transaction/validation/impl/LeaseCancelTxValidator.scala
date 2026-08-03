package tech.hearth.transaction.validation.impl

import cats.data.ValidatedNel
import cats.syntax.either.*
import tech.hearth.common.state.ByteStr
import tech.hearth.crypto
import tech.hearth.lang.ValidationError
import tech.hearth.transaction.TxValidationError.GenericError
import tech.hearth.transaction.lease.LeaseCancelTransaction
import tech.hearth.transaction.validation.TxValidator

object LeaseCancelTxValidator extends TxValidator[LeaseCancelTransaction] {
  override def validate(tx: LeaseCancelTransaction): ValidatedNel[ValidationError, LeaseCancelTransaction] = {
    import tx.*
    V.seq(tx)(
      checkLeaseId(leaseId).toValidatedNel
    )
  }

  def checkLeaseId(leaseId: ByteStr): Either[GenericError, Unit] =
    Either.cond(
      leaseId.arr.length == crypto.DigestLength,
      (),
      GenericError(s"Lease id=$leaseId has invalid length = ${leaseId.arr.length} byte(s) while expecting ${crypto.DigestLength}")
    )
}
