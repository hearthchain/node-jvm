package tech.hearth.transaction.lease

import tech.hearth.account.{Address, NetworkId, PublicKey}
import tech.hearth.common.state.ByteStr
import tech.hearth.lang.ValidationError
import tech.hearth.transaction.*
import tech.hearth.transaction.serialization.impl.LeaseTxSerializer
import tech.hearth.transaction.validation.TxValidator
import tech.hearth.transaction.validation.impl.LeaseTxValidator
import monix.eval.Coeval
import play.api.libs.json.JsObject

final case class LeaseTransaction(
    sender: PublicKey,
    recipient: Address,
    amount: TxPositiveAmount,
    fee: TxPositiveAmount,
    timestamp: TxTimestamp,
    proofs: Proofs,
    networkId: NetworkId
) extends Transaction(TransactionType.Lease),
      ProvenTransaction,
      TxWithFee.InHearth,
      FastHashId {
  type T = LeaseTransaction

  override val json: Coeval[JsObject] = Coeval.evalOnce(LeaseTxSerializer.toJson(this))

  override def addProof(proof: ByteStr): LeaseTransaction = copy(proofs = this.proofs.add(proof))
}

object LeaseTransaction {
  implicit val validator: TxValidator[LeaseTransaction] = LeaseTxValidator

  def create(
      networkId: NetworkId,
      sender: PublicKey,
      recipient: Address,
      amount: Long,
      fee: Long,
      timestamp: TxTimestamp,
      proofs: Proofs
  ): Either[ValidationError, LeaseTransaction] = {
    for {
      fee    <- TxPositiveAmount(fee)(TxValidationError.InsufficientFee)
      amount <- TxPositiveAmount(amount)(TxValidationError.NonPositiveAmount(amount, "hearth"))
      tx     <- LeaseTransaction(sender, recipient, amount, fee, timestamp, proofs, networkId).validatedEither
    } yield tx

  }
}
