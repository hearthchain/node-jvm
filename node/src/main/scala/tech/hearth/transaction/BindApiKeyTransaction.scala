package tech.hearth.transaction

import tech.hearth.account.*
import tech.hearth.common.state.ByteStr
import tech.hearth.lang.ValidationError
import tech.hearth.transaction.serialization.impl.BindApiKeyTxSerializer
import tech.hearth.transaction.validation.TxValidator
import tech.hearth.transaction.validation.impl.BindApiKeyTxValidator
import monix.eval.Coeval
import play.api.libs.json.JsObject

/** See BindApiKeyTransactionDiff for the real semantics: binds an HPKE-sealed API key envelope (encryptedApiKey,
  * opaque to the node) to a registered enclave's attestation public key.
  */
final case class BindApiKeyTransaction(
    sender: PublicKey,
    enclavePublicKey: ByteStr,
    encryptedApiKey: ByteStr,
    fee: TxPositiveAmount,
    timestamp: TxTimestamp,
    proofs: Proofs,
    networkId: NetworkId
) extends Transaction(TransactionType.BindApiKey),
      ProvenTransaction,
      TxWithFee.InHearth,
      FastHashId {
  override type T = BindApiKeyTransaction

  override val json: Coeval[JsObject] = Coeval.evalOnce(BindApiKeyTxSerializer.toJson(this))

  override def addProof(proof: ByteStr): BindApiKeyTransaction = copy(proofs = this.proofs.add(proof))
}

object BindApiKeyTransaction {
  implicit val validator: TxValidator[BindApiKeyTransaction] = BindApiKeyTxValidator

  def create(
      sender: PublicKey,
      enclavePublicKey: ByteStr,
      encryptedApiKey: ByteStr,
      fee: Long,
      timestamp: TxTimestamp,
      proofs: Proofs,
      networkId: NetworkId = NetworkId.current
  ): Either[ValidationError, BindApiKeyTransaction] =
    for {
      fee <- TxPositiveAmount(fee)(TxValidationError.InsufficientFee)
      tx  <- BindApiKeyTransaction(sender, enclavePublicKey, encryptedApiKey, fee, timestamp, proofs, networkId).validatedEither
    } yield tx
}
