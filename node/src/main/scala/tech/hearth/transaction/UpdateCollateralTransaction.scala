package tech.hearth.transaction

import tech.hearth.account.*
import tech.hearth.common.state.ByteStr
import tech.hearth.lang.ValidationError
import tech.hearth.transaction.serialization.impl.UpdateCollateralTxSerializer
import tech.hearth.transaction.validation.TxValidator
import tech.hearth.transaction.validation.impl.UpdateCollateralTxValidator
import monix.eval.Coeval
import play.api.libs.json.JsObject

/** Permissionless DCAP collateral upsert (see the StartBoost consensus plan). Every field is independently
  * optional so one transaction can refresh several collateral slots at once; validation requires at least one to
  * be set. Each payload is the raw Intel-signed collateral as fetched from Intel PCS: DER for the two CRLs, signed
  * JSON for tcbInfo/qeIdentity, a PEM issuer chain (TCB Signing Cert -> Intel Root CA) needed to verify either of
  * those two, and a second PEM issuer chain (PCK Platform/Processor CA -> Intel Root CA) needed to verify pckCrl -
  * both signed by an intermediate under Root CA, not by Root CA directly, so each needs its issuer submitted
  * alongside it to be verified eagerly here rather than deferred to whenever a quote happens to supply one.
  *
  * Semantics (signature verification, monotonic-freshness checks, state merge) are not implemented yet: see
  * TransactionDiffer, which has no case for this type yet and so rejects it with UnsupportedTransactionType. Only
  * the structural "at least one field set" check and wire-format (protobuf/JSON) plumbing exist so far.
  */
final case class UpdateCollateralTransaction(
    sender: PublicKey,
    rootCaCrl: Option[ByteStr],
    pckCrl: Option[ByteStr],
    tcbInfo: Option[ByteStr],
    qeIdentity: Option[ByteStr],
    tcbSigningIssuerChain: Option[ByteStr],
    pckCaIssuerChain: Option[ByteStr],
    fee: TxPositiveAmount,
    timestamp: TxTimestamp,
    proofs: Proofs,
    chainId: Byte
) extends Transaction(TransactionType.UpdateCollateral),
      ProvenTransaction,
      TxWithFee.InHearth,
      FastHashId {
  override type T = UpdateCollateralTransaction

  override val json: Coeval[JsObject] = Coeval.evalOnce(UpdateCollateralTxSerializer.toJson(this))

  override def addProof(proof: ByteStr): UpdateCollateralTransaction = copy(proofs = this.proofs.add(proof))
}

object UpdateCollateralTransaction {
  implicit val validator: TxValidator[UpdateCollateralTransaction] = UpdateCollateralTxValidator

  def create(
      sender: PublicKey,
      rootCaCrl: Option[ByteStr],
      pckCrl: Option[ByteStr],
      tcbInfo: Option[ByteStr],
      qeIdentity: Option[ByteStr],
      tcbSigningIssuerChain: Option[ByteStr],
      pckCaIssuerChain: Option[ByteStr],
      fee: Long,
      timestamp: TxTimestamp,
      proofs: Proofs,
      chainId: Byte = AddressScheme.current.chainId
  ): Either[ValidationError, UpdateCollateralTransaction] =
    for {
      fee <- TxPositiveAmount(fee)(TxValidationError.InsufficientFee)
      tx <- UpdateCollateralTransaction(
        sender,
        rootCaCrl,
        pckCrl,
        tcbInfo,
        qeIdentity,
        tcbSigningIssuerChain,
        pckCaIssuerChain,
        fee,
        timestamp,
        proofs,
        chainId
      ).validatedEither
    } yield tx
}
