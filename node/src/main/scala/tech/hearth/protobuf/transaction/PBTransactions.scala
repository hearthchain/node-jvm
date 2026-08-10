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
import tech.hearth.transaction.Asset.Waves
import tech.hearth.transaction.TxValidationError.{GenericError, NegativeAmount}
import tech.hearth.transaction.serialization.impl.PBTransactionSerializer
import tech.hearth.transaction.transfer.MassTransferTransaction
import tech.hearth.transaction.transfer.MassTransferTransaction.ParsedTransfer
import tech.hearth.transaction.{CommitToGenerationTransaction, Proofs, TxNonNegativeAmount, TxValidationError}
import scalapb.UnknownFieldSet.empty

object PBTransactions {

  def create(
      sender: tech.hearth.account.PublicKey,
      chainId: Byte = 0,
      fee: Long = 0L,
      feeAssetId: VanillaAssetId = Waves,
      timestamp: Long = 0L,
      proofsArray: Seq[tech.hearth.common.state.ByteStr] = Nil,
      data: tech.hearth.protobuf.transaction.Transaction.Data = tech.hearth.protobuf.transaction.Transaction.Data.Empty
  ): SignedTransaction =
    new SignedTransaction(
      Some(Transaction(chainId, sender.toByteString, Some((feeAssetId, fee): Amount), timestamp, data)),
      proofsArray.map(bs => ByteString.copyFrom(bs.arr))
    )

  def vanilla(signedTx: PBSignedTransaction): Either[ValidationError, VanillaTransaction] =
    signedTx.wavesTransaction match {
      case Some(parsedTx) =>
        val (feeAsset, feeAmount) = PBAmounts.toAssetAndAmount(parsedTx.fee.getOrElse(Amount.defaultInstance))
        for {
          proofs <- Proofs.create(signedTx.proofs.map(_.toByteStr))
          tx <- createVanilla(
            parsedTx.chainId.toByte,
            parsedTx.senderPublicKey,
            feeAmount,
            feeAsset,
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
      feeAssetId: VanillaAssetId,
      timestamp: Long,
      proofs: Proofs,
      data: PBTransaction.Data
  ): Either[ValidationError, VanillaTransaction] = {

    val result: Either[ValidationError, VanillaTransaction] = data match {
      case Data.Transfer(TransferTransactionData(Some(recipient), Some(amount), attachment, `empty`)) =>
        for {
          address <- recipient.toAddress
          tx <- vt.transfer.TransferTransaction.create(
            sender.toPublicKey,
            address,
            amount.vanillaAssetId,
            amount.longAmount,
            feeAssetId,
            feeAmount,
            attachment.toByteStr,
            timestamp,
            proofs,
            // An address no longer carries the network it belongs to, so the chain id has to come from the transaction
            // itself - without it, `create` defaults to the network this node runs on and the round trip loses it
            chainId
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

      case Data.MassTransfer(mt) =>
        for {
          parsedTransfers <- mt.transfers.traverse { t =>
            t.getRecipient.toAddress.flatMap { addressOrAlias =>
              TxNonNegativeAmount(t.amount)(NegativeAmount(t.amount, "asset"))
                .map(ParsedTransfer(addressOrAlias, _))
            }
          }
          tx <- vt.transfer.MassTransferTransaction.create(
            sender.toPublicKey,
            PBAmounts.toVanillaAssetId(mt.assetId),
            parsedTransfers,
            feeAmount,
            timestamp,
            mt.attachment.toByteStr,
            proofs,
            chainId,
            feeAssetId
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

      case _ =>
        Left(TxValidationError.UnsupportedTransactionType)
    }

    result
  }

  def protobuf(tx: VanillaTransaction): PBSignedTransaction = {
    tx match {
      case tx: vt.transfer.TransferTransaction =>
        import tx.*
        val data = TransferTransactionData(Some(recipient.toPB), Some((assetId, amount.value)), attachment.toByteString)
        PBTransactions.create(sender, chainId, fee.value, feeAssetId, timestamp, proofs, Data.Transfer(data))

      case tx: vt.assets.exchange.ExchangeTransaction =>
        import tx.*
        val data = ExchangeTransactionData(
          amount.value,
          price.value,
          buyMatcherFee.value,
          sellMatcherFee.value,
          Seq(PBOrders.protobuf(order1), PBOrders.protobuf(order2))
        )
        PBTransactions.create(tx.sender, chainId, fee.value, tx.feeAssetId, timestamp, proofs, Data.Exchange(data))

      case tx: vt.lease.LeaseTransaction =>
        import tx.*
        val data = LeaseTransactionData(Some(recipient.toPB), amount.value)
        PBTransactions.create(sender, chainId, fee.value, tx.feeAssetId, timestamp, proofs, Data.Lease(data))

      case tx: vt.lease.LeaseCancelTransaction =>
        import tx.*
        val data = LeaseCancelTransactionData(leaseId.toByteString)
        PBTransactions.create(sender, chainId, fee.value, tx.feeAssetId, timestamp, proofs, Data.LeaseCancel(data))

      case tx @ MassTransferTransaction(sender, assetId, transfers, fee, _, timestamp, attachment, proofs, chainId) =>
        val data = MassTransferTransactionData(
          PBAmounts.toPBAssetId(assetId),
          transfers.map(pt => MassTransferTransactionData.Transfer(Some(pt.address.toPB), pt.amount.value)),
          attachment.toByteString
        )
        PBTransactions.create(sender, chainId, fee.value, tx.feeAssetId, timestamp, proofs, Data.MassTransfer(data))

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
        PBTransactions.create(sender, chainId, fee.value, Waves, timestamp, proofs.proofs, data)

      case _ =>
        throw new IllegalArgumentException(s"Unsupported transaction: $tx")
    }
  }

  // The data entry converters are gone along with DataTransaction: the schema no longer has a DataEntry message

  def toByteArrayMerkle(tx: VanillaTransaction): Array[Byte] = toByteArray(tx)

  def toByteArray(tx: VanillaTransaction): Array[Byte] =
    PBTransactionSerializer.bytes(tx)
}
