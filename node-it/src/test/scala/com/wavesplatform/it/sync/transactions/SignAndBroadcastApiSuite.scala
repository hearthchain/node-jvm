package com.wavesplatform.it.sync.transactions

import com.typesafe.config.Config
import com.wavesplatform.account.PublicKey
import com.wavesplatform.api.http.ApiError.WrongJson
import com.wavesplatform.api.http.requests.TransferRequest
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.Base58
import com.wavesplatform.common.utils.EitherExt2.*
import com.wavesplatform.crypto
import com.wavesplatform.it.NodeConfigs.GenesisAssets
import com.wavesplatform.it.api.SyncHttpApi.*
import com.wavesplatform.it.sync.*
import com.wavesplatform.it.transactions.BaseTransactionSuite
import com.wavesplatform.it.{NTPTime, NodeConfigs}
import com.wavesplatform.state.Height
import com.wavesplatform.test.*
import com.wavesplatform.transaction.*
import com.wavesplatform.transaction.assets.exchange.*
import com.wavesplatform.transaction.transfer.MassTransferTransaction
import com.wavesplatform.transaction.transfer.MassTransferTransaction.Transfer
import org.asynchttpclient.util.HttpConstants
import org.scalatest
import org.scalatest.BeforeAndAfterAll
import play.api.libs.json.*

class SignAndBroadcastApiSuite extends BaseTransactionSuite with NTPTime with BeforeAndAfterAll {
  import NodeConfigs.*
  override protected def nodeConfigs: Seq[Config] = Seq(BiggestMiner.quorum(0).minAssetInfoUpdateInterval(0))

  test("height should always be reported for transactions") {
    val txId = sender.transfer(firstKeyPair, secondAddress, 1.waves, fee = minFee).id

    sender.waitForTransaction(txId)
    val jsv1               = Json.parse(sender.get(s"/transactions/info/$txId").getResponseBody)
    val hasPositiveHeight1 = (jsv1 \ "height").asOpt[Int].map(_ > 0)
    assert(hasPositiveHeight1.getOrElse(false))

    val response           = sender.get(s"/transactions/address/$firstAddress/limit/1")
    val jsv2               = Json.parse(response.getResponseBody).as[JsArray]
    val hasPositiveHeight2 = (jsv2(0)(0) \ "height").asOpt[Int].map(_ > 0)

    assert(hasPositiveHeight2.getOrElse(false))
  }

  test("/transactions/sign should handle erroneous input") {
    def assertSignBadJson(json: JsObject, expectedMessage: String, code: Int = 400): scalatest.Assertion =
      assertBadRequestAndMessage(sender.postJsonWithApiKey("/transactions/sign", json), expectedMessage, code)

    // /transactions/sign resolves "sender" against the node's own wallet, which the miner's own address (sender.address)
    // is never registered in - the wallet derives its accounts from a separate seed. Use a wallet-backed address so
    // these cases actually reach the request-shape validation they're testing, instead of failing wallet lookup first.
    val walletAddress = sender.createAddressServerSide()

    val json = Json.obj(
      "type"      -> TransactionType.Transfer.id,
      "sender"    -> walletAddress,
      "recipient" -> firstAddress,
      "amount"    -> 1,
      "fee"       -> 100000
    )
    assertSignBadJson(json - "type", WrongJson.WrongJsonDataMessage)
    assertSignBadJson(json + ("type" -> JsNumber(-100)), "Bad transaction type")
    assertSignBadJson(json - "recipient", WrongJson.WrongJsonDataMessage)

    val obsoleteTx =
      Json.obj("type" -> TransactionType.Genesis.id, "sender" -> walletAddress, "recipient" -> firstAddress, "amount" -> 1, "fee" -> 100000)
    assertSignBadJson(obsoleteTx, "transaction type not supported", 501)

    val bigBaseTx =
      Json.obj(
        "type"       -> TransactionType.Transfer.id,
        "sender"     -> walletAddress,
        "recipient"  -> firstAddress,
        "amount"     -> 1,
        "fee"        -> 100000,
        "attachment" -> "W" * 524291
      )
    assertSignBadJson(bigBaseTx, WrongJson.WrongJsonDataMessage)
  }

  test("/transaction/calculateFee should handle coding size limit") {
    val json =
      Json.obj(
        "type"            -> TransactionType.Transfer.id,
        "senderPublicKey" -> sender.publicKey.toString,
        "recipient"       -> secondAddress,
        "fee"             -> 100000,
        "amount"          -> 1,
        "assetId"         -> "W" * 524291
      )
    assertBadRequestAndMessage(sender.calculateFee(json).feeAmount, WrongJson.WrongJsonDataMessage)
  }

