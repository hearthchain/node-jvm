package tech.hearth.transaction

import tech.hearth.account.*
import tech.hearth.common.state.ByteStr
import tech.hearth.lang.ValidationError
import tech.hearth.transaction.Asset.IssuedAsset
import tech.hearth.transaction.serialization.impl.ReserveTxSerializer
import tech.hearth.transaction.validation.TxValidator
import tech.hearth.transaction.validation.impl.ReserveTxValidator
import monix.eval.Coeval
import play.api.libs.json.JsObject

/** See ReserveTransactionDiff for the real semantics: locking `amount` of `assetId` against a registered miner,
  * accumulating into Blockchain.reservedAmount(sender, miner, assetId). Accumulate-only for now - there is no
  * unreserve transaction yet (SettleTransaction only ever compares against the reserved total).
  *
  * `assetId` is an IssuedAsset, not an Asset: Hearth is not reservable, matching SettleTransaction.Settlement,
  * which is the only way a reservation is ever drawn down. `feeAssetId` is unconstrained - a fee is payable in
  * Hearth like every other transaction's.
  */
final case class ReserveTransaction(
    sender: PublicKey,
    assetId: IssuedAsset,
    amount: TxPositiveAmount,
    miner: Address,
    feeAssetId: Asset,
    fee: TxPositiveAmount,
    timestamp: TxTimestamp,
    proofs: Proofs,
    networkId: NetworkId
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
      assetId: IssuedAsset,
      amount: Long,
      miner: Address,
      feeAssetId: Asset,
      fee: Long,
      timestamp: TxTimestamp,
      proofs: Proofs,
      networkId: NetworkId = NetworkId.current
  ): Either[ValidationError, ReserveTransaction] =
    for {
      amount <- TxPositiveAmount(amount)(TxValidationError.NonPositiveAmount(amount, assetId.id.toString))
      fee    <- TxPositiveAmount(fee)(TxValidationError.InsufficientFee)
      tx     <- ReserveTransaction(sender, assetId, amount, miner, feeAssetId, fee, timestamp, proofs, networkId).validatedEither
    } yield tx
}
