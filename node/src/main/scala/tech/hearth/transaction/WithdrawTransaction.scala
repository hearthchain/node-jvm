package tech.hearth.transaction

import tech.hearth.account.*
import tech.hearth.common.state.ByteStr
import tech.hearth.lang.ValidationError
import tech.hearth.transaction.serialization.impl.WithdrawTxSerializer
import tech.hearth.transaction.validation.TxValidator
import tech.hearth.transaction.validation.impl.WithdrawTxValidator
import monix.eval.Coeval
import play.api.libs.json.JsObject

/** Not yet semantically implemented: see TransactionDiffer, which has no case for this type yet and so rejects it
  * with UnsupportedTransactionType. Only wire-format (protobuf/JSON) plumbing exists so far.
  */
final case class WithdrawTransaction(
    sender: PublicKey,
    fromMiner: Address,
    assetId: Asset,
    amount: TxPositiveAmount,
    feeAssetId: Asset,
    fee: TxPositiveAmount,
    timestamp: TxTimestamp,
    proofs: Proofs,
    chainId: Byte
) extends Transaction(TransactionType.Withdraw),
      ProvenTransaction,
      TxWithFee.InCustomAsset,
      FastHashId {
  override type T = WithdrawTransaction

  override val json: Coeval[JsObject] = Coeval.evalOnce(WithdrawTxSerializer.toJson(this))

  override def addProof(proof: ByteStr): WithdrawTransaction = copy(proofs = this.proofs.add(proof))
}

object WithdrawTransaction {
  implicit val validator: TxValidator[WithdrawTransaction] = WithdrawTxValidator

  def create(
      sender: PublicKey,
      fromMiner: Address,
      assetId: Asset,
      amount: Long,
      feeAssetId: Asset,
      fee: Long,
      timestamp: TxTimestamp,
      proofs: Proofs,
      chainId: Byte = AddressScheme.current.chainId
  ): Either[ValidationError, WithdrawTransaction] =
    for {
      amount <- TxPositiveAmount(amount)(TxValidationError.NonPositiveAmount(amount, assetId.maybeBase16Repr.getOrElse("hearth")))
      fee    <- TxPositiveAmount(fee)(TxValidationError.InsufficientFee)
      tx     <- WithdrawTransaction(sender, fromMiner, assetId, amount, feeAssetId, fee, timestamp, proofs, chainId).validatedEither
    } yield tx
}
