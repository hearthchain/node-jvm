package tech.hearth.api.http.requests

import tech.hearth.account.*
import tech.hearth.common.state.ByteStr
import tech.hearth.lang.ValidationError
import tech.hearth.state.diffs.FeeValidation.{FeeConstants, FeeUnit}
import tech.hearth.transaction.{BindApiKeyTransaction, Proofs, TransactionType}
import play.api.libs.json.*

object BindApiKeyRequest {
  // encryptedApiKey is an HPKE-sealed envelope, larger than the package's default Format[ByteStr] (280-char decode
  // limit) can safely be assumed to stay under - shadow it before the macro below resolves it, same as
  // StartBoostRequest.tdxQuote/UpdateCollateralRequest's fields. See requests.largeByteStrFormat.
  private given Format[ByteStr]    = largeByteStrFormat
  given OFormat[BindApiKeyRequest] = Json.format
}

case class BindApiKeyRequest(
    senderPublicKey: String,
    enclavePublicKey: ByteStr,
    encryptedApiKey: ByteStr,
    timestamp: Option[Long] = None,
    fee: Option[Long] = None,
    chainId: Byte = AddressScheme.current.chainId,
    proofs: Proofs = Proofs.empty
) extends TxBroadcastRequest[BindApiKeyTransaction] {
  def toTx: Either[ValidationError, BindApiKeyTransaction] =
    for {
      senderPk <- PublicKey.fromBase16String(senderPublicKey)
      tx <- BindApiKeyTransaction.create(
        senderPk,
        enclavePublicKey,
        encryptedApiKey,
        fee.getOrElse(FeeConstants(TransactionType.BindApiKey) * FeeUnit),
        timestamp.getOrElse(defaultTimestamp),
        proofs,
        chainId
      )
    } yield tx
}
