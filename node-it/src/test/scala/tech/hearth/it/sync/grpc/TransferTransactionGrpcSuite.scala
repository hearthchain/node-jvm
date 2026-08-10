package tech.hearth.it.sync.grpc

import tech.hearth.it.NTPTime
import tech.hearth.it.NodeConfigs.GenesisAssets
import tech.hearth.it.api.SyncGrpcApi.*
import tech.hearth.it.sync.*
import tech.hearth.protobuf.transaction.Recipient
import io.grpc.Status.Code

import scala.concurrent.duration.*

class TransferTransactionGrpcSuite extends GrpcBaseTransactionSuite with NTPTime {

  val issuedAssetId: String = GenesisAssets.TestAsset.id.toString

  test("asset transfer changes sender's and recipient's asset balance by transfer amount and hearth by fee") {
    val issuedAssetId    = GenesisAssets.TestAsset.id.toString
    val firstBalance     = sender.hearthBalance(firstAddress).available
    val firstEffBalance  = sender.hearthBalance(firstAddress).effective
    val secondBalance    = sender.hearthBalance(secondAddress).available
    val secondEffBalance = sender.hearthBalance(secondAddress).effective
    // The fixture asset is shared across this loop's iterations (and other tests), so balances accumulate
    // rather than starting from a fresh issuance each time; assert deltas, not absolute totals.
    val firstAssetBefore  = sender.assetsBalance(firstAddress, Seq(issuedAssetId)).getOrElse(issuedAssetId, 0L)
    val secondAssetBefore = sender.assetsBalance(secondAddress, Seq(issuedAssetId)).getOrElse(issuedAssetId, 0L)

    sender.broadcastTransfer(
      firstAcc,
      Recipient().withPublicKeyHash(secondAddress),
      someAssetAmount,
      minFee,
      issuedAssetId,
      waitForTx = true
    )

    sender.hearthBalance(firstAddress).available shouldBe firstBalance - minFee
    sender.hearthBalance(firstAddress).effective shouldBe firstEffBalance - minFee
    sender.hearthBalance(secondAddress).available shouldBe secondBalance
    sender.hearthBalance(secondAddress).effective shouldBe secondEffBalance

    sender.assetsBalance(firstAddress, Seq(issuedAssetId)).getOrElse(issuedAssetId, 0L) shouldBe firstAssetBefore - someAssetAmount
    sender.assetsBalance(secondAddress, Seq(issuedAssetId)).getOrElse(issuedAssetId, 0L) shouldBe secondAssetBefore + someAssetAmount
  }

  test("hearth transfer changes hearth balances and eff.b. by transfer amount and fee") {
    val firstBalance     = sender.hearthBalance(firstAddress).available
    val firstEffBalance  = sender.hearthBalance(firstAddress).effective
    val secondBalance    = sender.hearthBalance(secondAddress).available
    val secondEffBalance = sender.hearthBalance(secondAddress).effective

    sender.broadcastTransfer(firstAcc, Recipient().withPublicKeyHash(secondAddress), transferAmount, minFee, waitForTx = true)

    sender.hearthBalance(firstAddress).available shouldBe firstBalance - transferAmount - minFee
    sender.hearthBalance(firstAddress).effective shouldBe firstEffBalance - transferAmount - minFee
    sender.hearthBalance(secondAddress).available shouldBe secondBalance + transferAmount
    sender.hearthBalance(secondAddress).effective shouldBe secondEffBalance + transferAmount
  }

  test("invalid signed hearth transfer should not be in UTX or blockchain") {
    val invalidTimestampFromFuture = ntpTime.correctedTime() + 91.minutes.toMillis
    val invalidTimestampFromPast   = ntpTime.correctedTime() - 121.minutes.toMillis
    val firstBalance               = sender.hearthBalance(firstAddress).available
    val firstEffBalance            = sender.hearthBalance(firstAddress).effective
    val secondBalance              = sender.hearthBalance(secondAddress).available
    val secondEffBalance           = sender.hearthBalance(secondAddress).effective

    assertGrpcError(
      sender.broadcastTransfer(
        firstAcc,
        Recipient().withPublicKeyHash(secondAddress),
        transferAmount,
        minFee,
        timestamp = invalidTimestampFromFuture,
        waitForTx = true
      ),
      "Transaction timestamp .* is more than .*ms in the future",
      Code.INVALID_ARGUMENT
    )
    assertGrpcError(
      sender.broadcastTransfer(
        firstAcc,
        Recipient().withPublicKeyHash(secondAddress),
        transferAmount,
        minFee,
        timestamp = invalidTimestampFromPast,
        waitForTx = true
      ),
      "Transaction timestamp .* is more than .*ms in the past",
      Code.INVALID_ARGUMENT
    )
    // TODO: minimum-fee validation isn't implemented yet (FeeValidation.getMinFee is computed but never checked
    // by TransactionDiffer/CommonValidation); restore this case once fee rules are designed and enforced (see
    // TransferTransactionSuite's analogous commented-out case).
    // assertGrpcError(
    //   sender.broadcastTransfer(firstAcc, Recipient().withPublicKeyHash(secondAddress), transferAmount, minFee - 1, waitForTx = true),
    //   "Fee .* does not exceed minimal value",
    //   Code.INVALID_ARGUMENT
    // )

    sender.hearthBalance(firstAddress).available shouldBe firstBalance
    sender.hearthBalance(firstAddress).effective shouldBe firstEffBalance
    sender.hearthBalance(secondAddress).available shouldBe secondBalance
    sender.hearthBalance(secondAddress).effective shouldBe secondEffBalance
  }

  test("can not make transfer without having enough hearth balance") {
    val firstBalance     = sender.hearthBalance(firstAddress).available
    val firstEffBalance  = sender.hearthBalance(firstAddress).effective
    val secondBalance    = sender.hearthBalance(secondAddress).available
    val secondEffBalance = sender.hearthBalance(secondAddress).effective

    assertGrpcError(
      sender.broadcastTransfer(firstAcc, Recipient().withPublicKeyHash(secondAddress), firstBalance, minFee, waitForTx = true),
      "Attempt to transfer unavailable funds",
      Code.INVALID_ARGUMENT
    )

    sender.hearthBalance(firstAddress).available shouldBe firstBalance
    sender.hearthBalance(firstAddress).effective shouldBe firstEffBalance
    sender.hearthBalance(secondAddress).available shouldBe secondBalance
    sender.hearthBalance(secondAddress).effective shouldBe secondEffBalance
  }

  test("can not make assets transfer without having enough assets balance") {
    val firstAssetBalance  = sender.assetsBalance(firstAddress, Seq(issuedAssetId)).getOrElse(issuedAssetId, 0L)
    val secondAssetBalance = sender.assetsBalance(secondAddress, Seq(issuedAssetId)).getOrElse(issuedAssetId, 0L)

    assertGrpcError(
      sender.broadcastTransfer(
        firstAcc,
        Recipient().withPublicKeyHash(secondAddress),
        firstAssetBalance + 1,
        minFee,
        assetId = issuedAssetId,
        waitForTx = true
      ),
      "Attempt to transfer unavailable funds",
      Code.INVALID_ARGUMENT
    )

    sender.assetsBalance(firstAddress, Seq(issuedAssetId)).getOrElse(issuedAssetId, 0L) shouldBe firstAssetBalance
    sender.assetsBalance(secondAddress, Seq(issuedAssetId)).getOrElse(issuedAssetId, 0L) shouldBe secondAssetBalance
  }
}
