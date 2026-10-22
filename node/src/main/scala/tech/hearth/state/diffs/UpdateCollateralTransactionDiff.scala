package tech.hearth.state.diffs

import cats.syntax.either.*
import cats.syntax.traverse.*
import tech.hearth.lang.ValidationError
import tech.hearth.state.*
import tech.hearth.transaction.TxValidationError.GenericError
import tech.hearth.transaction.UpdateCollateralTransaction

/** UpdateCollateralTransaction semantics: verify whichever fields are set (see DcapCollateral) and merge the
  * result into the snapshot.
  *
  * atTime for every expiry/freshness check is the block's timestamp (DcapCollateral.validityTime): the transaction's
  * is equally deterministic, but its sender picks it and may backdate it by up to max-transaction-time-back-offset,
  * which would let collateral that just expired through.
  */
object UpdateCollateralTransactionDiff {
  def apply(blockchain: Blockchain)(tx: UpdateCollateralTransaction): Either[ValidationError, StateSnapshot] = {
    val sender = tx.sender.toAddress

    for {
      blockTimestamp <- DcapCollateral.validityTime(blockchain).leftMap(GenericError(_))
      rootCaCrl      <- tx.rootCaCrl.traverse(p => DcapCollateral.verifyRootCaCrl(p, blockchain, blockTimestamp).leftMap(GenericError(_)))
      pckCaIssuerChain <- tx.pckCaIssuerChain.traverse(p =>
        DcapCollateral.verifyPckCaIssuerChain(p, blockchain, blockTimestamp).leftMap(GenericError(_))
      )
      pckCrl <- tx.pckCrl.traverse(p => DcapCollateral.verifyPckCrl(p, tx.pckCaIssuerChain, blockchain, blockTimestamp).leftMap(GenericError(_)))
      tcbSigningIssuerChain <- tx.tcbSigningIssuerChain.traverse(p =>
        DcapCollateral.verifyTcbSigningIssuerChain(p, blockchain, blockTimestamp).leftMap(GenericError(_))
      )
      tcbInfo <- tx.tcbInfo.traverse(p =>
        DcapCollateral.verifyTcbInfo(p, tx.tcbSigningIssuerChain, blockchain, blockTimestamp).leftMap(GenericError(_))
      )
      qeIdentity <- tx.qeIdentity.traverse(p =>
        DcapCollateral.verifyQeIdentity(p, tx.tcbSigningIssuerChain, blockchain, blockTimestamp).leftMap(GenericError(_))
      )
      snapshot <- StateSnapshot.build(
        blockchain,
        portfolios = Map(sender -> Portfolio(balance = -tx.fee.value)),
        dcapRootCaCrl = rootCaCrl,
        dcapPckCrl = pckCrl,
        dcapTcbInfo = tcbInfo.toMap,
        dcapQeIdentity = qeIdentity,
        dcapTcbSigningIssuerChain = tcbSigningIssuerChain,
        dcapPckCaIssuerChain = pckCaIssuerChain
      )
    } yield snapshot
  }
}
