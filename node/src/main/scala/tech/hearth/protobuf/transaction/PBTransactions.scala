package tech.hearth.protobuf.transaction

import cats.syntax.traverse.*
import com.google.protobuf.ByteString
import tech.hearth.crypto.bls.{BlsPublicKey, BlsSignature}
import tech.hearth.lang.ValidationError
import tech.hearth.protobuf.*
import tech.hearth.protobuf.transaction.Transaction.Data
import tech.hearth.protobuf.utils.PBImplicitConversions.*
import tech.hearth.state.Height
import tech.hearth.transaction as vt
import tech.hearth.transaction.TxValidationError.{GenericError, NegativeAmount}
import tech.hearth.transaction.serialization.impl.PBTransactionSerializer
import tech.hearth.transaction.transfer.TransferTransaction
import tech.hearth.transaction.transfer.TransferTransaction.ParsedTransfer
import tech.hearth.transaction.{CommitToGenerationTransaction, Proofs, TxNonNegativeAmount, TxValidationError}
import scalapb.UnknownFieldSet.empty

object PBTransactions {

  /** Reserve and Settle only ever name an issued asset (Hearth is neither reservable nor settleable), while the
    * wire reads an empty asset id as Hearth - so that case is rejected here, before the transaction exists as a
    * domain value.
    */
  private def issuedAsset(asset: vt.Asset): Either[ValidationError, vt.Asset.IssuedAsset] = asset match {
    case issued: vt.Asset.IssuedAsset => Right(issued)
    case vt.Asset.Hearth              => Left(GenericError("Hearth is not a valid asset here, an issued asset is required"))
  }

  def create(
      sender: tech.hearth.account.PublicKey,
      chainId: Byte = 0,
      fee: Long = 0L,
      timestamp: Long = 0L,
      proofsArray: Seq[tech.hearth.common.state.ByteStr] = Nil,
      data: tech.hearth.protobuf.transaction.Transaction.Data = tech.hearth.protobuf.transaction.Transaction.Data.Empty
  ): SignedTransaction =
    new SignedTransaction(
      Some(Transaction(chainId, sender.toByteString, fee, timestamp, data)),
      proofsArray.map(bs => ByteString.copyFrom(bs.arr))
    )

  def vanilla(signedTx: PBSignedTransaction): Either[ValidationError, VanillaTransaction] =
    signedTx.transaction match {
      case Some(parsedTx) =>
        for {
          proofs <- Proofs.create(signedTx.proofs.map(_.toByteStr))
          tx <- createVanilla(
            parsedTx.chainId.toByte,
            parsedTx.senderPublicKey,
            parsedTx.fee,
            parsedTx.timestamp,
            proofs,
            parsedTx.data
          )
        } yield tx
      case _ => Left(GenericError("Transaction must be specified"))
    }

