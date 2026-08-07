package tech.hearth.transaction.transfer

import cats.instances.list.*
import cats.syntax.traverse.*
import tech.hearth.account.*
import tech.hearth.common.state.ByteStr
import tech.hearth.lang.ValidationError
import tech.hearth.transaction.*
import tech.hearth.transaction.Asset.Waves
import tech.hearth.transaction.TxValidationError.*
import tech.hearth.transaction.serialization.impl.MassTransferTxSerializer
import tech.hearth.transaction.transfer.MassTransferTransaction.ParsedTransfer
import tech.hearth.transaction.validation.TxValidator
import tech.hearth.transaction.validation.impl.MassTransferTxValidator
import monix.eval.Coeval
import play.api.libs.json.{JsObject, Json, OFormat}

import scala.util.Either

case class MassTransferTransaction(
    sender: PublicKey,
    assetId: Asset,
    transfers: Seq[ParsedTransfer],
    fee: TxPositiveAmount,
    feeAssetId: Asset,
    timestamp: TxTimestamp,
    attachment: ByteStr,
    proofs: Proofs,
    chainId: Byte
) extends Transaction(TransactionType.MassTransfer),
      ProvenTransaction,
      TxWithFee.InCustomAsset,
      FastHashId {
  override type T = MassTransferTransaction

  override val json: Coeval[JsObject] = Coeval.evalOnce(MassTransferTxSerializer.toJson(this))

  override def addProof(proof: ByteStr): MassTransferTransaction = copy(proofs = this.proofs.add(proof))

  def compactJson(recipient: Address): JsObject =
    json() ++ Json.obj(
      "transfers" -> MassTransferTxSerializer.transfersJson(transfers.filter(_.address == recipient))
    )
}

object MassTransferTransaction {
  type TransactionT = MassTransferTransaction

  val MaxTransferCount = 100

  val typeId: TxType = TransactionType.MassTransfer.id.toByte

  implicit val validator: TxValidator[MassTransferTransaction] = MassTransferTxValidator

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
      chainId: Byte = AddressScheme.current.chainId,
      feeAssetId: Asset = Waves
  ): Either[ValidationError, MassTransferTransaction] =
    for {
      fee <- TxPositiveAmount(fee)(TxValidationError.InsufficientFee)
      tx  <- MassTransferTransaction(sender, assetId, transfers, fee, feeAssetId, timestamp, attachment, proofs, chainId).validatedEither
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
