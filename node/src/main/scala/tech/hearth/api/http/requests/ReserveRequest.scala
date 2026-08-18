package tech.hearth.api.http.requests

import tech.hearth.account.*
import tech.hearth.lang.ValidationError
import tech.hearth.state.diffs.FeeValidation.{FeeConstants, FeeUnit}
import tech.hearth.transaction.{Asset, Proofs, ReserveTransaction, TransactionType}
import play.api.libs.json.*

object ReserveRequest {
  given OFormat[ReserveRequest] = Json.format
}

case class ReserveRequest(
    senderPublicKey: String,
    assetId: Option[Asset] = None,
    amount: Long,
    miner: String,
    feeAssetId: Option[Asset] = None,
    timestamp: Option[Long] = None,
    fee: Option[Long] = None,
    chainId: Byte = AddressScheme.current.chainId,
    proofs: Proofs = Proofs.empty
) extends TxBroadcastRequest[ReserveTransaction] {
  def toTx: Either[ValidationError, ReserveTransaction] =
    for {
      senderPk  <- PublicKey.fromBase16String(senderPublicKey)
      minerAddr <- Address.fromString(miner)
      tx <- ReserveTransaction.create(
        senderPk,
        assetId.getOrElse(Asset.Hearth),
        amount,
        minerAddr,
        feeAssetId.getOrElse(Asset.Hearth),
        fee.getOrElse(FeeConstants(TransactionType.Reserve) * FeeUnit),
        timestamp.getOrElse(defaultTimestamp),
        proofs,
        chainId
      )
    } yield tx
}