  private def createVanilla(
      chainId: Byte,
      sender: ByteString,
      feeAmount: Long,
      timestamp: Long,
      proofs: Proofs,
      data: PBTransaction.Data
  ): Either[ValidationError, VanillaTransaction] = {

    val result: Either[ValidationError, VanillaTransaction] = data match {
      case Data.Transfer(TransferTransactionData(assetId, transfers, attachment, feeAssetId, `empty`)) =>
        for {
          parsedTransfers <- transfers.toList.traverse { t =>
            t.getRecipient.toAddress.flatMap { address =>
              TxNonNegativeAmount(t.amount)(NegativeAmount(t.amount, "asset"))
                .map(ParsedTransfer(address, _))
            }
          }
          tx <- vt.transfer.TransferTransaction.create(
            sender.toPublicKey,
            PBAmounts.toVanillaAssetId(assetId),
            parsedTransfers,
            feeAmount,
            timestamp,
            attachment.toByteStr,
            proofs,
            chainId,
            PBAmounts.toVanillaAssetId(feeAssetId)
          )
        } yield tx

      case Data.Lease(LeaseTransactionData(Some(recipient), amount, `empty`)) =>
        for {
          address <- recipient.toAddress
          tx      <- vt.lease.LeaseTransaction.create(chainId, sender.toPublicKey, address, amount, feeAmount, timestamp, proofs)
        } yield tx

      case Data.LeaseCancel(LeaseCancelTransactionData(leaseId, `empty`)) =>
        vt.lease.LeaseCancelTransaction.create(sender.toPublicKey, leaseId.toByteStr, feeAmount, timestamp, proofs, chainId)

      case Data.Exchange(ExchangeTransactionData(amount, price, buyMatcherFee, sellMatcherFee, Seq(order1, order2), `empty`)) =>
        for {
          order1 <- PBOrders.vanilla(order1)
          order2 <- PBOrders.vanilla(order2)
          tx <- vt.assets.exchange.ExchangeTransaction.create(
            order1,
            order2,
            amount,
            price,
            buyMatcherFee,
            sellMatcherFee,
            feeAmount,
            timestamp,
            proofs,
            chainId
          )
        } yield tx

      case Data.CommitToGeneration(
            CommitToGenerationTransactionData(
              generationPeriodStart,
              endorserPublicKey,
              commitmentSignature,
              vrfPublicKey,
              vrfCommitmentSignature,
              `empty`
            )
          ) =>
        for {
          sig   <- BlsSignature(commitmentSignature.toByteArray)
          blsPk <- BlsPublicKey(endorserPublicKey.toByteStr)
          tx <- CommitToGenerationTransaction.create(
            sender.toPublicKey,
            blsPk,
            vrfPublicKey.toByteStr,
            Height(generationPeriodStart),
            timestamp,
            feeAmount,
            sig,
            vrfCommitmentSignature.toByteStr,
            proofs,
            chainId
          )
        } yield tx

      case Data.StartBoost(StartBoostTransactionData(Some(validator), tdxQuote, generationPeriodStart, `empty`)) =>
        for {
          validatorAddress <- validator.toAddress
          tx <- vt.StartBoostTransaction.create(
            sender.toPublicKey,
            validatorAddress,
            tdxQuote.toByteStr,
            Height(generationPeriodStart),
            feeAmount,
            timestamp,
            proofs,
            chainId
          )
        } yield tx

      case Data.BindApiKey(BindApiKeyTransactionData(enclavePublicKey, encryptedApiKey, `empty`)) =>
        vt.BindApiKeyTransaction.create(
          sender.toPublicKey,
          enclavePublicKey.toByteStr,
          encryptedApiKey.toByteStr,
          feeAmount,
          timestamp,
          proofs,
          chainId
        )

      case Data.Reserve(ReserveTransactionData(Some(amount), Some(miner), feeAssetId, `empty`)) =>
        for {
          minerAddress <- miner.toAddress
          assetId      <- issuedAsset(amount.vanillaAssetId)
          tx <- vt.ReserveTransaction.create(
            sender.toPublicKey,
            assetId,
            amount.longAmount,
            minerAddress,
            PBAmounts.toVanillaAssetId(feeAssetId),
            feeAmount,
            timestamp,
            proofs,
            chainId
          )
        } yield tx

      case Data.Settle(SettleTransactionData(enclavePublicKey, settlements, enclaveSignature, `empty`)) =>
        for {
          parsedSettlements <- settlements.toList.traverse { s =>
            for {
              client  <- s.getClient.toAddress
              assetId <- issuedAsset(s.getCumulativeSpent.vanillaAssetId)
              amount  <- TxNonNegativeAmount(s.getCumulativeSpent.longAmount)(NegativeAmount(s.getCumulativeSpent.longAmount, "asset"))
            } yield vt.SettleTransaction.Settlement(client, assetId, amount)
          }
          tx <- vt.SettleTransaction.create(
            sender.toPublicKey,
            enclavePublicKey.toByteStr,
            parsedSettlements,
            enclaveSignature.toByteStr,
            feeAmount,
            timestamp,
            proofs,
            chainId
          )
        } yield tx

      case Data.Withdraw(WithdrawTransactionData(Some(fromMiner), Some(amount), feeAssetId, `empty`)) =>
        for {
          fromMinerAddress <- fromMiner.toAddress
          tx <- vt.WithdrawTransaction.create(
            sender.toPublicKey,
            fromMinerAddress,
            amount.vanillaAssetId,
            amount.longAmount,
            PBAmounts.toVanillaAssetId(feeAssetId),
            feeAmount,
            timestamp,
            proofs,
            chainId
          )
        } yield tx

      case Data.UpdateCollateral(
            UpdateCollateralTransactionData(
              rootCaCrl,
              pckCrl,
              tcbInfo,
              qeIdentity,
              tcbSigningIssuerChain,
              pckCaIssuerChain,
              `empty`
            )
          ) =>
        vt.UpdateCollateralTransaction.create(
          sender.toPublicKey,
          rootCaCrl.map(_.toByteStr),
          pckCrl.map(_.toByteStr),
          tcbInfo.map(_.toByteStr),
          qeIdentity.map(_.toByteStr),
          tcbSigningIssuerChain.map(_.toByteStr),
          pckCaIssuerChain.map(_.toByteStr),
          feeAmount,
          timestamp,
          proofs,
          chainId
        )

      case _ =>
        Left(TxValidationError.UnsupportedTransactionType)
    }

    result
  }

