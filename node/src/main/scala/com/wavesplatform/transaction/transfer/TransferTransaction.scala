package com.wavesplatform.transaction.transfer

import com.wavesplatform.account.*
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.lang.ValidationError
import com.wavesplatform.transaction.*
import com.wavesplatform.transaction.serialization.impl.TransferTxSerializer
import com.wavesplatform.transaction.validation.*
import com.wavesplatform.transaction.validation.impl.TransferTxValidator
import com.wavesplatform.utils.base58Length
import monix.eval.Coeval
import play.api.libs.json.JsObject

case class TransferTransaction(
    version: TxVersion,
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
  val MaxAttachmentStringSize: Int = base58Length(MaxAttachmentSize)

  val typeId: TxType = 4: Byte

  implicit val validator: TxValidator[TransferTransaction] = TransferTxValidator

  def create(
      version: TxVersion,
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
      amount <- TxPositiveAmount(amount)(TxValidationError.NonPositiveAmount(amount, asset.maybeBase58Repr.getOrElse("waves")))
      fee    <- TxPositiveAmount(fee)(TxValidationError.InsufficientFee)
      tx     <- TransferTransaction(version, sender, recipient, asset, amount, feeAsset, fee, attachment, timestamp, proofs, chainId).validatedEither
    } yield tx
}
