package tech.hearth.transaction.transfer

import tech.hearth.account.*
import tech.hearth.common.state.ByteStr
import tech.hearth.lang.ValidationError
import tech.hearth.transaction.*
import tech.hearth.transaction.serialization.impl.TransferTxSerializer
import tech.hearth.transaction.validation.*
import tech.hearth.transaction.validation.impl.TransferTxValidator
import tech.hearth.utils.base16Length
import monix.eval.Coeval
import play.api.libs.json.JsObject

case class TransferTransaction(
    sender: PublicKey,
    recipient: Address,
    assetId: Asset,
    amount: TxPositiveAmount,
    feeAssetId: Asset,
    fee: TxPositiveAmount,
    attachment: ByteStr,
    timestamp: TxTimestamp,
    proofs: Proofs,
    chainId: Byte
) extends Transaction(TransactionType.Transfer),
      ProvenTransaction,
      FastHashId,
      TxWithFee.InCustomAsset {
  override type T = TransferTransaction

  final val json: Coeval[JsObject] = Coeval.evalOnce(TransferTxSerializer.toJson(this))

  override def addProof(proof: ByteStr): TransferTransaction = copy(proofs = this.proofs.add(proof))
}

object TransferTransaction {
  type TransactionT = TransferTransaction

  val MaxAttachmentSize            = 140
  val MaxAttachmentStringSize: Int = base16Length(MaxAttachmentSize)

  implicit val validator: TxValidator[TransferTransaction] = TransferTxValidator

  def create(
      sender: PublicKey,
      recipient: Address,
      asset: Asset,
      amount: Long,
      feeAsset: Asset,
      fee: Long,
      attachment: ByteStr,
      timestamp: TxTimestamp,
      proofs: Proofs,
      chainId: Byte = AddressScheme.current.chainId
  ): Either[ValidationError, TransferTransaction] =
    for {
      amount <- TxPositiveAmount(amount)(TxValidationError.NonPositiveAmount(amount, asset.maybeBase16Repr.getOrElse("hearth")))
      fee    <- TxPositiveAmount(fee)(TxValidationError.InsufficientFee)
      tx     <- TransferTransaction(sender, recipient, asset, amount, feeAsset, fee, attachment, timestamp, proofs, chainId).validatedEither
    } yield tx
}
