package tech.hearth.transaction.transfer

import cats.instances.list.*
import cats.syntax.traverse.*
import tech.hearth.account.*
import tech.hearth.common.state.ByteStr
import tech.hearth.lang.ValidationError
import tech.hearth.transaction.*
import tech.hearth.transaction.Asset.Hearth
import tech.hearth.transaction.TxValidationError.*
import tech.hearth.transaction.serialization.impl.TransferTxSerializer
import tech.hearth.transaction.transfer.TransferTransaction.ParsedTransfer
import tech.hearth.transaction.validation.TxValidator
import tech.hearth.transaction.validation.impl.TransferTxValidator
import tech.hearth.utils.base16Length
import monix.eval.Coeval
import play.api.libs.json.{JsObject, Json, OFormat}

import scala.util.Either

case class TransferTransaction(
    sender: PublicKey,
    assetId: Asset,
    transfers: Seq[ParsedTransfer],
    fee: TxPositiveAmount,
    feeAssetId: Asset,
    timestamp: TxTimestamp,
    attachment: ByteStr,
    proofs: Proofs,
    networkId: NetworkId
) extends Transaction(TransactionType.Transfer),
      ProvenTransaction,
      TxWithFee.InCustomAsset,
      FastHashId {
  override type T = TransferTransaction

  override val json: Coeval[JsObject] = Coeval.evalOnce(TransferTxSerializer.toJson(this))

  override def addProof(proof: ByteStr): TransferTransaction = copy(proofs = this.proofs.add(proof))

  def compactJson(recipient: Address): JsObject =
    json() ++ Json.obj(
      "transfers" -> TransferTxSerializer.transfersJson(transfers.filter(_.address == recipient))
    )
}

object TransferTransaction {
  type TransactionT = TransferTransaction

  val MaxAttachmentSize            = 140
  val MaxAttachmentStringSize: Int = base16Length(MaxAttachmentSize)
  val MaxTransferCount             = 100

  val typeId: TxType = TransactionType.Transfer.id.toByte

  implicit val validator: TxValidator[TransferTransaction] = TransferTxValidator

  case class Transfer(
      recipient: String,
      amount: Long
  )

  object Transfer {
    implicit val jsonFormat: OFormat[Transfer] = Json.format[Transfer]
  }

  case class ParsedTransfer(address: Address, amount: TxNonNegativeAmount)

  def create(
      sender: PublicKey,
      assetId: Asset,
      transfers: Seq[ParsedTransfer],
      fee: Long,
      timestamp: TxTimestamp,
      attachment: ByteStr,
      proofs: Proofs,
      networkId: NetworkId = NetworkId.current,
      feeAssetId: Asset = Hearth
  ): Either[ValidationError, TransferTransaction] =
    for {
      fee <- TxPositiveAmount(fee)(TxValidationError.InsufficientFee)
      tx  <- TransferTransaction(sender, assetId, transfers, fee, feeAssetId, timestamp, attachment, proofs, networkId).validatedEither
    } yield tx

  def parseTransfersList(transfers: List[Transfer]): Validation[List[ParsedTransfer]] =
    transfers.traverse { case Transfer(recipient, amount) =>
      for {
        address        <- Address.fromString(recipient)
        transferAmount <- TxNonNegativeAmount(amount)(NegativeAmount(amount, "asset"))
      } yield {
        ParsedTransfer(address, transferAmount)
      }
    }

}
