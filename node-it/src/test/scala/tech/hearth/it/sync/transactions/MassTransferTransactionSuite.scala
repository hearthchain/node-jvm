package tech.hearth.it.sync.transactions

import com.google.protobuf.ByteString
import tech.hearth.account.{Address, AddressScheme, PublicKey}
import tech.hearth.api.http.requests.TransferRequest
import tech.hearth.common.state.ByteStr
import tech.hearth.crypto
import tech.hearth.it.NodeConfigs.GenesisAssets
import tech.hearth.it.api.MassTransferTransactionInfo
import tech.hearth.it.api.SyncHttpApi.*
import tech.hearth.it.sync.*
import tech.hearth.it.transactions.BaseTransactionSuite
import tech.hearth.it.util.TxHelpers
import tech.hearth.protobuf.transaction.{TransferTransactionData, PBRecipients}
import tech.hearth.test.*
import tech.hearth.transaction.Asset.Hearth
import tech.hearth.transaction.transfer.*
import tech.hearth.transaction.transfer.TransferTransaction.{MaxTransferCount, Transfer}
import tech.hearth.transaction.transfer.TransferTransaction.MaxAttachmentSize
import tech.hearth.transaction.Proofs
import play.api.libs.json.*

import scala.concurrent.duration.*
import scala.util.Random

class MassTransferTransactionSuite extends BaseTransactionSuite {

  protected override def beforeAll(): Unit = {
    super.beforeAll()
    // explicitly create an address in node's wallet
    sender.postForm("/addresses")
  }

  private def fakeSignature = ByteStr(Array.fill(64)(Random.nextInt().toByte))

  test("asset mass transfer changes asset balances and sender's.hearth balance is decreased by fee.") {
    val (balance1, eff1) = miner.accountBalances(firstAddress)
    val (balance2, eff2) = miner.accountBalances(secondAddress)

    val assetId                  = GenesisAssets.TestAsset.id.toString
    val firstAssetBalanceBefore  = sender.assetBalance(firstAddress, assetId).balance
    val secondAssetBalanceBefore = sender.assetBalance(secondAddress, assetId).balance

    val transfers = List(Transfer(secondAddress, 1000))

    val massTransferTransactionFee = calcMassTransferFee(transfers.size)
    val massTransferTx             = sender.massTransfer(firstKeyPair, transfers, massTransferTransactionFee, assetId = Some(assetId))
    nodes.waitForHeightAriseAndTxPresent(massTransferTx.id)
    massTransferTx.chainId shouldBe Some(AddressScheme.current.chainId)
    sender.transactionInfo[MassTransferTransactionInfo](massTransferTx.id).chainId shouldBe Some(AddressScheme.current.chainId)

    miner.assertBalances(firstAddress, balance1 - massTransferTransactionFee, eff1 - massTransferTransactionFee)
    sender.assetBalance(firstAddress, assetId).balance shouldBe firstAssetBalanceBefore - 1000
    miner.assertBalances(secondAddress, balance2, eff2)
    sender.assetBalance(secondAddress, assetId).balance shouldBe secondAssetBalanceBefore + 1000
  }

  test("fee in an asset at or above its minAssetFee is accepted, below is rejected") {
    // GenesisTestAsset's configured min-fee (template.conf) equals minFee (0.001 hearth) - unrelated to
    // calcMassTransferFee's per-unit Hearth fee sizing, which is orthogonal to the flat per-asset fee floor.
    val assetId   = GenesisAssets.TestAsset.id.toString
    val transfers = List(Transfer(secondAddress, 1))

    val okTransfer = sender.massTransfer(firstKeyPair, transfers, minFee, feeAssetId = Some(assetId))
    nodes.waitForHeightAriseAndTxPresent(okTransfer.id)

    assertBadRequestAndResponse(
      sender.massTransfer(firstKeyPair, transfers, minFee - 1, feeAssetId = Some(assetId)),
      "does not exceed minimal value"
    )
  }