  def protobuf(tx: VanillaTransaction): PBSignedTransaction = {
    tx match {
      case tx: vt.transfer.TransferTransaction =>
        import tx.*
        val data = TransferTransactionData(
          PBAmounts.toPBAssetId(assetId),
          transfers.map(pt => TransferTransactionData.Transfer(Some(pt.address.toPB), pt.amount.value)),
          attachment.toByteString,
          PBAmounts.toPBAssetId(feeAssetId)
        )
        PBTransactions.create(sender, chainId, fee.value, timestamp, proofs, Data.Transfer(data))

      case tx: vt.assets.exchange.ExchangeTransaction =>
        import tx.*
        val data = ExchangeTransactionData(
          amount.value,
          price.value,
          buyMatcherFee.value,
          sellMatcherFee.value,
          Seq(PBOrders.protobuf(order1), PBOrders.protobuf(order2))
        )
        PBTransactions.create(tx.sender, chainId, fee.value, timestamp, proofs, Data.Exchange(data))

      case tx: vt.lease.LeaseTransaction =>
        import tx.*
        val data = LeaseTransactionData(Some(recipient.toPB), amount.value)
        PBTransactions.create(sender, chainId, fee.value, timestamp, proofs, Data.Lease(data))

      case tx: vt.lease.LeaseCancelTransaction =>
        import tx.*
        val data = LeaseCancelTransactionData(leaseId.toByteString)
        PBTransactions.create(sender, chainId, fee.value, timestamp, proofs, Data.LeaseCancel(data))

      case tx: CommitToGenerationTransaction =>
        import tx.*
        val data = Data.CommitToGeneration(
          CommitToGenerationTransactionData(
            generationPeriodStart = generationPeriodStart.toInt,
            endorserPublicKey = endorserPublicKey.byteStr.toByteString,
            commitmentSignature = commitmentSignature.byteStr.toByteString,
            vrfPublicKey = vrfPublicKey.toByteString,
            vrfCommitmentSignature = vrfCommitmentSignature.toByteString
          )
        )
        PBTransactions.create(sender, chainId, fee.value, timestamp, proofs.proofs, data)

      case tx: vt.StartBoostTransaction =>
        import tx.*
        val data =
          Data.StartBoost(StartBoostTransactionData(Some(validator.toPB), tdxQuote.toByteString, generationPeriodStart.toInt))
        PBTransactions.create(sender, chainId, fee.value, timestamp, proofs, data)

      case tx: vt.BindApiKeyTransaction =>
        import tx.*
        val data = Data.BindApiKey(BindApiKeyTransactionData(enclavePublicKey.toByteString, encryptedApiKey.toByteString))
        PBTransactions.create(sender, chainId, fee.value, timestamp, proofs, data)

      case tx: vt.ReserveTransaction =>
        import tx.*
        val data = Data.Reserve(ReserveTransactionData(Some((assetId, amount.value)), Some(miner.toPB), PBAmounts.toPBAssetId(feeAssetId)))
        PBTransactions.create(sender, chainId, fee.value, timestamp, proofs, data)

      case tx: vt.SettleTransaction =>
        import tx.*
        val data = Data.Settle(
          SettleTransactionData(
            enclavePublicKey.toByteString,
            settlements.map(s => SettleTransactionData.Settlement(Some(s.client.toPB), Some((s.assetId, s.cumulativeSpent.value)))),
            enclaveSignature.toByteString
          )
        )
        PBTransactions.create(sender, chainId, fee.value, timestamp, proofs, data)

      case tx: vt.WithdrawTransaction =>
        import tx.*
        val data = Data.Withdraw(
          WithdrawTransactionData(Some(fromMiner.toPB), Some((assetId, amount.value)), PBAmounts.toPBAssetId(feeAssetId))
        )
        PBTransactions.create(sender, chainId, fee.value, timestamp, proofs, data)

      case tx: vt.UpdateCollateralTransaction =>
        import tx.*
        val data = Data.UpdateCollateral(
          UpdateCollateralTransactionData(
            rootCaCrl.map(_.toByteString),
            pckCrl.map(_.toByteString),
            tcbInfo.map(_.toByteString),
            qeIdentity.map(_.toByteString),
            tcbSigningIssuerChain.map(_.toByteString),
            pckCaIssuerChain.map(_.toByteString)
          )
        )
        PBTransactions.create(sender, chainId, fee.value, timestamp, proofs, data)

      case _ =>
        throw new IllegalArgumentException(s"Unsupported transaction: ${tx.tpe}")
    }
  }

  // The data entry converters are gone along with DataTransaction: the schema no longer has a DataEntry message

  def toByteArrayMerkle(tx: VanillaTransaction): Array[Byte] = toByteArray(tx)

  def toByteArray(tx: VanillaTransaction): Array[Byte] =
    PBTransactionSerializer.bytes(tx)
}
