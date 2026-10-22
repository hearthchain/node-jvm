package tech.hearth.api.http.requests

import tech.hearth.account.*
import tech.hearth.lang.ValidationError
import tech.hearth.state.diffs.FeeValidation.{FeeConstants, FeeUnit}
import tech.hearth.transaction.Asset.IssuedAsset
import tech.hearth.transaction.{Asset, Proofs, TransactionType, WithdrawTransaction}
import play.api.libs.json.*

object WithdrawRequest {
  given OFormat[WithdrawRequest] = Json.format
}

case class WithdrawRequest(
    senderPublicKey: String,
    fromMiner: String,
    assetId: IssuedAsset,
    amount: Long,
    feeAssetId: Option[Asset] = None,
    timestamp: Option[Long] = None,
    fee: Option[Long] = None,
    networkId: NetworkId = NetworkId.current,
    proofs: Proofs = Proofs.empty
) extends TxBroadcastRequest[WithdrawTransaction] {
  def toTx: Either[ValidationError, WithdrawTransaction] =
    for {
      senderPk  <- PublicKey.fromBase16String(senderPublicKey)
      minerAddr <- Address.fromString(fromMiner)
      tx <- WithdrawTransaction.create(
        senderPk,
        minerAddr,
        assetId,
        amount,
        feeAssetId.getOrElse(Asset.Hearth),
        fee.getOrElse(FeeConstants(TransactionType.Withdraw) * FeeUnit),
        timestamp.getOrElse(defaultTimestamp),
        proofs,
        networkId
      )
    } yield tx
}
