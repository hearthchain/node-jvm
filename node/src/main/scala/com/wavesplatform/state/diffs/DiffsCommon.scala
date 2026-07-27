package com.wavesplatform.state.diffs

import cats.syntax.either.*
import com.wavesplatform.account.{Address, PublicKey}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.lang.ValidationError
import com.wavesplatform.state.{Blockchain, Height, LeaseBalance, LeaseDetails, LeaseStaticInfo, Portfolio, StateSnapshot, TransactionId}
import com.wavesplatform.transaction.Asset.{IssuedAsset, Waves}
import com.wavesplatform.transaction.TxPositiveAmount
import com.wavesplatform.transaction.TxValidationError.GenericError

object DiffsCommon {
  def validateAsset(
      blockchain: Blockchain,
      asset: IssuedAsset,
      sender: Address,
      issuerOnly: Boolean
  ): Either[ValidationError, Unit] = {
    @inline
    def validIssuer(issuerOnly: Boolean, sender: Address, issuer: Address) =
      !issuerOnly || sender == issuer

    blockchain.assetDescription(asset) match {
      case Some(ad) if !validIssuer(issuerOnly, sender, ad.issuer.toAddress) =>
        Left(GenericError("Asset was issued by other address"))
      case None =>
        Left(GenericError("Referenced assetId not found"))
      case Some(_) =>
        Right({})
    }
  }

  def processLease(
      blockchain: Blockchain,
      amount: TxPositiveAmount,
      sender: PublicKey,
      recipientAddress: Address,
      fee: Long,
      leaseId: ByteStr,
      txId: TransactionId
  ): Either[ValidationError, StateSnapshot] = {
    val senderAddress = sender.toAddress
    for {
      _ <- Either.cond(
        recipientAddress != senderAddress,
        (),
        GenericError("Cannot lease to self")
      )
      _ <- Either.cond(
        blockchain.leaseDetails(leaseId).isEmpty,
        (),
        GenericError(s"Lease with id=$leaseId is already in the state")
      )
      leaseBalance    = blockchain.leaseBalance(senderAddress)
      senderBalance   = blockchain.balance(senderAddress, Waves)
      requiredBalance = amount.value + fee
      _ <- Either.cond(
        senderBalance - leaseBalance.out >= requiredBalance,
        (),
        GenericError(s"Cannot lease more than own: Balance: $senderBalance, already leased: ${leaseBalance.out}")
      )
      portfolioDiff = Map(
        senderAddress    -> Portfolio(-fee, LeaseBalance(0, amount.value)),
        recipientAddress -> Portfolio(0, LeaseBalance(amount.value, 0))
      )
      snapshot <- StateSnapshot.build(
        blockchain,
        portfolios = portfolioDiff,
        newLeases = Map(leaseId -> LeaseStaticInfo(sender, recipientAddress, amount, txId, Height(blockchain.height)))
      )
    } yield snapshot
  }

  def processLeaseCancel(
      blockchain: Blockchain,
      sender: PublicKey,
      fee: Long,
      leaseId: ByteStr,
      cancelTxId: ByteStr
  ): Either[ValidationError, StateSnapshot] = {
    for {
      lease <- blockchain.leaseDetails(leaseId).toRight(GenericError(s"Lease with id=$leaseId not found"))
      _     <- Either.raiseUnless(lease.isActive)(GenericError(s"Cannot cancel already cancelled lease"))
      _     <- Either.raiseUnless(sender == lease.sender)(GenericError("LeaseTransaction was leased by other sender"))
      senderPortfolio    = Map[Address, Portfolio](sender.toAddress -> Portfolio(-fee, LeaseBalance(0, -lease.amount.value)))
      recipientPortfolio = Map(lease.recipientAddress -> Portfolio(0, LeaseBalance(-lease.amount.value, 0)))
      portfolios <- Portfolio.combine(senderPortfolio, recipientPortfolio).leftMap(GenericError(_))
      snapshot <- StateSnapshot.build(
        blockchain,
        portfolios = portfolios,
        cancelledLeases = Map(leaseId -> LeaseDetails.Status.Cancelled(Height(blockchain.height), Some(TransactionId(cancelTxId))))
      )
    } yield snapshot
  }
}
