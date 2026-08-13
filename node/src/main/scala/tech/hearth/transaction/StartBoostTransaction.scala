package tech.hearth.transaction

import tech.hearth.account.*
import tech.hearth.common.state.ByteStr
import tech.hearth.lang.ValidationError
import tech.hearth.transaction.serialization.impl.StartBoostTxSerializer
import tech.hearth.transaction.validation.TxValidator
import tech.hearth.transaction.validation.impl.StartBoostTxValidator
import monix.eval.Coeval
import play.api.libs.json.JsObject

/** Not yet semantically implemented: see TransactionDiffer, which has no case for this type yet and so rejects it
  * with UnsupportedTransactionType. Only wire-format (protobuf/JSON) plumbing exists so far.
  */
final case class StartBoostTransaction(
    sender: PublicKey,
    validator: Address,
    tdxQuote: ByteStr,
    fee: TxPositiveAmount,
    timestamp: TxTimestamp,
    proofs: Proofs,
    chainId: Byte
) extends Transaction(TransactionType.StartBoost),
      ProvenTransaction,
      TxWithFee.InHearth,
      FastHashId {
  override type T = StartBoostTransaction

  override val json: Coeval[JsObject] = Coeval.evalOnce(StartBoostTxSerializer.toJson(this))

  override def addProof(proof: ByteStr): StartBoostTransaction = copy(proofs = this.proofs.add(proof))
}

object StartBoostTransaction {
  implicit val validator: TxValidator[StartBoostTransaction] = StartBoostTxValidator

  def create(
      sender: PublicKey,
      validator: Address,
      tdxQuote: ByteStr,
      fee: Long,
      timestamp: TxTimestamp,
      proofs: Proofs,
      chainId: Byte = AddressScheme.current.chainId
  ): Either[ValidationError, StartBoostTransaction] =
    for {
      fee <- TxPositiveAmount(fee)(TxValidationError.InsufficientFee)
      tx  <- StartBoostTransaction(sender, validator, tdxQuote, fee, timestamp, proofs, chainId).validatedEither
    } yield tx
}
