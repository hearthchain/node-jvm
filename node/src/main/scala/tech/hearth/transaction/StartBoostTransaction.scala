package tech.hearth.transaction

import tech.hearth.account.*
import tech.hearth.common.state.ByteStr
import tech.hearth.lang.ValidationError
import tech.hearth.state.Height
import tech.hearth.transaction.serialization.impl.StartBoostTxSerializer
import tech.hearth.transaction.validation.TxValidator
import tech.hearth.transaction.validation.impl.StartBoostTxValidator
import monix.eval.Coeval
import play.api.libs.json.JsObject

/** See StartBoostTransactionDiff for the real semantics: verifying the embedded TDX quote end to end and
  * registering its enclave for one generation period.
  *
  * generationPeriodStart mirrors CommitToGenerationTransaction.generationPeriodStart: a StartBoost registers the
  * enclave for one generation period and must be resubmitted for the next one (see the DCAP consensus plan).
  */
final case class StartBoostTransaction(
    sender: PublicKey,
    validator: Address,
    tdxQuote: ByteStr,
    generationPeriodStart: Height,
    fee: TxPositiveAmount,
    timestamp: TxTimestamp,
    proofs: Proofs,
    networkId: NetworkId
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
      generationPeriodStart: Height,
      fee: Long,
      timestamp: TxTimestamp,
      proofs: Proofs,
      networkId: NetworkId = NetworkId.current
  ): Either[ValidationError, StartBoostTransaction] =
    for {
      fee <- TxPositiveAmount(fee)(TxValidationError.InsufficientFee)
      tx  <- StartBoostTransaction(sender, validator, tdxQuote, generationPeriodStart, fee, timestamp, proofs, networkId).validatedEither
    } yield tx
}
