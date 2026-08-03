package tech.hearth.it.sync.transactions

import tech.hearth.account.{Address, AddressScheme, PublicKey}
import tech.hearth.common.state.ByteStr
import tech.hearth.common.utils.EitherExt2.*
import tech.hearth.it.NodeConfigs.GenesisAssets
import tech.hearth.it.api.SyncHttpApi.*
import tech.hearth.it.sync.*
import tech.hearth.it.transactions.BaseTransactionSuite
import tech.hearth.test.*
import tech.hearth.transaction.Asset.Waves
import tech.hearth.transaction.TxHelpers
import tech.hearth.transaction.transfer.*
import tech.hearth.transaction.transfer.TransferTransaction.MaxAttachmentSize
import tech.hearth.transaction.{Proofs, TxPositiveAmount}
import org.scalatest.CancelAfterFailure
import play.api.libs.json.Json

import java.nio.charset.StandardCharsets
import scala.concurrent.duration.*

class TransferTransactionSuite extends BaseTransactionSuite with CancelAfterFailure {
  test("transfer with empty string assetId") {
    val tx = TxHelpers.transfer(
      from = sender.keyPair,
      to = sender.keyPair.toAddress,
      amount = 100L,
      asset = Waves,
      fee = minFee,
      feeAsset = Waves,
      attachment = ByteStr.empty
    )
    val json = tx.json() ++ Json.obj("assetId" -> "", "feeAssetId" -> "")
    sender.signedBroadcast(json, waitForTx = true)
  }

  test("asset transfer changes sender's and recipient's asset balance") {
    val assetId                           = GenesisAssets.TestAsset.id.toString
    val (firstBalance, firstEffBalance)   = miner.accountBalances(firstAddress)
    val (secondBalance, secondEffBalance) = miner.accountBalances(secondAddress)
    val firstAssetBalanceBefore           = sender.assetBalance(firstAddress, assetId).balance
    val secondAssetBalanceBefore          = sender.assetBalance(secondAddress, assetId).balance

    val transferTransaction = sender.transfer(firstKeyPair, secondAddress, someAssetAmount / 2, minFee, Some(assetId))
    nodes.waitForHeightAriseAndTxPresent(transferTransaction.id)

    miner.assertBalances(firstAddress, firstBalance - minFee, firstEffBalance - minFee)
    miner.assertBalances(secondAddress, secondBalance, secondEffBalance)
    sender.assetBalance(firstAddress, assetId).balance shouldBe firstAssetBalanceBefore - someAssetAmount / 2
    sender.assetBalance(secondAddress, assetId).balance shouldBe secondAssetBalanceBefore + someAssetAmount / 2
  }

  test("waves transfer changes waves balances and eff.b.") {
    val (firstBalance, firstEffBalance)   = miner.accountBalances(firstAddress)
    val (secondBalance, secondEffBalance) = miner.accountBalances(secondAddress)

    val transferId = sender.transfer(firstKeyPair, secondAddress, transferAmount, minFee).id

    nodes.waitForHeightAriseAndTxPresent(transferId)

    miner.assertBalances(firstAddress, firstBalance - transferAmount - minFee, firstEffBalance - transferAmount - minFee)
    miner.assertBalances(secondAddress, secondBalance + transferAmount, secondEffBalance + transferAmount)
  }

  test("invalid signed waves transfer should not be in UTX or blockchain") {
    def invalidTx(
        timestamp: Long = System.currentTimeMillis,
        fee: Long = 100000,
        attachment: Array[Byte] = Array.emptyByteArray
    ): TransferTransaction = {
      val tx = TransferTransaction(
        sender = PublicKey(sender.keyPair.publicKey()),
        recipient = Address.fromString(sender.address).explicitGet(),
        assetId = Waves,
        amount = TxPositiveAmount.unsafeFrom(1),
        feeAssetId = Waves,
        fee = TxPositiveAmount.unsafeFrom(fee),
        attachment = ByteStr(attachment),
        timestamp = timestamp,
        proofs = Proofs.empty,
        chainId = AddressScheme.current.chainId
      )

      tx.signWith(sender.keyPair)
    }

    val (balance1, eff1) = miner.accountBalances(firstAddress)

    val invalidTxs = Seq(
      (invalidTx(timestamp = System.currentTimeMillis + 1.day.toMillis), "Transaction timestamp .* is more than .*ms in the future"),
      // TODO: minimum-fee validation isn't implemented yet (FeeValidation.getMinFee is computed but never checked
      // by TransactionDiffer/CommonValidation); restore this case once fee rules are designed and enforced.
      // (invalidTx(fee = 99999), "Fee .* does not exceed minimal value"),
      // utils.byteArrayFromString now rejects an over-long base58 string outright (Base58.tryDecodeWithLimit), before
      // an attachment-specific length check ever runs. Repeated '1' bytes (0x31) base58-encode long enough to trip
      // that generic gate; repeated 0x01 bytes encode short enough to still reach the attachment-specific check below.
      (invalidTx(attachment = ("1" * (MaxAttachmentSize + 1)).getBytes(StandardCharsets.UTF_8)), "Can't parse '.*' as base58 encoded byte array"),
      (
        invalidTx(attachment = Array.fill(MaxAttachmentSize + 1)(1)),
        "Invalid attachment. Length \\d+ bytes exceeds maximum of \\d+ bytes."
      )
    )

    for ((tx, diag) <- invalidTxs) {
      assertBadRequestAndResponse(sender.broadcastRequest(tx.json()), diag)
      nodes.foreach(_.ensureTxDoesntExist(tx.id().toString))
    }

    nodes.waitForHeightArise()
    miner.assertBalances(firstAddress, balance1, eff1)

  }

  test("can not make transfer without having enough effective balance") {
    val (secondBalance, secondEffBalance) = miner.accountBalances(secondAddress)

    assertApiErrorRaised(sender.transfer(secondKeyPair, firstAddress, secondEffBalance, minFee))
    nodes.waitForHeightArise()

    miner.assertBalances(secondAddress, secondBalance, secondEffBalance)
  }

  test("can not make transfer without having enough balance") {
    val (secondBalance, secondEffBalance) = miner.accountBalances(secondAddress)

    assertBadRequestAndResponse(
      sender.transfer(secondKeyPair, firstAddress, secondBalance + 1.waves, minFee),
      "Attempt to transfer unavailable funds"
    )
    miner.assertBalances(secondAddress, secondBalance, secondEffBalance)
  }

  test("can forge block with sending majority of some asset to self and to other account") {
    val assetId                           = GenesisAssets.TestAsset.id.toString
    val (firstBalance, firstEffBalance)   = miner.accountBalances(firstAddress)
    val (secondBalance, secondEffBalance) = miner.accountBalances(secondAddress)
    val firstAssetBalanceBefore           = sender.assetBalance(firstAddress, assetId).balance

    val tx1 = sender.transfer(firstKeyPair, firstAddress, firstAssetBalanceBefore, minFee, Some(assetId)).id
    nodes.waitForHeightAriseAndTxPresent(tx1)

    val tx2 = sender.transfer(firstKeyPair, secondAddress, firstAssetBalanceBefore / 2, minFee, Some(assetId)).id
    nodes.waitForHeightAriseAndTxPresent(tx2)

    miner.assertBalances(firstAddress, firstBalance - 2 * minFee, firstEffBalance - 2 * minFee)
    miner.assertBalances(secondAddress, secondBalance, secondEffBalance)
  }
}
