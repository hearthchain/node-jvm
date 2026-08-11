package tech.hearth.transaction.lease

import tech.hearth.account.{AddressScheme, PublicKey}
import tech.hearth.common.state.ByteStr
import tech.hearth.lang.ValidationError
import tech.hearth.transaction.*
import tech.hearth.transaction.serialization.impl.LeaseCancelTxSerializer
import tech.hearth.transaction.validation.TxValidator
import tech.hearth.transaction.validation.impl.LeaseCancelTxValidator
import monix.eval.Coeval
import play.api.libs.json.JsObject

final case class LeaseCancelTransaction(
    sender: PublicKey,
    leaseId: ByteStr,
    fee: TxPositiveAmount,
    timestamp: TxTimestamp,
    proofs: Proofs,
    chainId: Byte
) extends Transaction(TransactionType.LeaseCancel),
      ProvenTransaction,
      TxWithFee.InHearth,
      FastHashId {
  override type T = LeaseCancelTransaction

  override val json: Coeval[JsObject] = Coeval.evalOnce(LeaseCancelTxSerializer.toJson(this))

  override def addProof(proof: ByteStr): LeaseCancelTransaction = copy(proofs = this.proofs.add(proof))
}

object LeaseCancelTransaction {
  type TransactionT = LeaseCancelTransaction

  implicit val validator: TxValidator[LeaseCancelTransaction] = LeaseCancelTxValidator

  def create(
      sender: PublicKey,
      leaseId: ByteStr,
      fee: Long,
      timestamp: TxTimestamp,
      proofs: Proofs,
      chainId: Byte = AddressScheme.current.chainId
  ): Either[ValidationError, TransactionT] =
    for {
      fee <- TxPositiveAmount(fee)(TxValidationError.InsufficientFee)
      tx  <- LeaseCancelTransaction(sender, leaseId, fee, timestamp, proofs, chainId).validatedEither
    } yield tx
}