  test("/transactions/sign should respect timestamp if specified") {
    val timestamp = 1500000000000L
    val json =
      Json.obj(
        "type"      -> TransactionType.Transfer.id,
        "sender"    -> sender.address,
        "recipient" -> firstAddress,
        "amount"    -> 1,
        "fee"       -> 100000,
        "timestamp" -> timestamp
      )
    val r = sender.postJsonWithApiKey("/transactions/sign", json)
    assert(r.getStatusCode == HttpConstants.ResponseStatusCodes.OK_200)
    assert((Json.parse(r.getResponseBody) \ "timestamp").as[Long] == timestamp)
  }

  test("/transactions/broadcast should handle erroneous input") {
    def assertBroadcastBadJson(json: JsObject, expectedMessage: String): scalatest.Assertion =
      assertBadRequestAndMessage(sender.postJson("/transactions/broadcast", json), expectedMessage)

    val timestamp = System.currentTimeMillis
    val json = Json.obj(
      "type"            -> TransactionType.Transfer.id,
      "senderPublicKey" -> sender.publicKey.toString,
      "recipient"       -> firstAddress,
      "amount"          -> 1,
      "fee"             -> 100000,
      "timestamp"       -> timestamp,
      "proofs"          -> List("A" * 64)
    )

    assertBroadcastBadJson(json, "Proof doesn't validate")
    assertBroadcastBadJson(json - "type", WrongJson.WrongJsonDataMessage)
    assertBroadcastBadJson(json - "type" + ("type" -> Json.toJson(88)), "Bad transaction type")
    assertBroadcastBadJson(json - "recipient", WrongJson.WrongJsonDataMessage)
    // A chainId mismatch case used to be tested here, but TransferRequest has no chainId field of its own - the reader
    // ignores whatever the request JSON carries and always builds the transaction against the server's own network -
    // so a Transfer broadcast can never actually reach CommonValidation.disallowFromAnotherNetwork this way.
  }

  test("/transactions/sign should produce transfer transaction that is good for /transactions/broadcast") {
    signBroadcastAndCalcFee(
      Json.obj(
        "type"       -> TransactionType.Transfer.id,
        "sender"     -> sender.address,
        "recipient"  -> secondAddress,
        "amount"     -> transferAmount,
        "attachment" -> Base58.encode("falafel".getBytes("UTF-8"))
      )
    )
  }

  test("/transactions/sign should produce mass transfer transaction that is good for /transactions/broadcast") {
    signBroadcastAndCalcFee(
      Json.obj(
        "type"       -> MassTransferTransaction.typeId,
        "sender"     -> sender.address,
        "transfers"  -> Json.toJson(Seq(Transfer(secondAddress, 1.waves), Transfer(thirdAddress, 2.waves))),
        "attachment" -> Base58.encode("masspay".getBytes("UTF-8"))
      )
    )
  }

  test("/transactions/sign should produce lease/cancel transactions that are good for /transactions/broadcast") {
    val leaseId =
      signBroadcastAndCalcFee(
        Json.obj("type" -> TransactionType.Lease.id, "sender" -> sender.address, "amount" -> leasingAmount, "recipient" -> secondAddress)
      )

    signBroadcastAndCalcFee(
      Json.obj("type" -> TransactionType.LeaseCancel.id, "sender" -> sender.address, "txId" -> leaseId)
    )
  }

  test("/transactions/sign/{signerAddress} should sign a transaction by key of signerAddress") {
    // Only the public key is embedded in the request JSON below; the actual signing is done server-side by
    // sender's own wallet (see the /transactions/sign/{sender.address} call), so a purely local key pair
    // suffices here (there is no API to recover a key/seed from a server-generated address any more).
    val firstAddress = sender.createKeyPair()

    val json = Json.obj(
      "type"            -> TransactionType.Transfer.id,
      "senderPublicKey" -> PublicKey(firstAddress.publicKey()).toString,
      "recipient"       -> secondAddress,
      "fee"             -> minFee,
      "amount"          -> transferAmount
    )

    val signedRequestResponse = sender.postJsonWithApiKey(s"/transactions/sign/${sender.address}", json)
    assert(signedRequestResponse.getStatusCode == HttpConstants.ResponseStatusCodes.OK_200)
    val signedRequestJson = Json.parse(signedRequestResponse.getResponseBody)
    val signedRequest     = signedRequestJson.as[TransferRequest]
    assert(PublicKey.fromBase58String(signedRequest.senderPublicKey).explicitGet() == PublicKey(firstAddress.publicKey()))
    assert(signedRequest.recipient == secondAddress)
    assert(signedRequest.fee == minFee)
    assert(signedRequest.amount == transferAmount)
    val signature = Base58.tryDecodeWithLimit((signedRequestJson \ "proofs")(0).as[String]).get
    val tx        = signedRequest.toTx.explicitGet()
    val keyPair   = sender.keyPair
    assert(crypto.verify(ByteStr(signature), tx.bodyBytes(), PublicKey(keyPair.publicKey())))
  }

