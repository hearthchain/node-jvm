package com.wavesplatform.transaction.lease

import com.wavesplatform.account.{AddressScheme, PublicKey}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.lang.ValidationError
import com.wavesplatform.transaction.*
import com.wavesplatform.transaction.serialization.impl.LeaseCancelTxSerializer
import com.wavesplatform.transaction.validation.TxValidator
import com.wavesplatform.transaction.validation.impl.LeaseCancelTxValidator
import monix.eval.Coeval
import play.api.libs.json.JsObject

final case class LeaseCancelTransaction(
    version: TxVersion,
    sender: PublicKey,
    leaseId: ByteStr,
    fee: TxPositiveAmount,
    timestamp: TxTimestamp,
    proofs: Proofs,
    chainId: Byte
) extends Transaction(TransactionType.LeaseCancel),
      ProvenTransaction,
      TxWithFee.InWaves,
      FastHashId {
  override type T = LeaseCancelTransaction

  override val json: Coeval[JsObject] = Coeval.evalOnce(LeaseCancelTxSerializer.toJson(this))

  override def addProof(proof: ByteStr): LeaseCancelTransaction = copy(proofs = this.proofs.add(proof))
}

object LeaseCancelTransaction {
  type TransactionT = LeaseCancelTransaction

  val typeId: TxType = 9: Byte

  implicit val validator: TxValidator[LeaseCancelTransaction] = LeaseCancelTxValidator

  def create(
      version: TxVersion,
      sender: PublicKey,
      leaseId: ByteStr,
      fee: Long,
      timestamp: TxTimestamp,
      proofs: Proofs,
      chainId: Byte = AddressScheme.current.chainId
  ): Either[ValidationError, TransactionT] =
    for {
      fee <- TxPositiveAmount(fee)(TxValidationError.InsufficientFee)
      tx  <- LeaseCancelTransaction(version, sender, leaseId, fee, timestamp, proofs, chainId).validatedEither
    } yield tx
}
