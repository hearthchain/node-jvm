package tech.hearth.transaction.validation.impl

import tech.hearth.crypto.dcap.IntelPki.MaxCollateralFieldSize
import tech.hearth.transaction.BindApiKeyTransaction
import tech.hearth.transaction.TxValidationError.{GenericError, TooBigInBytes}
import tech.hearth.transaction.validation.*

object BindApiKeyTxValidator extends TxValidator[BindApiKeyTransaction] {
  override def validate(tx: BindApiKeyTransaction): ValidatedV[BindApiKeyTransaction] = {
    import tx.*
    V.seq(tx)(
      // A registered attestation public key is always a raw P-256 point, 64 bytes (see RegisteredEnclave) - a
      // different length can never match one, so reject it structurally rather than paying for the lookup.
      V.cond(enclavePublicKey.arr.length == 64, GenericError(s"enclavePublicKey length ${enclavePublicKey.arr.length} bytes, expected 64")),
      // Bounds mempool/block/storage space regardless of submission path (REST/gRPC/P2P) - the REST JSON layer has
      // its own, separate decode limit (api.http.requests.LargeBlobDecodeLimit), sized the same. Reused from DCAP
      // collateral rather than introducing a new constant: an HPKE-sealed API key envelope is always far smaller
      // than this, so it's a loose but sufficient bound.
      V.cond(
        encryptedApiKey.arr.length <= MaxCollateralFieldSize,
        TooBigInBytes(s"encryptedApiKey length ${encryptedApiKey.arr.length} bytes exceeds maximum of $MaxCollateralFieldSize bytes.")
      )
    )
  }
}
