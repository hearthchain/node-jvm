package tech.hearth.transaction.validation.impl

import tech.hearth.transaction.TxValidationError.GenericError
import tech.hearth.transaction.UpdateCollateralTransaction
import tech.hearth.transaction.validation.*

object UpdateCollateralTxValidator extends TxValidator[UpdateCollateralTransaction] {
  override def validate(tx: UpdateCollateralTransaction): ValidatedV[UpdateCollateralTransaction] = {
    import tx.*
    V.seq(tx)(
      V.cond(
        Seq(rootCaCrl, pckCrl, tcbInfo, qeIdentity, tcbSigningIssuerChain).exists(_.isDefined),
        GenericError("UpdateCollateral transaction must set at least one field")
      )
    )
  }
}
