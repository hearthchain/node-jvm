package tech.hearth.transaction

import tech.hearth.account.*
import tech.hearth.common.state.ByteStr
import tech.hearth.lang.ValidationError
import tech.hearth.transaction.Asset.IssuedAsset
import tech.hearth.transaction.serialization.impl.WithdrawTxSerializer
import tech.hearth.transaction.validation.TxValidator
import tech.hearth.transaction.validation.impl.WithdrawTxValidator
import monix.eval.Coeval
import play.api.libs.json.JsObject

/** Asks for `amount` of what the sender reserved with `fromMiner` (ReserveTransaction) back. See
  * WithdrawTransactionDiff: the request is recorded here and paid out two period boundaries later, by
  * WithdrawalPayout, so that the miner can still settle work it accepted before it saw the request.
  *
  * `assetId` is an IssuedAsset for the same reason ReserveTransaction's is: Hearth is not reservable.
  */
final case class WithdrawTransaction(
    sender: PublicKey,
    fromMiner: Address,
    assetId: IssuedAsset,
    amount: TxPositiveAmount,
    feeAssetId: Asset,
    fee: TxPositiveAmount,
    timestamp: TxTimestamp,
    proofs: Proofs,
    networkId: NetworkId
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
      assetId: IssuedAsset,
      amount: Long,
      feeAssetId: Asset,
      fee: Long,
      timestamp: TxTimestamp,
      proofs: Proofs,
      networkId: NetworkId = NetworkId.current
  ): Either[ValidationError, WithdrawTransaction] =
    for {
      amount <- TxPositiveAmount(amount)(TxValidationError.NonPositiveAmount(amount, assetId.id.toString))
      fee    <- TxPositiveAmount(fee)(TxValidationError.InsufficientFee)
      tx     <- WithdrawTransaction(sender, fromMiner, assetId, amount, feeAssetId, fee, timestamp, proofs, networkId).validatedEither
    } yield tx
}
