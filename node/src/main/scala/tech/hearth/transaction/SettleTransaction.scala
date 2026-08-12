package tech.hearth.transaction

import tech.hearth.account.*
import tech.hearth.common.state.ByteStr
import tech.hearth.lang.ValidationError
import tech.hearth.transaction.serialization.impl.SettleTxSerializer
import tech.hearth.transaction.validation.TxValidator
import tech.hearth.transaction.validation.impl.SettleTxValidator
import monix.eval.Coeval
import play.api.libs.json.JsObject

/** Not yet semantically implemented: see TransactionDiffer, which has no case for this type yet and so rejects it
  * with UnsupportedTransactionType. Only wire-format (protobuf/JSON) plumbing exists so far.
  */
final case class SettleTransaction(
    sender: PublicKey,
    senderAddress: Address,
    fee: TxPositiveAmount,
    timestamp: TxTimestamp,
    proofs: Proofs,
    chainId: Byte
) extends Transaction(TransactionType.Settle),
      ProvenTransaction,
      TxWithFee.InHearth,
      FastHashId {
  override type T = SettleTransaction

  override val json: Coeval[JsObject] = Coeval.evalOnce(SettleTxSerializer.toJson(this))

  override def addProof(proof: ByteStr): SettleTransaction = copy(proofs = this.proofs.add(proof))
}

object SettleTransaction {
  implicit val validator: TxValidator[SettleTransaction] = SettleTxValidator

  def create(
      sender: PublicKey,
      senderAddress: Address,
      fee: Long,
      timestamp: TxTimestamp,
      proofs: Proofs,
      chainId: Byte = AddressScheme.current.chainId
  ): Either[ValidationError, SettleTransaction] =
    for {
      fee <- TxPositiveAmount(fee)(TxValidationError.InsufficientFee)
      tx  <- SettleTransaction(sender, senderAddress, fee, timestamp, proofs, chainId).validatedEither
    } yield tx
}
