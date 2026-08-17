package tech.hearth.api.http.requests

import tech.hearth.account.*
import tech.hearth.common.state.ByteStr
import tech.hearth.lang.ValidationError
import tech.hearth.state.Height
import tech.hearth.state.diffs.FeeValidation.{FeeConstants, FeeUnit}
import tech.hearth.transaction.{Proofs, StartBoostTransaction, TransactionType}
import play.api.libs.json.*

object StartBoostRequest {
  // tdxQuote is ~4935 bytes raw (~9870 hex chars) - shadow the package's default Format[ByteStr] (280-char decode
  // limit) with one sized for large blobs before the macro below resolves it. See requests.largeByteStrFormat.
  private given Format[ByteStr]    = largeByteStrFormat
  given OFormat[StartBoostRequest] = Json.format
}

case class StartBoostRequest(
    senderPublicKey: String,
    validator: String,
    tdxQuote: ByteStr,
    generationPeriodStart: Height,
    timestamp: Option[Long] = None,
    fee: Option[Long] = None,
    chainId: Byte = AddressScheme.current.chainId,
    proofs: Proofs = Proofs.empty
) extends TxBroadcastRequest[StartBoostTransaction] {
  def toTx: Either[ValidationError, StartBoostTransaction] =
    for {
      senderPk      <- PublicKey.fromBase16String(senderPublicKey)
      validatorAddr <- Address.fromString(validator)
      tx <- StartBoostTransaction.create(
        senderPk,
        validatorAddr,
        tdxQuote,
        generationPeriodStart,
        fee.getOrElse(FeeConstants(TransactionType.StartBoost) * FeeUnit),
        timestamp.getOrElse(defaultTimestamp),
        proofs,
        chainId
      )
    } yield tx
}
