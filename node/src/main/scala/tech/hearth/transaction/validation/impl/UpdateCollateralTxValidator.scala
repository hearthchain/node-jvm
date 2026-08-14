package tech.hearth.transaction.validation.impl

import tech.hearth.crypto.dcap.IntelPki.MaxCollateralFieldSize
import tech.hearth.transaction.TxValidationError.{GenericError, TooBigInBytes}
import tech.hearth.transaction.UpdateCollateralTransaction
import tech.hearth.transaction.validation.*

object UpdateCollateralTxValidator extends TxValidator[UpdateCollateralTransaction] {
  override def validate(tx: UpdateCollateralTransaction): ValidatedV[UpdateCollateralTransaction] = {
    import tx.*
    val fields    = Seq(rootCaCrl, pckCrl, tcbInfo, qeIdentity, tcbSigningIssuerChain, pckCaIssuerChain)
    val oversized = fields.flatten.find(_.arr.length > MaxCollateralFieldSize)
    V.seq(tx)(
      V.cond(
        fields.exists(_.isDefined),
        GenericError("UpdateCollateral transaction must set at least one field")
      ),
      // Bounds expensive X.509/JSON parsing work regardless of submission path (REST/gRPC/P2P) - the REST JSON
      // layer has its own, separate decode limit (api.http.requests.LargeBlobDecodeLimit), sized the same.
      V.cond(
        oversized.isEmpty,
        TooBigInBytes(
          s"UpdateCollateral field length ${oversized.map(_.arr.length).getOrElse(0)} bytes exceeds maximum of $MaxCollateralFieldSize bytes."
        )
      )
    )
  }
}
