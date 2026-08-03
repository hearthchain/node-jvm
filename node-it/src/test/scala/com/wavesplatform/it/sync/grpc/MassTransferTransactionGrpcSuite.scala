package com.wavesplatform.it.sync.grpc

import com.google.protobuf.ByteString
import com.wavesplatform.it.NodeConfigs.GenesisAssets
import com.wavesplatform.it.api.SyncGrpcApi.*
import com.wavesplatform.it.sync.*
import com.wavesplatform.protobuf.transaction.MassTransferTransactionData.Transfer
import com.wavesplatform.protobuf.transaction.Recipient
import com.wavesplatform.transaction.transfer.MassTransferTransaction.MaxTransferCount
import com.wavesplatform.transaction.transfer.TransferTransaction.MaxAttachmentSize
import io.grpc.Status.Code

class MassTransferTransactionGrpcSuite extends GrpcBaseTransactionSuite {

  test("asset mass transfer changes asset balances and sender's.waves balance is decreased by fee.") {
    val firstBalance  = sender.wavesBalance(firstAddress)
    val secondBalance = sender.wavesBalance(secondAddress)
    val attachment    = ByteString.copyFrom("mass transfer description".getBytes("UTF-8"))

    val transfers          = List(Transfer(Some(Recipient.of(secondAddress)), transferAmount))
    val assetId            = GenesisAssets.TestAsset.id.toString
    val assetBalanceBefore = sender.assetsBalance(firstAddress, Seq(assetId)).getOrElse(assetId, 0L)

    val massTransferTransactionFee = calcMassTransferFee(transfers.size)
    sender.broadcastMassTransfer(firstAcc, Some(assetId), transfers, attachment, massTransferTransactionFee, waitForTx = true)

    val firstBalanceAfter  = sender.wavesBalance(firstAddress)
    val secondBalanceAfter = sender.wavesBalance(secondAddress)

    firstBalanceAfter.regular shouldBe firstBalance.regular - massTransferTransactionFee
    firstBalanceAfter.effective shouldBe firstBalance.effective - massTransferTransactionFee
    sender.assetsBalance(firstAddress, Seq(assetId)).getOrElse(assetId, 0L) shouldBe assetBalanceBefore - transferAmount
    secondBalanceAfter.regular shouldBe secondBalance.regular
    secondBalanceAfter.effective shouldBe secondBalance.effective
  }

  test("waves mass transfer changes waves balances") {
    val firstBalance  = sender.wavesBalance(firstAddress)
    val secondBalance = sender.wavesBalance(secondAddress)
    val thirdBalance  = sender.wavesBalance(thirdAddress)
    val transfers = List(
      Transfer(Some(Recipient.of(secondAddress)), transferAmount),
      Transfer(Some(Recipient.of(thirdAddress)), 2 * transferAmount)
    )

    val massTransferTransactionFee = calcMassTransferFee(transfers.size)
    sender.broadcastMassTransfer(firstAcc, transfers = transfers, fee = massTransferTransactionFee, waitForTx = true)

    val firstBalanceAfter  = sender.wavesBalance(firstAddress)
    val secondBalanceAfter = sender.wavesBalance(secondAddress)
    val thirdBalanceAfter  = sender.wavesBalance(thirdAddress)

    firstBalanceAfter.regular shouldBe firstBalance.regular - massTransferTransactionFee - 3 * transferAmount
    firstBalanceAfter.effective shouldBe firstBalance.effective - massTransferTransactionFee - 3 * transferAmount
    secondBalanceAfter.regular shouldBe secondBalance.regular + transferAmount
    secondBalanceAfter.effective shouldBe secondBalance.effective + transferAmount
    thirdBalanceAfter.regular shouldBe thirdBalance.regular + 2 * transferAmount
    thirdBalanceAfter.effective shouldBe thirdBalance.effective + 2 * transferAmount
  }

  test("can not make mass transfer without having enough waves") {
    val firstBalance  = sender.wavesBalance(firstAddress)
    val secondBalance = sender.wavesBalance(secondAddress)
    val transfers = List(
      Transfer(Some(Recipient.of(secondAddress)), firstBalance.regular / 2),
      Transfer(Some(Recipient.of(thirdAddress)), firstBalance.regular / 2)
    )

    assertGrpcError(
      sender.broadcastMassTransfer(firstAcc, transfers = transfers, fee = calcMassTransferFee(transfers.size)),
      "Attempt to transfer unavailable funds",
      Code.INVALID_ARGUMENT
    )

    nodes.foreach(n => n.waitForHeight(n.height + 1))
    sender.wavesBalance(firstAddress) shouldBe firstBalance
    sender.wavesBalance(secondAddress) shouldBe secondBalance
  }

