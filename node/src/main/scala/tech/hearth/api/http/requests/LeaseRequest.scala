package tech.hearth.api.http.requests

import tech.hearth.account.{Address, NetworkId, PublicKey}
import tech.hearth.common.state.ByteStr
import tech.hearth.lang.ValidationError
import tech.hearth.transaction.Proofs
import tech.hearth.transaction.lease.LeaseTransaction
import play.api.libs.json.{Format, Json}

case class LeaseRequest(
    networkId: Option[NetworkId],
    senderPublicKey: String,
    recipient: String,
    amount: Long,
    fee: Long,
    timestamp: Option[Long],
    signature: Option[ByteStr],
    proofs: Option[Proofs]
) extends TxBroadcastRequest[LeaseTransaction] {
  def toTx: Either[ValidationError, LeaseTransaction] =
    for {
      validRecipient <- Address.fromString(recipient)
      validProofs    <- toProofs(signature, proofs)
      validSender    <- PublicKey.fromBase16String(senderPublicKey)
      tx <- LeaseTransaction.create(
        networkId.getOrElse(NetworkId.current),
        validSender,
        validRecipient,
        amount,
        fee,
        timestamp.getOrElse(0L),
        validProofs
      )
    } yield tx
}

object LeaseRequest {
  given Format[LeaseRequest] = Json.format
}
