package tech.hearth.it.sync.grpc

import com.google.protobuf.ByteString
import tech.hearth.api.grpc.LeaseResponse
import tech.hearth.common.utils.EitherExt2.*
import tech.hearth.it.api.SyncGrpcApi.*
import tech.hearth.it.sync.*
import tech.hearth.protobuf.transaction.{PBRecipients, PBTransactions, Recipient}
import tech.hearth.test.*
import tech.hearth.transaction.Transaction
import tech.hearth.transaction.lease.LeaseTransaction
import io.grpc.Status.Code

class LeasingTransactionsGrpcSuite extends GrpcBaseTransactionSuite {
  private val errorMessage = "Reason: Cannot lease more than own"

  test("leasing hearth decreases lessor's eff.b. and increases lessee's eff.b.; lessor pays fee") {
    val firstBalance  = sender.hearthBalance(firstAddress)
    val secondBalance = sender.hearthBalance(secondAddress)

    val leaseTx   = sender.broadcastLease(firstAcc, PBRecipients.create(secondAcc.toAddress), leasingAmount, minFee, waitForTx = true)
    val vanillaTx = PBTransactions.vanilla(leaseTx).explicitGet()
    val leaseTxId = vanillaTx.id().toString
    val height    = sender.getStatus(leaseTxId).height

    sender.hearthBalance(firstAddress).regular shouldBe firstBalance.regular - minFee
    sender.hearthBalance(firstAddress).effective shouldBe firstBalance.effective - minFee - leasingAmount
    sender.hearthBalance(secondAddress).regular shouldBe secondBalance.regular
    sender.hearthBalance(secondAddress).effective shouldBe secondBalance.effective + leasingAmount

    val response = toResponse(vanillaTx, height)
    sender.getActiveLeases(secondAddress) shouldBe List(response)
    sender.getActiveLeases(firstAddress) shouldBe List(response)

    sender.broadcastLeaseCancel(firstAcc, leaseTxId, minFee, waitForTx = true)
  }

  test("cannot lease non-own hearth") {
    val leaseTx   = sender.broadcastLease(firstAcc, PBRecipients.create(secondAcc.toAddress), leasingAmount, minFee, waitForTx = true)
    val vanillaTx = PBTransactions.vanilla(leaseTx).explicitGet()
    val leaseTxId = vanillaTx.id().toString
    val height    = sender.getStatus(leaseTxId).height

    val secondEffBalance = sender.hearthBalance(secondAddress).effective
    val thirdEffBalance  = sender.hearthBalance(thirdAddress).effective

    assertGrpcError(
      sender.broadcastLease(secondAcc, PBRecipients.create(thirdAcc.toAddress), secondEffBalance - minFee, minFee),
      errorMessage,
      Code.INVALID_ARGUMENT
    )

    sender.hearthBalance(secondAddress).effective shouldBe secondEffBalance
    sender.hearthBalance(thirdAddress).effective shouldBe thirdEffBalance

    val response = toResponse(vanillaTx, height)
    sender.getActiveLeases(secondAddress) shouldBe List(response)
    sender.getActiveLeases(thirdAddress) shouldBe List.empty

    sender.broadcastLeaseCancel(firstAcc, leaseTxId, minFee, waitForTx = true)
  }

  test("can not make leasing without having enough balance") {
    val firstBalance  = sender.hearthBalance(firstAddress)
    val secondBalance = sender.hearthBalance(secondAddress)

    // secondAddress effective balance more than general balance
    assertGrpcError(
      sender.broadcastLease(secondAcc, Recipient().withPublicKeyHash(firstAddress), secondBalance.regular + 1.hearth, minFee),
      errorMessage,
      Code.INVALID_ARGUMENT
    )

    assertGrpcError(
      sender.broadcastLease(firstAcc, Recipient().withPublicKeyHash(secondAddress), firstBalance.regular, minFee),
      errorMessage,
      Code.INVALID_ARGUMENT
    )

    assertGrpcError(
      sender.broadcastLease(firstAcc, Recipient().withPublicKeyHash(secondAddress), firstBalance.regular - minFee / 2, minFee),
      errorMessage,
      Code.INVALID_ARGUMENT
    )

    sender.hearthBalance(firstAddress) shouldBe firstBalance
    sender.hearthBalance(secondAddress) shouldBe secondBalance
    sender.getActiveLeases(firstAddress) shouldBe List.empty
    sender.getActiveLeases(secondAddress) shouldBe List.empty
  }

