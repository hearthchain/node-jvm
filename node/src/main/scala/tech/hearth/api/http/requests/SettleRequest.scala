package tech.hearth.api.http.requests

import tech.hearth.account.*
import tech.hearth.common.state.ByteStr
import tech.hearth.lang.ValidationError
import tech.hearth.state.diffs.FeeValidation.{FeeConstants, FeeUnit}
import tech.hearth.transaction.SettleTransaction.SettlementRequest
import tech.hearth.transaction.{Proofs, SettleTransaction, TransactionType}
import play.api.libs.json.*

object SettleRequest {
  given OFormat[SettleRequest] = Json.format
}

case class SettleRequest(
    senderPublicKey: String,
    enclavePublicKey: ByteStr,
    settlements: List[SettlementRequest],
    enclaveSignature: ByteStr,
    timestamp: Option[Long] = None,
    fee: Option[Long] = None,
    chainId: Byte = AddressScheme.current.chainId,
    proofs: Proofs = Proofs.empty
) extends TxBroadcastRequest[SettleTransaction] {
  def toTx: Either[ValidationError, SettleTransaction] =
    for {
      senderPk          <- PublicKey.fromBase16String(senderPublicKey)
      parsedSettlements <- SettleTransaction.parseSettlementsList(settlements)
      tx <- SettleTransaction.create(
        senderPk,
        enclavePublicKey,
        parsedSettlements,
        enclaveSignature,
        fee.getOrElse(FeeConstants(TransactionType.Settle) * FeeUnit),
        timestamp.getOrElse(defaultTimestamp),
        proofs,
        chainId
      )
    } yield tx
}
