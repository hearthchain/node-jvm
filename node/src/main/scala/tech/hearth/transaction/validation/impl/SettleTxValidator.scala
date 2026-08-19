package tech.hearth.transaction.validation.impl

import tech.hearth.crypto.SignatureLength
import tech.hearth.transaction.SettleTransaction
import tech.hearth.transaction.SettleTransaction.MaxSettlementCount
import tech.hearth.transaction.TxValidationError.GenericError
import tech.hearth.transaction.AssetIdLength
import tech.hearth.transaction.validation.*

object SettleTxValidator extends TxValidator[SettleTransaction] {
  override def validate(tx: SettleTransaction): ValidatedV[SettleTransaction] = {
    import tx.*
    V.seq(tx)(
      // A registered enclave public key is always a raw Ed25519 point, 32 bytes (see RegisteredEnclave) - a
      // different length can never match one, so reject it structurally rather than paying for the lookup.
      V.cond(enclavePublicKey.arr.length == 32, GenericError(s"enclavePublicKey length ${enclavePublicKey.arr.length} bytes, expected 32")),
      V.cond(
        enclaveSignature.arr.length == SignatureLength,
        GenericError(s"enclaveSignature length ${enclaveSignature.arr.length} bytes, expected $SignatureLength")
      ),
      V.cond(settlements.nonEmpty, GenericError("At least one settlement is required")),
      V.cond(
        settlements.length <= MaxSettlementCount,
        GenericError(s"Number of settlements ${settlements.length} is greater than $MaxSettlementCount")
      ),
      // mkSettlementMessage concatenates every settlement's fields with no length delimiter, relying on each one
      // being fixed-width (client(20) ++ assetId(32) ++ cumulativeSpent(8)) so the enclave-signed message has a
      // unique factorization. The wire format (PBAmounts.toVanillaAssetId) accepts an IssuedAsset id of any length,
      // so without this check a malicious sender (the operator relaying the batch, exactly the party the enclave
      // signature is meant to constrain) could submit a settlements list whose concatenated bytes collide with a
      // genuinely enclave-signed message for a *different* settlements list, and settle funds the enclave never
      // actually authorized.
      V.cond(
        settlements.forall(_.assetId.compatId.forall(_.arr.length == AssetIdLength)),
        GenericError(s"Every settlement's assetId must be $AssetIdLength bytes")
      )
    )
  }
}