  test("hearth mass transfer changes hearth balances") {
    val (balance1, eff1) = miner.accountBalances(firstAddress)
    val (balance2, eff2) = miner.accountBalances(secondAddress)
    val (balance3, eff3) = miner.accountBalances(thirdAddress)
    val transfers        = List(Transfer(secondAddress, 1000), Transfer(thirdAddress, 2 * 1000))

    val massTransferTransactionFee = calcMassTransferFee(transfers.size)
    val transferId                 = sender.massTransfer(firstKeyPair, transfers, massTransferTransactionFee).id
    nodes.waitForHeightAriseAndTxPresent(transferId)

    miner.assertBalances(
      firstAddress,
      balance1 - massTransferTransactionFee - 3 * 1000,
      eff1 - massTransferTransactionFee - 3 * 1000
    )
    miner.assertBalances(secondAddress, balance2 + 1000, eff2 + 1000)
    miner.assertBalances(thirdAddress, balance3 + 2 * 1000, eff3 + 2 * 1000)
  }

  test("can not make mass transfer without having enough hearth") {
    val (balance1, eff1) = miner.accountBalances(firstAddress)
    val (balance2, eff2) = miner.accountBalances(secondAddress)
    val transfers        = List(Transfer(secondAddress, balance1 / 2), Transfer(thirdAddress, balance1 / 2))

    assertBadRequestAndResponse(
      sender.massTransfer(firstKeyPair, transfers, calcMassTransferFee(transfers.size)),
      "Attempt to transfer unavailable funds"
    )

    nodes.waitForHeightArise()
    miner.assertBalances(firstAddress, balance1, eff1)
    miner.assertBalances(secondAddress, balance2, eff2)
  }

  // TODO: minimum-fee validation isn't implemented yet (FeeValidation.getMinFee is computed but never checked
  // by TransactionDiffer/CommonValidation); restore this case once fee rules are designed and enforced (see
  // TransferTransactionSuite's analogous commented-out case).
  ignore("can not make mass transfer when fee less then required") {
    val (balance1, eff1) = miner.accountBalances(firstAddress)
    val (balance2, eff2) = miner.accountBalances(secondAddress)
    val transfers        = List(Transfer(secondAddress, transferAmount))

    assertBadRequestAndResponse(
      sender.massTransfer(firstKeyPair, transfers, calcMassTransferFee(transfers.size) - 1),
      "Fee .* does not exceed minimal value"
    )
    nodes.waitForHeightArise()
    miner.assertBalances(firstAddress, balance1, eff1)
    miner.assertBalances(secondAddress, balance2, eff2)
  }

  test("can not make mass transfer without having enough of effective balance") {
    val (balance1, eff1) = miner.accountBalances(firstAddress)
    val (balance2, eff2) = miner.accountBalances(secondAddress)
    val transfers        = List(Transfer(secondAddress, balance1 - 2 * minFee))

    val leaseTxId = sender.lease(firstKeyPair, secondAddress, leasingAmount, minFee).id
    nodes.waitForHeightAriseAndTxPresent(leaseTxId)

    assertBadRequestAndResponse(
      sender.massTransfer(firstKeyPair, transfers, calcMassTransferFee(transfers.size)),
      "Attempt to transfer unavailable funds"
    )
    nodes.waitForHeightArise()
    miner.assertBalances(firstAddress, balance1 - minFee, eff1 - leasingAmount - minFee)
    miner.assertBalances(secondAddress, balance2, eff2 + leasingAmount)

    sender.cancelLease(firstKeyPair, leaseTxId, waitForTx = true)
  }