  // TODO: minimum-fee validation isn't implemented yet (FeeValidation.getMinFee is computed but never checked
  // by TransactionDiffer/CommonValidation); restore this case once fee rules are designed and enforced (see
  // TransferTransactionSuite's analogous commented-out case).
  ignore("cannot make mass transfer when fee less then minimal ") {
    val firstBalance               = sender.wavesBalance(firstAddress)
    val secondBalance              = sender.wavesBalance(secondAddress)
    val transfers                  = List(Transfer(Some(Recipient.of(secondAddress)), transferAmount))
    val massTransferTransactionFee = calcMassTransferFee(transfers.size)

    assertGrpcError(
      sender.broadcastMassTransfer(firstAcc, transfers = transfers, fee = massTransferTransactionFee - 1),
      s"does not exceed minimal value of $massTransferTransactionFee WAVES",
      Code.INVALID_ARGUMENT
    )

    nodes.foreach(n => n.waitForHeight(n.height + 1))
    sender.wavesBalance(firstAddress) shouldBe firstBalance
    sender.wavesBalance(secondAddress) shouldBe secondBalance
  }

  test("cannot make mass transfer without having enough of effective balance") {
    val firstBalance               = sender.wavesBalance(firstAddress)
    val secondBalance              = sender.wavesBalance(secondAddress)
    val transfers                  = List(Transfer(Some(Recipient.of(secondAddress)), firstBalance.regular - 2 * minFee))
    val massTransferTransactionFee = calcMassTransferFee(transfers.size)

    sender.broadcastLease(firstAcc, Recipient.of(secondAddress), leasingAmount, minFee, waitForTx = true)

    assertGrpcError(
      sender.broadcastMassTransfer(firstAcc, transfers = transfers, fee = massTransferTransactionFee),
      "Attempt to transfer unavailable funds",
      Code.INVALID_ARGUMENT
    )
    nodes.foreach(n => n.waitForHeight(n.height + 1))
    sender.wavesBalance(firstAddress).regular shouldBe firstBalance.regular - minFee
    sender.wavesBalance(firstAddress).effective shouldBe firstBalance.effective - minFee - leasingAmount
    sender.wavesBalance(secondAddress).regular shouldBe secondBalance.regular
    sender.wavesBalance(secondAddress).effective shouldBe secondBalance.effective + leasingAmount
  }

  test("cannot broadcast invalid mass transfer tx") {
    val firstBalance    = sender.wavesBalance(firstAddress)
    val secondBalance   = sender.wavesBalance(secondAddress)
    val defaultTransfer = List(Transfer(Some(Recipient.of(secondAddress)), transferAmount))

    // All three of these are structural checks (MassTransferTxValidator plus TxNonNegativeAmount's own bounds
    // check), enforced by MassTransferTransaction.create - which PBTransactions.vanilla runs, inside
    // broadcastMassTransfer itself, to compute bodyBytes for signing, before any gRPC call is made. So each fails
    // as a plain client-side RuntimeException (EitherExt2.explicitGet on a Left(ValidationError)), not a
    // GrpcStatusRuntimeException from the server, and assertGrpcError doesn't apply here.
    val negativeTransfer = List(Transfer(Some(Recipient.of(secondAddress)), -1))
    the[RuntimeException] thrownBy sender.broadcastMassTransfer(
      firstAcc,
      transfers = negativeTransfer,
      fee = calcMassTransferFee(negativeTransfer.size)
    ) should have message "NegativeAmount(-1,asset)"

    val tooManyTransfers = List.fill(MaxTransferCount + 1)(Transfer(Some(Recipient.of(secondAddress)), 1))
    the[RuntimeException] thrownBy sender.broadcastMassTransfer(
      firstAcc,
      transfers = tooManyTransfers,
      fee = calcMassTransferFee(MaxTransferCount + 1)
    ) should have message s"GenericError(Number of transfers ${MaxTransferCount + 1} is greater than $MaxTransferCount)"

    val tooBigAttachment = ByteString.copyFrom(("a" * (MaxAttachmentSize + 1)).getBytes("UTF-8"))
    the[RuntimeException] thrownBy sender.broadcastMassTransfer(
      firstAcc,
      transfers = defaultTransfer,
      attachment = tooBigAttachment,
      fee = calcMassTransferFee(1)
    ) should have message s"TooBigInBytes(Invalid attachment. Length ${MaxAttachmentSize + 1} bytes exceeds maximum of $MaxAttachmentSize bytes.)"

    sender.wavesBalance(firstAddress) shouldBe firstBalance
    sender.wavesBalance(secondAddress) shouldBe secondBalance
  }

  test("huge transactions are allowed") {
    val firstBalance  = sender.wavesBalance(firstAddress)
    val fee           = calcMassTransferFee(MaxTransferCount)
    val amount        = (firstBalance.available - fee) / MaxTransferCount
    val maxAttachment = ByteString.copyFrom(("a" * MaxAttachmentSize).getBytes("UTF-8"))

    val transfers = List.fill(MaxTransferCount)(Transfer(Some(Recipient.of(firstAddress)), amount))
    sender.broadcastMassTransfer(firstAcc, transfers = transfers, fee = fee, attachment = maxAttachment, waitForTx = true)

    sender.wavesBalance(firstAddress).regular shouldBe firstBalance.regular - fee
    sender.wavesBalance(firstAddress).effective shouldBe firstBalance.effective - fee
  }

}
