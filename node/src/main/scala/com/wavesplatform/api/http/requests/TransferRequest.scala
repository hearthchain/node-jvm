package com.wavesplatform.api.http.requests

import com.wavesplatform.account.{Address, PublicKey}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.lang.ValidationError
import com.wavesplatform.transaction.transfer.TransferTransaction
import com.wavesplatform.transaction.{Asset, Proofs}
import play.api.libs.json.*

case class TransferRequest(
    senderPublicKey: String,
    recipient: String,
    assetId: Option[Asset],
    amount: Long,
    feeAssetId: Option[Asset],
    fee: Long,
    attachment: Option[ByteStr] = None,
    timestamp: Option[Long] = None,
    signature: Option[ByteStr] = None,
    proofs: Option[Proofs] = None
) extends TxBroadcastRequest[TransferTransaction] {
  def toTx: Either[ValidationError, TransferTransaction] =
    for {
      validRecipient <- Address.fromString(recipient)
      validProofs    <- toProofs(signature, proofs)
      validSender    <- PublicKey.fromBase58String(senderPublicKey)
      tx <- TransferTransaction.create(
        validSender,
        validRecipient,
        assetId.getOrElse(Asset.Waves),
        amount,
        feeAssetId.getOrElse(Asset.Waves),
        fee,
        attachment.getOrElse(ByteStr.empty),
        timestamp.getOrElse(0L),
        validProofs
      )
    } yield tx
}

object TransferRequest {
  given Format[TransferRequest] = Json.format
}