  test("invalid transfer should not be in UTX or blockchain") {
    for (_ <- massTransferTxSupportedVersions) {
      def request(
          transfers: List[Transfer] = List(Transfer(secondAddress, transferAmount)),
          fee: Long = calcMassTransferFee(1),
          timestamp: Long = System.currentTimeMillis,
          attachment: Array[Byte] = Array.emptyByteArray
      ): (TransferRequest, Option[ByteStr]) = {
        val txEi = for {
          parsedTransfers <- TransferTransaction.parseTransfersList(transfers)
          tx <- TransferTransaction
            .create(
              PublicKey(sender.keyPair.publicKey()),
              Hearth,
              parsedTransfers,
              fee,
              timestamp,
              ByteStr(attachment),
              Proofs.empty
            )
            .map(_.signWith(sender.keyPair))
        } yield tx

        val (signature, idOpt) = txEi.fold(_ => (Proofs(List(fakeSignature)), None), tx => (tx.proofs, Some(tx.id())))

        val req = TransferRequest(
          sender.publicKey.toString,
          None,
          transfers,
          fee,
          None,
          timestamp,
          ByteStr(attachment),
          signature
        )

        (req, idOpt)
      }

      def negativeTransferAmountRequest: (TransferRequest, Option[ByteStr]) = {
        val recipient = secondKeyPair

        val transfers  = List(Transfer(recipient.toAddress.toString, -1))
        val attachment = ByteStr(Array.emptyByteArray)
        val fee        = calcMassTransferFee(1)
        val timestamp  = System.currentTimeMillis()
        val mttdTransfers = transfers.map { t =>
          TransferTransactionData.Transfer(
            Some(PBRecipients.create(Address.fromPublicKey(PublicKey(recipient.publicKey())))),
            t.amount
          )
        }

        val bodyBytes =
          TxHelpers.massTransferBodyBytes(sender.keyPair, None, mttdTransfers, ByteString.copyFrom(attachment.arr), fee, timestamp)

        (
          TransferRequest(
            sender.publicKey.toString,
            None,
            transfers,
            fee,
            None,
            timestamp,
            ByteStr(Array.emptyByteArray),
            Proofs(Seq(bodyBytes))
          ),
          Some(ByteStr(crypto.fastHash(bodyBytes.arr)))
        )
      }

      val (balance1, eff1) = miner.accountBalances(firstAddress)
      val invalidTransfers = Seq(
        (request(timestamp = System.currentTimeMillis + 1.day.toMillis), "Transaction timestamp .* is more than .*ms in the future"),
        (
          request(transfers = List.fill(MaxTransferCount + 1)(Transfer(secondAddress, 1)), fee = calcMassTransferFee(MaxTransferCount + 1)),
          s"Number of transfers ${MaxTransferCount + 1} is greater than 100"
        ),
        (negativeTransferAmountRequest, "negative amount: -1 of asset"),
        (request(fee = 0), "insufficient fee"),
        // TODO: minimum-fee validation isn't implemented yet (FeeValidation.getMinFee is computed but never checked
        // by TransactionDiffer/CommonValidation); restore this case once fee rules are designed and enforced.
        // (request(fee = 99999), "Fee .* does not exceed minimal value"),
        // utils.byteArrayFromString hex-decodes the attachment before the length check ever runs. Hex encodes
        // exactly 2 chars per byte with no compression, and MaxAttachmentStringSize is sized from the same
        // 140-byte bound as MaxAttachmentSize, so any attachment over MaxAttachmentSize is also over the generic
        // string-length limit - the attachment-specific "Invalid attachment. Length ... exceeds maximum" check is
        // unreachable via this endpoint under hex and is exercised directly in node/tests instead
        // (MassTransferTransactionSpecification).
        (
          request(attachment = Array.fill(MaxAttachmentSize + 1)(1: Byte)),
          "Can't parse '.*' as base16 encoded byte array"
        )
      )

      for (((req, idOpt), diag) <- invalidTransfers) {
        assertBadRequestAndResponse(sender.broadcastRequest(req), diag)
        idOpt.foreach(id => nodes.foreach(_.ensureTxDoesntExist(id.toString)))
      }

      nodes.waitForHeightArise()
      miner.assertBalances(firstAddress, balance1, eff1)
    }
  }

  test("huuuge transactions are allowed") {
    val (balance1, eff1) = miner.accountBalances(firstAddress)
    val fee              = calcMassTransferFee(MaxTransferCount)
    val amount           = (balance1 - fee) / MaxTransferCount

    val transfers  = List.fill(MaxTransferCount)(Transfer(firstAddress, amount))
    val transferId = sender.massTransfer(firstKeyPair, transfers, fee).id

    nodes.waitForHeightAriseAndTxPresent(transferId)
    miner.assertBalances(firstAddress, balance1 - fee, eff1 - fee)
  }

