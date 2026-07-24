package com.wavesplatform.state.diffs

import cats.implicits.toBifunctorOps
import cats.syntax.either.*
import com.wavesplatform.lang.ValidationError
import com.wavesplatform.state.*
import com.wavesplatform.transaction.Asset.{IssuedAsset, Waves}
import com.wavesplatform.transaction.TxValidationError.GenericError
import com.wavesplatform.transaction.transfer.TransferTransaction
import com.wavesplatform.transaction.Asset
import tech.hearth.crypto.Address

object TransferTransactionDiff {
  def apply(blockchain: Blockchain)(tx: TransferTransaction): Either[ValidationError, StateSnapshot] =
    TransferDiff(blockchain)(tx.sender.toAddress, tx.recipient, tx.amount.value, tx.assetId, tx.fee.value, tx.feeAssetId)
}

object TransferDiff {
  def apply(
      blockchain: Blockchain
  )(
      senderAddress: Address,
      recipient: Address,
      amount: Long,
      assetId: Asset,
      fee: Long,
      feeAssetId: Asset
  ): Either[ValidationError, StateSnapshot] = {

    val isSmartAsset = false

    for {
      _ <- Either.cond(!isSmartAsset, (), GenericError("Smart assets can't participate in TransferTransactions as a fee"))

      // Ride4DApps is active: overflow no longer needs an explicit check, the transaction validates itself
      transferPf <- assetId match {
        case Waves =>
          Portfolio
            .combine(
              Map(senderAddress -> Portfolio(-amount)),
              Map(recipient     -> Portfolio(amount))
            )
            .leftMap(GenericError(_))
        case asset @ IssuedAsset(_) =>
          Portfolio
            .combine(
              Map(senderAddress -> Portfolio.build(asset -> -amount)),
              Map(recipient     -> Portfolio.build(asset -> amount))
            )
            .leftMap(GenericError(_))
      }
      feePf <- feeAssetId match {
        case Waves => Right(Map(senderAddress -> Portfolio(-fee)))
        case asset @ IssuedAsset(_) =>
          val senderPf = Map(senderAddress -> Portfolio.build(asset -> -fee))
          Right(senderPf)
      }
      portfolios <- Portfolio.combine(transferPf, feePf).leftMap(GenericError(_))
      assetIssued    = assetId.fold(true)(blockchain.assetDescription(_).isDefined)
      feeAssetIssued = feeAssetId.fold(true)(blockchain.assetDescription(_).isDefined)
      _        <- Either.raiseUnless(assetIssued && feeAssetIssued)(GenericError(s"Unissued assets are not allowed"))
      snapshot <- StateSnapshot.build(blockchain, portfolios = portfolios)
    } yield snapshot
  }
}
