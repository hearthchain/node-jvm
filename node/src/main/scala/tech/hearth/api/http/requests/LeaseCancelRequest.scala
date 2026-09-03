package tech.hearth.api.http.requests

import tech.hearth.account.{NetworkId, PublicKey}
import tech.hearth.common.state.ByteStr
import tech.hearth.lang.ValidationError
import tech.hearth.transaction.Proofs
import tech.hearth.transaction.lease.LeaseCancelTransaction
import play.api.libs.functional.syntax.*
import play.api.libs.json.*

case class LeaseCancelRequest(
    senderPublicKey: String,
    leaseId: String,
    fee: Long,
    timestamp: Option[Long],
    signature: Option[ByteStr],
    proofs: Option[Proofs],
    networkId: NetworkId
) extends TxBroadcastRequest[LeaseCancelTransaction] {
  def toTx: Either[ValidationError, LeaseCancelTransaction] =
    for {
      validProofs  <- toProofs(signature, proofs)
      validLeaseId <- parseBase16(leaseId, "invalid.leaseTx", DigestStringLength)
      validSender  <- PublicKey.fromBase16String(senderPublicKey)
      tx <- LeaseCancelTransaction.create(
        validSender,
        validLeaseId,
        fee,
        timestamp.getOrElse(0L),
        validProofs
      )
    } yield tx
}

object LeaseCancelRequest {
  import tech.hearth.utils.byteStrFormat
  given Format[LeaseCancelRequest] = Format(
    ((JsPath \ "senderPublicKey").read[String] and
      (JsPath \ "leaseId").read[String].orElse((JsPath \ "txId").read[String]) and
      (JsPath \ "fee").read[Long] and
      (JsPath \ "timestamp").readNullable[Long] and
      (JsPath \ "signature").readNullable[ByteStr] and
      (JsPath \ "proofs").readNullable[Proofs] and
      (JsPath \ "networkId").readWithDefault(NetworkId.current))(LeaseCancelRequest.apply),
    Json.writes[LeaseCancelRequest]
  )
}
