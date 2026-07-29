package com.wavesplatform.transaction.transfer

import cats.instances.list.*
import cats.syntax.traverse.*
import com.wavesplatform.account.*
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.lang.ValidationError
import com.wavesplatform.transaction.*
import com.wavesplatform.transaction.TxValidationError.*
import com.wavesplatform.transaction.serialization.impl.MassTransferTxSerializer
import com.wavesplatform.transaction.transfer.MassTransferTransaction.ParsedTransfer
import com.wavesplatform.transaction.validation.TxValidator
import com.wavesplatform.transaction.validation.impl.MassTransferTxValidator
import monix.eval.Coeval
import play.api.libs.json.{JsObject, Json, OFormat}

import scala.util.Either

case class MassTransferTransaction(
    sender: PublicKey,
    assetId: Asset,
    transfers: Seq[ParsedTransfer],
    fee: TxPositiveAmount,
    timestamp: TxTimestamp,
    attachment: ByteStr,
    proofs: Proofs,
    chainId: Byte
) extends Transaction(TransactionType.MassTransfer),
      ProvenTransaction,
      TxWithFee.InWaves,
      FastHashId {
  override type T = MassTransferTransaction

  override val json: Coeval[JsObject] = Coeval.evalOnce(MassTransferTxSerializer.toJson(this))

  override def addProof(proof: ByteStr): MassTransferTransaction = copy(proofs = this.proofs.add(proof))

  def compactJson: JsObject =
    json() ++ Json.obj(
      "transfers" -> MassTransferTxSerializer.transfersJson(transfers)
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
      chainId: Byte = AddressScheme.current.chainId
  ): Either[ValidationError, MassTransferTransaction] =
    for {
      fee <- TxPositiveAmount(fee)(TxValidationError.InsufficientFee)
      tx  <- MassTransferTransaction(sender, assetId, transfers, fee, timestamp, attachment, proofs, chainId).validatedEither
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