  test("/transactions/broadcast should produce ExchangeTransaction with genesis asset") {
    val assetId = GenesisAssets.TestAsset.id.toString

    val versions = for {
      o1ver <- 1 to 3
      o2ver <- 1 to 3
    } yield (o1ver.toByte, o2ver.toByte)

    for ((o1ver, o2ver) <- versions) {
      val buyer               = sender.keyPair
      val seller              = secondKeyPair
      val matcher             = thirdKeyPair
      val ts                  = ntpTime.correctedTime()
      val expirationTimestamp = ts + Order.MaxLiveTime / 2
      val buyPrice            = 1 * Order.PriceConstant
      val sellPrice           = (0.50 * Order.PriceConstant).toLong
      val mf                  = 300000L
      val buyAmount           = 2
      val sellAmount          = 3
      val assetPair           = AssetPair.createAssetPair("WAVES", assetId).get

      val buy = TxHelpers.order(
        OrderType.BUY,
        assetPair.amountAsset,
        assetPair.priceAsset,
        sender = buyer,
        matcher = matcher,
        amount = buyAmount,
        price = buyPrice,
        fee = mf,
        timestamp = ts,
        expiration = expirationTimestamp,
        version = o1ver
      )
      val sell = TxHelpers.order(
        OrderType.SELL,
        assetPair.amountAsset,
        assetPair.priceAsset,
        sender = seller,
        matcher = matcher,
        amount = sellAmount,
        price = sellPrice,
        fee = mf,
        timestamp = ts,
        expiration = expirationTimestamp,
        version = o2ver
      )

      val amount = math.min(buy.amount.value, sell.amount.value)
      val tx =
        TxHelpers
          .exchange(
            matcher = matcher,
            order1 = buy,
            order2 = sell,
            amount = amount,
            price = sellPrice,
            buyMatcherFee = (BigInt(mf) * amount / buy.amount.value).toLong,
            sellMatcherFee = (BigInt(mf) * amount / sell.amount.value).toLong,
            fee = mf,
            timestamp = ts
          )
          .json()

      val transactionHeight = Height(sender.waitForTransaction(sender.signedBroadcast(tx).id).height)
      sender.waitForHeight(transactionHeight + 1)
      assertBadRequestAndMessage(sender.signedBroadcast(tx), "is already in the state on a height")
    }
  }

  protected override def beforeAll(): Unit = {
    super.beforeAll()
    // explicitly create three more addresses in node's wallet
    sender.postForm("/addresses")
    sender.postForm("/addresses")
    sender.postForm("/addresses")
  }

  private def signBroadcastAndCalcFee(json: JsObject): String = {
    val jsWithPK  = json ++ Json.obj("senderPublicKey" -> sender.publicKey.toString)
    val jsWithFee = jsWithPK ++ Json.obj("fee" -> sender.calculateFee(jsWithPK).feeAmount)
    val rs        = sender.postJsonWithApiKey("/transactions/sign", jsWithFee)
    assert(rs.getStatusCode == HttpConstants.ResponseStatusCodes.OK_200)
    val body   = Json.parse(rs.getResponseBody)
    val proofs = (body \ "proofs").as[Seq[String]]
    assert(proofs.lengthCompare(1) == 0 && proofs.head.nonEmpty)

    val validation = sender.postJson("/debug/validate", body)
    assert(validation.getStatusCode == HttpConstants.ResponseStatusCodes.OK_200)

    val rb = sender.postJson("/transactions/broadcast", body)
    assert(rb.getStatusCode == HttpConstants.ResponseStatusCodes.OK_200)
    val id = (Json.parse(rb.getResponseBody) \ "id").as[String]
    assert(id.nonEmpty)
    sender.waitForTransaction(id)
    id
  }
}
