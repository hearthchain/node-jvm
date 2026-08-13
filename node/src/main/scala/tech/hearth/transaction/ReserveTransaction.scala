package tech.hearth.transaction

import tech.hearth.account.*
import tech.hearth.common.state.ByteStr
import tech.hearth.lang.ValidationError
import tech.hearth.transaction.serialization.impl.ReserveTxSerializer
import tech.hearth.transaction.validation.TxValidator
import tech.hearth.transaction.validation.impl.ReserveTxValidator
import monix.eval.Coeval
import play.api.libs.json.JsObject

/** Not yet semantically implemented: see TransactionDiffer, which has no case for this type yet and so rejects it
  * with UnsupportedTransactionType. Only wire-format (protobuf/JSON) plumbing exists so far.
  */
final case class ReserveTransaction(
    sender: PublicKey,
    assetId: Asset,
    amount: TxPositiveAmount,
    miner: Address,
    feeAssetId: Asset,
    fee: TxPositiveAmount,
    timestamp: TxTimestamp,
    proofs: Proofs,
    chainId: Byte
) extends Transaction(TransactionType.Reserve),
      ProvenTransaction,
      TxWithFee.InCustomAsset,
      FastHashId {
  override type T = ReserveTransaction

  override val json: Coeval[JsObject] = Coeval.evalOnce(ReserveTxSerializer.toJson(this))

  override def addProof(proof: ByteStr): ReserveTransaction = copy(proofs = this.proofs.add(proof))
}

object ReserveTransaction {
  implicit val validator: TxValidator[ReserveTransaction] = ReserveTxValidator

  def create(
      sender: PublicKey,
      assetId: Asset,
      amount: Long,
      miner: Address,
      feeAssetId: Asset,
      fee: Long,
      timestamp: TxTimestamp,
      proofs: Proofs,
      chainId: Byte = AddressScheme.current.chainId
  ): Either[ValidationError, ReserveTransaction] =
    for {
      amount <- TxPositiveAmount(amount)(TxValidationError.NonPositiveAmount(amount, assetId.maybeBase16Repr.getOrElse("hearth")))
      fee    <- TxPositiveAmount(fee)(TxValidationError.InsufficientFee)
      tx     <- ReserveTransaction(sender, assetId, amount, miner, feeAssetId, fee, timestamp, proofs, chainId).validatedEither
    } yield tx
}
