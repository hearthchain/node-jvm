package tech.hearth.api.http.requests

import tech.hearth.account.*
import tech.hearth.common.state.ByteStr
import tech.hearth.crypto.bls.{BlsPublicKey, BlsSignature}
import tech.hearth.lang.ValidationError
import tech.hearth.state.Height
import tech.hearth.state.diffs.FeeValidation.{FeeConstants, FeeUnit}
import tech.hearth.transaction.{CommitToGenerationTransaction, Proofs, TransactionType}
import play.api.libs.json.*

object CommitToGenerationRequest {
  given OFormat[CommitToGenerationRequest] = Json.format
}

case class CommitToGenerationRequest(
    senderPublicKey: String,
    endorserPublicKey: ByteStr,
    vrfPublicKey: ByteStr,
    generationPeriodStart: Height,
    timestamp: Option[Long] = None,
    fee: Option[Long] = None,
    commitmentSignature: ByteStr,
    vrfCommitmentSignature: ByteStr,
    chainId: Byte = AddressScheme.current.chainId,
    proofs: Proofs = Proofs.empty
) extends TxBroadcastRequest[CommitToGenerationTransaction] {
  def toTx: Either[ValidationError, CommitToGenerationTransaction] = {
    for {
      blsSignature <- BlsSignature(commitmentSignature)
      blsPk        <- BlsPublicKey(endorserPublicKey)
      senderPk     <- PublicKey.fromBase58String(senderPublicKey)
      tx <- CommitToGenerationTransaction.create(
        senderPk,
        blsPk,
        vrfPublicKey,
        generationPeriodStart,
        timestamp.getOrElse(defaultTimestamp),
        fee.getOrElse(FeeConstants(TransactionType.CommitToGeneration) * FeeUnit),
        blsSignature,
        vrfCommitmentSignature,
        proofs,
        chainId
      )
    } yield tx
  }
}
