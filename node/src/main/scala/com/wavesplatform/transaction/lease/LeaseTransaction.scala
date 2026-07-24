package com.wavesplatform.transaction.lease

import com.wavesplatform.account.{Address, PublicKey}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.lang.ValidationError
import com.wavesplatform.transaction.*
import com.wavesplatform.transaction.serialization.impl.LeaseTxSerializer
import com.wavesplatform.transaction.validation.TxValidator
import com.wavesplatform.transaction.validation.impl.LeaseTxValidator
import eu.timepit.refined.*
import eu.timepit.refined.api.{RefType, Refined}
import eu.timepit.refined.generic.Equal
import monix.eval.Coeval
import play.api.libs.json.JsObject

final case class LeaseTransaction(
    sender: PublicKey,
    recipient: Address,
    amount: TxPositiveAmount,
    fee: TxPositiveAmount,
    timestamp: TxTimestamp,
    proofs: Proofs,
    chainId: Byte
) extends Transaction(TransactionType.Lease),
      ProvenTransaction,
      TxWithFee.InWaves,
      FastHashId {
  type T = LeaseTransaction

  override val json: Coeval[JsObject] = Coeval.evalOnce(LeaseTxSerializer.toJson(this))

  override def addProof(proof: ByteStr): LeaseTransaction = copy(proofs = this.proofs.add(proof))
}

object LeaseTransaction {
  implicit val validator: TxValidator[LeaseTransaction] = LeaseTxValidator

  def create(
      chainId: Byte,
      sender: PublicKey,
      recipient: Address,
      amount: Long,
      fee: Long,
      timestamp: TxTimestamp,
      proofs: Proofs
  ): Either[ValidationError, LeaseTransaction] = {
    for {
      fee    <- TxPositiveAmount(fee)(TxValidationError.InsufficientFee)
      amount <- TxPositiveAmount(amount)(TxValidationError.NonPositiveAmount(amount, "waves"))
      tx     <- LeaseTransaction(sender, recipient, amount, fee, timestamp, proofs, chainId).validatedEither
    } yield tx

  }
}
