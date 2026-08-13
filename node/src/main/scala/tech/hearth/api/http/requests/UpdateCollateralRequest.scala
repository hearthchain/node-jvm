package tech.hearth.api.http.requests

import tech.hearth.account.*
import tech.hearth.common.state.ByteStr
import tech.hearth.lang.ValidationError
import tech.hearth.state.diffs.FeeValidation.{FeeConstants, FeeUnit}
import tech.hearth.transaction.{Proofs, TransactionType, UpdateCollateralTransaction}
import play.api.libs.json.*

object UpdateCollateralRequest {
  // Shadows the package's default Format[ByteStr] (280-char decode limit) with one sized for collateral blobs -
  // every ByteStr field below can be several KB - before the macro below resolves it. See requests.largeByteStrFormat.
  private given Format[ByteStr]          = largeByteStrFormat
  given OFormat[UpdateCollateralRequest] = Json.format
}

case class UpdateCollateralRequest(
    senderPublicKey: String,
    rootCaCrl: Option[ByteStr] = None,
    pckCrl: Option[ByteStr] = None,
    tcbInfo: Option[ByteStr] = None,
    qeIdentity: Option[ByteStr] = None,
    tcbSigningIssuerChain: Option[ByteStr] = None,
    pckCaIssuerChain: Option[ByteStr] = None,
    timestamp: Option[Long] = None,
    fee: Option[Long] = None,
    chainId: Byte = AddressScheme.current.chainId,
    proofs: Proofs = Proofs.empty
) extends TxBroadcastRequest[UpdateCollateralTransaction] {
  def toTx: Either[ValidationError, UpdateCollateralTransaction] =
    for {
      senderPk <- PublicKey.fromBase16String(senderPublicKey)
      tx <- UpdateCollateralTransaction.create(
        senderPk,
        rootCaCrl,
        pckCrl,
        tcbInfo,
        qeIdentity,
        tcbSigningIssuerChain,
        pckCaIssuerChain,
        fee.getOrElse(FeeConstants(TransactionType.UpdateCollateral) * FeeUnit),
        timestamp.getOrElse(defaultTimestamp),
        proofs,
        chainId
      )
    } yield tx
}
