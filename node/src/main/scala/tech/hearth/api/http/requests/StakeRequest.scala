package tech.hearth.api.http.requests

import tech.hearth.account.*
import tech.hearth.lang.ValidationError
import tech.hearth.state.Height
import tech.hearth.state.diffs.FeeValidation.{FeeConstants, FeeUnit}
import tech.hearth.transaction.{Proofs, StakeTransaction, TransactionType}
import play.api.libs.json.*

object StakeRequest {
  given OFormat[StakeRequest] = Json.format
}

case class StakeRequest(
    senderPublicKey: String,
    periodStart: Height,
    // Long rather than TxNonNegativeAmount: 0 is a valid request (it releases the whole stake), and
    // StakeTransaction.create is what rejects a negative one.
    amount: Long,
    timestamp: Option[Long] = None,
    fee: Option[Long] = None,
    networkId: NetworkId = NetworkId.current,
    proofs: Proofs = Proofs.empty
) extends TxBroadcastRequest[StakeTransaction] {
  def toTx: Either[ValidationError, StakeTransaction] =
    for {
      senderPk <- PublicKey.fromBase16String(senderPublicKey)
      tx <- StakeTransaction.create(
        senderPk,
        periodStart,
        amount,
        fee.getOrElse(FeeConstants(TransactionType.Stake) * FeeUnit),
        timestamp.getOrElse(defaultTimestamp),
        proofs,
        networkId
      )
    } yield tx
}
