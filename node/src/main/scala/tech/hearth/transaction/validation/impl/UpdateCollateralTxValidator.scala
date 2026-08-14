package tech.hearth.transaction.validation.impl

import tech.hearth.crypto.dcap.IntelPki.MaxCollateralFieldSize
import tech.hearth.transaction.TxValidationError.GenericError
import tech.hearth.transaction.UpdateCollateralTransaction
import tech.hearth.transaction.validation.*

object UpdateCollateralTxValidator extends TxValidator[UpdateCollateralTransaction] {
  override def validate(tx: UpdateCollateralTransaction): ValidatedV[UpdateCollateralTransaction] = {
    import tx.*
    val fields = Seq(rootCaCrl, pckCrl, tcbInfo, qeIdentity, tcbSigningIssuerChain, pckCaIssuerChain)
    V.seq(tx)(
      V.cond(
        fields.exists(_.isDefined),
        GenericError("UpdateCollateral transaction must set at least one field")
      ),
      // Bounds expensive X.509/JSON parsing work regardless of submission path (REST/gRPC/P2P) - the REST JSON
      // layer has its own, separate decode limit (api.http.requests.LargeBlobDecodeLimit), sized the same.
      V.cond(
        fields.flatten.forall(_.arr.length <= MaxCollateralFieldSize),
        GenericError(s"UpdateCollateral field exceeds the $MaxCollateralFieldSize byte limit")
      )
    )
  }
}