  test("lease cancellation reverts eff.b. changes; lessor pays fee for both lease and cancellation") {
    val firstBalance  = sender.hearthBalance(firstAddress)
    val secondBalance = sender.hearthBalance(secondAddress)

    val leaseTx   = sender.broadcastLease(firstAcc, PBRecipients.create(secondAcc.toAddress), leasingAmount, minFee, waitForTx = true)
    val leaseTxId = PBTransactions.vanilla(leaseTx).explicitGet().id().toString

    sender.broadcastLeaseCancel(firstAcc, leaseTxId, minFee, waitForTx = true)

    sender.hearthBalance(firstAddress).regular shouldBe firstBalance.regular - 2 * minFee
    sender.hearthBalance(firstAddress).effective shouldBe firstBalance.effective - 2 * minFee
    sender.hearthBalance(secondAddress).regular shouldBe secondBalance.regular
    sender.hearthBalance(secondAddress).effective shouldBe secondBalance.effective
    sender.getActiveLeases(secondAddress) shouldBe List.empty
    sender.getActiveLeases(firstAddress) shouldBe List.empty
  }

  test("lease cancellation can be done only once") {
    val firstBalance  = sender.hearthBalance(firstAddress)
    val secondBalance = sender.hearthBalance(secondAddress)

    val leaseTx   = sender.broadcastLease(firstAcc, PBRecipients.create(secondAcc.toAddress), leasingAmount, minFee, waitForTx = true)
    val leaseTxId = PBTransactions.vanilla(leaseTx).explicitGet().id().toString

    sender.broadcastLeaseCancel(firstAcc, leaseTxId, minFee, waitForTx = true)

    assertGrpcError(
      sender.broadcastLeaseCancel(firstAcc, leaseTxId, minFee),
      "Reason: Cannot cancel already cancelled lease",
      Code.INVALID_ARGUMENT
    )
    sender.hearthBalance(firstAddress).regular shouldBe firstBalance.regular - 2 * minFee
    sender.hearthBalance(firstAddress).effective shouldBe firstBalance.effective - 2 * minFee
    sender.hearthBalance(secondAddress).regular shouldBe secondBalance.regular
    sender.hearthBalance(secondAddress).effective shouldBe secondBalance.effective

    sender.getActiveLeases(secondAddress) shouldBe List.empty
    sender.getActiveLeases(firstAddress) shouldBe List.empty
  }

  test("only sender can cancel lease transaction") {
    val firstBalance  = sender.hearthBalance(firstAddress)
    val secondBalance = sender.hearthBalance(secondAddress)

    val leaseTx   = sender.broadcastLease(firstAcc, PBRecipients.create(secondAcc.toAddress), leasingAmount, minFee, waitForTx = true)
    val vanillaTx = PBTransactions.vanilla(leaseTx).explicitGet()
    val leaseTxId = vanillaTx.id().toString
    val height    = sender.getStatus(leaseTxId).height

    assertGrpcError(
      sender.broadcastLeaseCancel(secondAcc, leaseTxId, minFee),
      "LeaseTransaction was leased by other sender",
      Code.INVALID_ARGUMENT
    )
    sender.hearthBalance(firstAddress).regular shouldBe firstBalance.regular - minFee
    sender.hearthBalance(firstAddress).effective shouldBe firstBalance.effective - minFee - leasingAmount
    sender.hearthBalance(secondAddress).regular shouldBe secondBalance.regular
    sender.hearthBalance(secondAddress).effective shouldBe secondBalance.effective + leasingAmount

    val response = toResponse(vanillaTx, height)
    sender.getActiveLeases(secondAddress) shouldBe List(response)
    sender.getActiveLeases(firstAddress) shouldBe List(response)

    sender.broadcastLeaseCancel(firstAcc, leaseTxId, minFee, waitForTx = true)
  }

  test("can not make leasing to yourself") {
    val firstBalance = sender.hearthBalance(firstAddress)
    // A self-lease is now caught by PBTransactions.vanilla's construction-time validation inside broadcastLease
    // itself (LeaseTxValidator, run while building the domain LeaseTransaction from the protobuf request), before
    // any gRPC call is made - so it surfaces as a plain client-side RuntimeException (EitherExt2.explicitGet on a
    // Left(TxValidationError.ToSelf)), not a GrpcStatusRuntimeException from the server, and assertGrpcError
    // doesn't apply here.
    the[RuntimeException] thrownBy sender.broadcastLease(
      firstAcc,
      PBRecipients.create(firstAcc.toAddress),
      leasingAmount,
      minFee
    ) should have message "ToSelf"
    sender.hearthBalance(firstAddress).regular shouldBe firstBalance.regular
    sender.hearthBalance(firstAddress).effective shouldBe firstBalance.effective
    sender.getActiveLeases(firstAddress) shouldBe List.empty
  }

  private def toResponse(tx: Transaction, height: Long): LeaseResponse = {
    val leaseTx   = tx.asInstanceOf[LeaseTransaction]
    val leaseTxId = ByteString.copyFrom(leaseTx.id().arr)
    LeaseResponse(
      leaseId = leaseTxId,
      originTransactionId = leaseTxId,
      sender = ByteString.copyFrom(leaseTx.sender.toAddress.toBytes()),
      recipient = Some(PBRecipients.create(leaseTx.recipient)),
      amount = leaseTx.amount.value,
      height = height.toInt
    )
  }
}