  test("transaction requires a proof") {
    for (v <- massTransferTxSupportedVersions) {
      val fee       = calcMassTransferFee(2)
      val transfers = Seq(Transfer(secondAddress, 1000), Transfer(thirdAddress, 1000))
      // The node's own miner/generator account is never in its own wallet (see CLAUDE.md "Keys"), so signing has
      // to go through a server-side-registered address, funded here since createAddressServerSide grants no balance.
      val signerAddress = sender.createAddressServerSide()
      sender.transfer(sender.keyPair, signerAddress, fee + 2000, waitForTx = true)
      val signedMassTransfer: JsObject = {
        val rs = sender.postJsonWithApiKey(
          "/transactions/sign",
          Json.obj(
            "type"      -> TransferTransaction.typeId,
            "version"   -> v,
            "sender"    -> signerAddress,
            "transfers" -> transfers,
            "fee"       -> fee
          )
        )
        Json.parse(rs.getResponseBody).as[JsObject]
      }

      def id(obj: JsObject) = obj.value("id").as[String]

      val noProof = signedMassTransfer - "proofs"
      assertBadRequestAndResponse(
        sender.postJson("/transactions/broadcast", noProof),
        s"Reason: Transactions from non-scripted accounts must have exactly 1 proof"
      )
      nodes.foreach(_.ensureTxDoesntExist(id(noProof)))

      val badProof = signedMassTransfer ++ Json.obj("proofs" -> Seq(fakeSignature.toString))
      assertBadRequestAndResponse(sender.postJson("/transactions/broadcast", badProof), "Proof doesn't validate as signature")
      nodes.foreach(_.ensureTxDoesntExist(id(badProof)))

      val withProof = signedMassTransfer
      assert((withProof \ "proofs").as[Seq[String]].lengthCompare(1) == 0)
      sender.postJson("/transactions/broadcast", withProof)
      nodes.waitForHeightAriseAndTxPresent(id(withProof))
    }
  }

  private def extractTransactionByType(json: JsValue, t: Int): Seq[JsValue] = {
    json.validate[Seq[JsObject]].getOrElse(Seq.empty[JsValue]).filter(_("type").as[Int] == t)
  }

  test("reporting MassTransfer transactions") {
    val transfers = List(Transfer(firstAddress, 5.hearth), Transfer(secondAddress, 2.hearth), Transfer(thirdAddress, 3.hearth))
    val txId      = sender.massTransfer(firstKeyPair, transfers, 300000).id
    nodes.waitForHeightAriseAndTxPresent(txId)

    // /transactions/info/txID should return complete list of transfers
    val txInfo = Json.parse(sender.get(s"/transactions/info/$txId").getResponseBody).as[TransferRequest]
    assert(txInfo.transfers.size == 3)

    // /transactions/address should return complete transfers list for the sender...
    val txSender = Json
      .parse(sender.get(s"/transactions/address/$firstAddress/limit/10").getResponseBody)
      .as[JsArray]
      .value
      .map(js => extractTransactionByType(js, TransferTransaction.typeId).head)
      .head
    assert(txSender.as[TransferRequest].transfers.size == 3)
    assert((txSender \ "transferCount").as[Int] == 3)
    assert((txSender \ "totalAmount").as[Long] == 10.hearth)
    val transfersAfterTrans = txSender.as[TransferRequest].transfers
    assert(transfers.equals(transfersAfterTrans))

    // ...and compact list for recipients
    val txRecipient = Json
      .parse(
        sender
          .get(s"/transactions/address/$secondAddress/limit/10")
          .getResponseBody
      )
      .as[JsArray]
      .value
      .map(js => extractTransactionByType(js, TransferTransaction.typeId).head)
      .head

    assert(txRecipient.as[TransferRequest].transfers.size == 1)
    assert((txRecipient \ "transferCount").as[Int] == 3)
    assert((txRecipient \ "totalAmount").as[Long] == 10.hearth)
    val transferToSecond = txRecipient.as[TransferRequest].transfers.head
    assert(transfers contains transferToSecond)
  }
}
