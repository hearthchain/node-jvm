package com.wavesplatform.http

import com.wavesplatform.account.PublicKey
import com.wavesplatform.api.http.ApiError.*
import com.wavesplatform.api.http.{CustomJson, RouteTimeout, TransactionsApiRoute}
import com.wavesplatform.block.Block
import com.wavesplatform.common.merkle.Merkle
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.Base58
import com.wavesplatform.crypto.bls.BlsKeyPair
import com.wavesplatform.db.WithState.AddrWithBalance
import com.wavesplatform.history.defaultSigner
import com.wavesplatform.protobuf.transaction.PBTransactions
import com.wavesplatform.settings.WavesSettings
import com.wavesplatform.state.Height
import com.wavesplatform.test.*
import com.wavesplatform.transaction.Asset.{IssuedAsset, Waves}
import com.wavesplatform.transaction.TxHelpers.defaultAddress
import com.wavesplatform.transaction.assets.exchange.{Order, OrderType}
import com.wavesplatform.transaction.transfer.TransferTransaction
import com.wavesplatform.transaction.{AssetIdLength, TxHelpers, TxVersion}
import com.wavesplatform.utils.SharedSchedulerMixin
import com.wavesplatform.{BlockGen, TestValues, crypto}
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.model.headers.Accept
import org.scalacheck.Gen.*
import org.scalatest.{Assertion, OptionValues}
import play.api.libs.json.*
import play.api.libs.json.Json.JsValueWrapper

import scala.concurrent.Future
import scala.concurrent.duration.*

class TransactionsRouteSpec
    extends RouteSpec("/transactions")
    with RestAPISettingsHelper
    with BlockGen
    with OptionValues
    with SharedDomain
    with SharedSchedulerMixin {

  private val testTime = new TestTime

  private val richAccount = TxHelpers.signer(10001)
  private val richAddress = richAccount.toAddress

  override def settings: WavesSettings = DomainPresets.DeterministicFinality.copy(
    restAPISettings = restAPISettings.copy(transactionsByAddressLimit = 5)
  )

  override def genesisBalances: Seq[AddrWithBalance] = Seq(
    AddrWithBalance(richAddress, 1_000_000.waves),
    AddrWithBalance(defaultAddress, 1_000_000.waves)
  )

  private val transactionsApiRoute = new TransactionsApiRoute(
    settings.restAPISettings,
    domain.transactionsApi,
    domain.wallet,
    domain.blockchain,
    () => domain.blockchain,
    () => domain.utxPool.size,
    (tx, _) => Future.successful(domain.utxPool.putIfNew(tx, forceValidate = true)),
    testTime,
    new RouteTimeout(60.seconds)(using sharedScheduler)
  )

  private val route = seal(transactionsApiRoute.route)

  private val invalidBase58Gen = alphaNumStr.map(_ + "0")

  routePath("/calculateFee") - {
    "waves" in {
      val transferTx = Json.obj(
        "type"            -> 4,
        "version"         -> 1,
        "amount"          -> 1000000,
        "feeAssetId"      -> JsNull,
        "senderPublicKey" -> PublicKey(TestValues.keyPair.publicKey),
        "recipient"       -> TestValues.address
      )

      Post(routePath("/calculateFee"), transferTx) ~> route ~> check {
        status shouldEqual StatusCodes.OK
        (responseAs[JsObject] \ "feeAssetId").asOpt[String] shouldBe empty
        (responseAs[JsObject] \ "feeAmount").as[Long] shouldEqual 100000
      }
    }
  }

  "returns lease details for lease cancel transaction" in {
    val sender    = TxHelpers.signer(20)
    val recipient = TxHelpers.signer(21)

    val lease       = TxHelpers.lease(sender, recipient.toAddress, 5.waves)
    val leaseCancel = TxHelpers.leaseCancel(lease.id(), sender)

    domain.appendBlock(
      TxHelpers.transfer(richAccount, sender.toAddress, 6.waves),
      lease
    )

    val leaseHeight = domain.blockchain.height

    def expectedJson(status: String, height: Int, cancelHeight: Option[Int] = None, cancelTransactionId: Option[ByteStr] = None): JsObject =
      Json
        .parse(s"""{
                  |  "applicationStatus": "succeeded",
                  |  "type" : 9,
                  |  "id" : "${leaseCancel.id()}",
                  |  "sender" : "${sender.toAddress}",
                  |  "senderPublicKey" : "${sender.publicKey}",
                  |  "fee" : ${0.001.waves},
                  |  "feeAssetId" : null,
                  |  "timestamp" : ${leaseCancel.timestamp},
                  |  "proofs" : [ "${leaseCancel.signature}" ],
                  |  "version" : 2,
                  |  "leaseId" : "${lease.id()}",
                  |  "chainId" : 84,
                  |  "spentComplexity" : 0,
                  |  "lease" : {
                  |    "id" : "${lease.id()}",
                  |    "originTransactionId" : "${lease.id()}",
                  |    "sender" : "${sender.toAddress}",
                  |    "recipient" : "${recipient.toAddress}",
                  |    "amount" : ${5.waves},
                  |    "height" : $height,
                  |    "status" : "$status",
                  |    "cancelHeight" : ${cancelHeight.getOrElse("null")},
                  |    "cancelTransactionId" : ${cancelTransactionId.fold("null")("\"" + _ + "\"")}
                  |  }
                  |}""".stripMargin)
        .as[JsObject]

    domain.utxPool.putIfNew(leaseCancel)

    withClue(routePath("/unconfirmed")) {
      Get(routePath(s"/unconfirmed")) ~> route ~> check {
        responseAs[Seq[JsObject]].head should matchJson(expectedJson("active", leaseHeight) - "spentComplexity" - "applicationStatus")
      }
    }

    domain.appendBlock(leaseCancel)

    val cancelHeight = domain.blockchain.height
    val cancelTransactionJson =
      expectedJson("canceled", leaseHeight, Some(cancelHeight), Some(leaseCancel.id())) ++ Json.obj("height" -> cancelHeight)

    withClue(routePath("/address/{address}/limit/{limit}")) {
      Get(routePath(s"/address/${recipient.toAddress}/limit/5")) ~> route ~> check {
        val json = (responseAs[JsArray] \ 0 \ 0).as[JsObject]
        json should matchJson(cancelTransactionJson)
      }
    }

    withClue(routePath("/info/{id}")) {
      Get(routePath(s"/info/${leaseCancel.id()}")) ~> route ~> check {
        responseAs[JsObject] should matchJson(cancelTransactionJson)
      }
    }
  }

  routePath("/address/{address}/limit/{limit}") - {
    val txByAddressLimit = settings.restAPISettings.transactionsByAddressLimit
    "handles parameter errors with corresponding responses" - {
      "invalid address bytes" in {
        Get(routePath(s"/address/${Base58.encode(new Array[Byte](24))}/limit/1")) ~> route should produce(InvalidAddress)
      }

      "invalid base58 encoding" in {
        Get(routePath(s"/address/${"1" * 23 + "0"}/limit/1")) ~> route should produce(
          CustomValidationError("requirement failed: Wrong char '0' in Base58 string '111111111111111111111110'")
        )
      }

      "invalid limit" - {
        "limit is too big" in {
          Get(
            routePath(s"/address/$richAddress/limit/${txByAddressLimit + 1}")
          ) ~> route should produce(TooBigArrayAllocation)
        }
      }

      "invalid after" in {
        val invalidBase58String = "1" * 23 + "0"
        Get(routePath(s"/address/$richAddress/limit/$txByAddressLimit?after=$invalidBase58String")) ~> route ~> check {
          status shouldEqual StatusCodes.BadRequest
          (responseAs[JsObject] \ "message").as[String] shouldEqual s"Unable to decode transaction id $invalidBase58String"
        }
      }
    }

    "returns 200 if correct params provided" - {
      "address and limit" in {
        Get(routePath(s"/address/$richAddress/limit/$txByAddressLimit")) ~> route ~> check {
          status shouldEqual StatusCodes.OK
        }
      }

      "address, limit and after" in {
        Get(routePath(s"/address/$richAddress/limit/$txByAddressLimit?after=${ByteStr(new Array[Byte](32))}")) ~> route ~> check {
          status shouldEqual StatusCodes.OK
        }
      }
    }

    "large-significand-format" in {
      val transferTxn           = TxHelpers.transfer(richAccount, TxHelpers.address(930), 10.waves)
      val commitToGenerationTxn = TxHelpers.commitToGeneration(Height(3001), richAccount)
      domain.appendBlock(transferTxn, commitToGenerationTxn)

      Get(routePath(s"/info/${transferTxn.id()}")) ~> Accept(CustomJson.jsonWithNumbersAsStrings) ~> route ~> check {
        val result = responseAs[JsObject]
        (result \ "amount").as[String] shouldBe transferTxn.amount.value.toString
        (result \ "fee").as[String] shouldBe transferTxn.fee.value.toString

        (result \ "height").as[Int] shouldBe domain.blockchain.height
        (result \ "spentComplexity").as[Int] shouldBe 0
        (result \ "type").as[Int] shouldBe transferTxn.tpe.id
        (result \ "timestamp").as[Long] shouldBe transferTxn.timestamp
      }

      Get(routePath(s"/info/${commitToGenerationTxn.id()}")) ~> Accept(CustomJson.jsonWithNumbersAsStrings) ~> route ~> check {
        val result = responseAs[JsObject]
        (result \ "fee").as[String] shouldBe commitToGenerationTxn.fee.value.toString

        (result \ "timestamp").as[Long] shouldBe commitToGenerationTxn.timestamp
      }
    }

  }

  routePath("/info/{id}") - {
    "returns lease tx for lease cancel tx" in {
      val lessor         = TxHelpers.signer(250)
      val leaseRecipient = TxHelpers.address(251)

      val lease = TxHelpers.lease(lessor, leaseRecipient, 22.waves)

      domain.appendBlock(
        TxHelpers.transfer(richAccount, lessor.toAddress, 25.waves),
        lease
      )

      val leaseHeight = domain.blockchain.height

      val leaseCancel = TxHelpers.leaseCancel(lease.id(), lessor)
      domain.appendBlock(leaseCancel)
      val cancelHeight = domain.blockchain.height

      Get(routePath(s"/info/${leaseCancel.id()}")) ~> route ~> check {
        val json = responseAs[JsObject]
        json shouldBe Json.parse(s"""{
                                    |  "type" : 9,
                                    |  "id" : "${leaseCancel.id()}",
                                    |  "sender" : "${lessor.toAddress}",
                                    |  "senderPublicKey" : "${lessor.publicKey}",
                                    |  "fee" : 100000,
                                    |  "feeAssetId" : null,
                                    |  "timestamp" : ${leaseCancel.timestamp},
                                    |  "proofs" : [ "${leaseCancel.signature}" ],
                                    |  "version" : 2,
                                    |  "leaseId" : "${lease.id()}",
                                    |  "chainId" : 84,
                                    |  "height" : $cancelHeight,
                                    |  "applicationStatus" : "succeeded",
                                    |  "spentComplexity": 0,
                                    |  "lease" : {
                                    |    "id" : "${lease.id()}",
                                    |    "originTransactionId" : "${lease.id()}",
                                    |    "sender" : "${lessor.toAddress}",
                                    |    "recipient" : "$leaseRecipient",
                                    |    "amount" : ${22.waves},
                                    |    "height" : $leaseHeight,
                                    |    "status" : "canceled",
                                    |    "cancelHeight" : $cancelHeight,
                                    |    "cancelTransactionId" : "${leaseCancel.id()}"
                                    |  }
                                    |}""".stripMargin)
      }
    }

    "handles invalid signature" in {
      forAll(invalidBase58Gen) { invalidBase58 =>
        Get(routePath(s"/info/$invalidBase58")) ~> route should produce(InvalidTransactionId("Wrong char"), matchMsg = true)
      }

      Get(routePath(s"/info/")) ~> route should produce(InvalidTransactionId("Transaction ID was not specified"))
      Get(routePath(s"/info")) ~> route should produce(InvalidTransactionId("Transaction ID was not specified"))
    }

  }

  routePath("/status/{signature}") - {
    "handles invalid signature" in {
      forAll(invalidBase58Gen) { invalidBase58 =>
        Get(routePath(s"/status?id=$invalidBase58")) ~> route should produce(InvalidIds(Seq(invalidBase58)))
      }
    }

    "handles empty request" in {
      Get(routePath(s"/status?")) ~> route should produce(CustomValidationError("Empty request"))
    }

    "working properly otherwise" in {
      val sender = TxHelpers.signer(1195)
      val tx1    = TxHelpers.transfer(richAccount, sender.toAddress, 5.waves)
      val tx2    = TxHelpers.transfer(sender, richAddress, 1.waves)

      domain.appendBlock(tx1, tx2)

      Get(routePath(s"/status?id=${tx1.id().toString}&id=${tx2.id().toString}")) ~> route ~> check {
        status shouldEqual StatusCodes.OK
        val resp = responseAs[JsArray].value
        (resp.head \ "id").as[String] shouldEqual tx1.id().toString
        (resp(1) \ "id").as[String] shouldEqual tx2.id().toString
        resp.foreach(j => (j \ "status").as[String] shouldEqual "confirmed")
      }
    }
  }

  routePath("/unconfirmed") - {
    "returns the list of unconfirmed transactions" in {
      domain.utxPool.removeAll(domain.utxPool.all)
      val txs = Seq.tabulate(20)(a => TxHelpers.transfer(richAccount, amount = (a + 1).waves))
      txs.foreach(t => domain.utxPool.putIfNew(t))
      Get(routePath("/unconfirmed")) ~> route ~> check {
        val txIds = responseAs[Seq[JsValue]].map(v => (v \ "id").as[String])
        txIds should contain allElementsOf (txs.map(_.id().toString))
      }
      domain.utxPool.removeAll(txs)
    }

    routePath("/unconfirmed/size") - {
      "returns the size of unconfirmed transactions" in {
        domain.utxPool.removeAll(domain.utxPool.all)
        val txs = Seq.tabulate(20)(a => TxHelpers.transfer(richAccount, amount = (a + 1).waves))
        txs.foreach(t => domain.utxPool.putIfNew(t))
        Get(routePath("/unconfirmed/size")) ~> route ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[JsValue] shouldEqual Json.obj("size" -> JsNumber(txs.size))
        }
        domain.utxPool.removeAll(txs)
      }
    }

    routePath("/unconfirmed/info/{id}") - {
      "handles invalid signature" in {
        forAll(invalidBase58Gen) { invalidBase58 =>
          Get(routePath(s"/unconfirmed/info/$invalidBase58")) ~> route should produce(InvalidTransactionId("Wrong char"), matchMsg = true)
        }

        Get(routePath(s"/unconfirmed/info/")) ~> route should produce(InvalidSignature)
        Get(routePath(s"/unconfirmed/info")) ~> route should produce(InvalidSignature)
      }

      "working properly otherwise" in {
        val tx = TxHelpers.transfer(richAccount, defaultAddress, 20.waves)
        domain.utxPool.putIfNew(tx)
        Get(routePath(s"/unconfirmed/info/${tx.id().toString}")) ~> route ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[JsValue] shouldEqual tx.json()
        }
        domain.utxPool.removeAll(Seq(tx))
      }
    }
  }

  routePath("/sign") - {
    "CommitToGenerationTransaction" in {
      val sender = domain.wallet.generateNewAccount().get
      val blsKP  = BlsKeyPair(???)
      val unsignedTxnJson = Json.parse(
        s"""{
           |  "type": 19,
           |  "sender": "${sender.toAddress}"
           |}""".stripMargin
      )

      Post(routePath("/sign"), unsignedTxnJson) ~> ApiKeyHeader ~> route ~> check {
        val jsObject = responseAs[JsObject]
        withClue(s"$jsObject ") {
          status shouldEqual StatusCodes.OK
        }
        (jsObject \ "generationPeriodStart").as[Int] shouldBe 3001
        (jsObject \ "senderPublicKey").as[String] shouldBe sender.publicKey.toString
        (jsObject \ "endorserPublicKey").as[String] shouldBe blsKP.publicKey.base58
        (jsObject \ "commitmentSignature").asOpt[String] shouldBe defined
      }
    }
  }

  routePath("/broadcast") - {
    "checks the length of base58 attachment in symbols" in {
      val attachmentSizeInSymbols = TransferTransaction.MaxAttachmentStringSize + 1
      val attachmentStr           = "1" * attachmentSizeInSymbols

      val tx = TxHelpers
        .transfer()
        .copy(attachment = ByteStr(Base58.decode(attachmentStr))) // to bypass a validation
        .signWith(defaultSigner)

      Post(routePath("/broadcast"), tx.json()) ~> route should produce(
        WrongJson(
          errors = Seq(
            JsPath \ "attachment" -> Seq(
              JsonValidationError(s"base58-encoded string length ($attachmentSizeInSymbols) exceeds maximum length of 192")
            )
          ),
          msg = Some("json data validation error, see validationErrors for details")
        )
      )
    }

    "checks the length of base58 attachment in bytes" in {
      val attachmentSizeInSymbols = TransferTransaction.MaxAttachmentSize + 1
      val attachmentStr           = "1" * attachmentSizeInSymbols
      val attachment              = ByteStr(Base58.decode(attachmentStr))

      val tx = TxHelpers
        .transfer()
        .copy(attachment = attachment)
        .signWith(defaultSigner)

      Post(routePath("/broadcast"), tx.json()) ~> route should produce(
        TooBigInBytes(
          s"Invalid attachment. Length ${attachment.size} bytes exceeds maximum of ${TransferTransaction.MaxAttachmentSize} bytes."
        )
      )
    }

    "CommitToGeneration transaction" in {
      val txn = TxHelpers.commitToGeneration(Height(settings.blockchainSettings.functionalitySettings.generationPeriodLength + 1))
      Post(routePath("/broadcast"), txn.json()) ~> route ~> check {
        val jsObject = responseAs[JsObject]
        withClue(s"$jsObject ") {
          status shouldEqual StatusCodes.OK
        }
      }
    }
  }

  routePath("/merkleProof") - {
    def validateSuccess(blockRoot: ByteStr, expected: Seq[(ByteStr, Array[Byte], Int)], response: HttpResponse): Unit = {
      response.status shouldBe StatusCodes.OK

      val proofs = responseAs[List[JsObject]]

      proofs.size shouldBe expected.size

      proofs.zip(expected).foreach { case (p, (id, hash, index)) =>
        val transactionId    = (p \ "id").as[String]
        val transactionIndex = (p \ "transactionIndex").as[Int]
        val digests          = (p \ "merkleProof").as[List[String]].map(s => Base58.decode(s))

        transactionId shouldEqual id.toString
        transactionIndex shouldEqual index

        assert(Merkle.verify(hash, transactionIndex, digests.reverse, blockRoot.arr))

      }
    }

    def validateFailure(response: HttpResponse): Unit = {
      response.status shouldEqual StatusCodes.BadRequest
      (responseAs[JsObject] \ "message").as[String] shouldEqual s"transactions do not exist"
    }

    "returns merkle proofs" in {
      val sender = TxHelpers.signer(1390)

      val tx1 = TxHelpers.transfer(richAccount, sender.toAddress, 10.waves)
      val tx2 = TxHelpers.transfer(sender, richAddress, 1.waves)

      domain.appendBlock(tx1, tx2)

      val transactions = Seq(tx1, tx2)
      val proofs = Seq(
        (tx1.id(), crypto.fastHash(PBTransactions.toByteArrayMerkle(tx1)), 2),
        (tx2.id(), crypto.fastHash(PBTransactions.toByteArrayMerkle(tx2)), 3)
      )

      val queryParams = transactions.map(t => s"id=${t.id()}").mkString("?", "&", "")
      val requestBody = Json.obj("ids" -> transactions.map(_.id().toString))

      Get(routePath(s"/merkleProof$queryParams")) ~> route ~> check {
        validateSuccess(domain.blockchain.lastBlockHeader.value.header.transactionsRoot, proofs, response)
      }

      Post(routePath("/merkleProof"), requestBody) ~> route ~> check {
        validateSuccess(domain.blockchain.lastBlockHeader.value.header.transactionsRoot, proofs, response)
      }
    }

    "returns error in case of all transactions are filtered" in {
      val genesisTransactions = domain.blocksApi.blockAtHeight(Height(1)).value._2.collect { case (_, tx) => tx.id() }

      val queryParams = genesisTransactions.map(id => s"id=$id").mkString("?", "&", "")
      val requestBody = Json.obj("ids" -> genesisTransactions)

      Get(routePath(s"/merkleProof$queryParams")) ~> route ~> check {
        validateFailure(response)
      }

      Post(routePath("/merkleProof"), requestBody) ~> route ~> check {
        validateFailure(response)
      }
    }

    "handles invalid ids" in {
      val invalidIds = Seq(
        ByteStr.fill(AssetIdLength)(1),
        ByteStr.fill(AssetIdLength)(2)
      ).map(bs => s"${bs}0")

      Get(routePath(s"/merkleProof?${invalidIds.map("id=" + _).mkString("&")}")) ~> route should produce(InvalidIds(invalidIds))

      Post(routePath("/merkleProof"), FormData(invalidIds.map("id" -> _)*)) ~> route should produce(InvalidIds(invalidIds))

      Post(routePath("/merkleProof"), Json.obj("ids" -> invalidIds)) ~> route should produce(InvalidIds(invalidIds))
    }

    "handles transactions ids limit" in {
      val inputLimitErrMsg = TooBigArrayAllocation(transactionsApiRoute.settings.transactionsByAddressLimit).message
      val emptyInputErrMsg = "Transaction ID was not specified"

      def checkErrorResponse(errMsg: String): Unit = {
        response.status shouldBe StatusCodes.BadRequest
        (responseAs[JsObject] \ "message").as[String] shouldBe errMsg
      }

      def checkResponse(tx: TransferTransaction, idsCount: Int): Unit = {
        response.status shouldBe StatusCodes.OK

        val result = responseAs[JsArray].value
        result.size shouldBe idsCount
        (1 to idsCount).zip(responseAs[JsArray].value) foreach { case (_, json) =>
          (json \ "id").as[String] shouldBe tx.id().toString
          (json \ "transactionIndex").as[Int] shouldBe 1
        }
      }

      val sender = TxHelpers.signer(1090)

      val transferTx = TxHelpers.transfer(from = sender)
      domain.appendBlock(TxHelpers.transfer(richAccount, sender.toAddress, 100.waves), transferTx)

      val maxLimitIds      = Seq.fill(transactionsApiRoute.settings.transactionsByAddressLimit)(transferTx.id().toString)
      val moreThanLimitIds = transferTx.id().toString +: maxLimitIds

      Get(routePath(s"/merkleProof?${maxLimitIds.map("id=" + _).mkString("&")}")) ~> route ~> check(checkResponse(transferTx, maxLimitIds.size))
      Get(routePath(s"/merkleProof?${moreThanLimitIds.map("id=" + _).mkString("&")}")) ~> route ~> check(checkErrorResponse(inputLimitErrMsg))
      Get(routePath("/merkleProof")) ~> route ~> check(checkErrorResponse(emptyInputErrMsg))

      Post(routePath("/merkleProof"), FormData(maxLimitIds.map("id" -> _)*)) ~> route ~> check(checkResponse(transferTx, maxLimitIds.size))
      Post(routePath("/merkleProof"), FormData(moreThanLimitIds.map("id" -> _)*)) ~> route ~> check(checkErrorResponse(inputLimitErrMsg))
      Post(routePath("/merkleProof"), FormData()) ~> route ~> check(checkErrorResponse(emptyInputErrMsg))

      Post(
        routePath(s"/merkleProof"),
        HttpEntity(ContentTypes.`application/json`, Json.obj("ids" -> Json.arr(maxLimitIds.map(id => id: JsValueWrapper)*)).toString())
      ) ~> route ~> check(checkResponse(transferTx, maxLimitIds.size))
      Post(
        routePath(s"/merkleProof"),
        HttpEntity(ContentTypes.`application/json`, Json.obj("ids" -> Json.arr(moreThanLimitIds.map(id => id: JsValueWrapper)*)).toString())
      ) ~> route ~> check(checkErrorResponse(inputLimitErrMsg))
      Post(
        routePath("/merkleProof"),
        HttpEntity(ContentTypes.`application/json`, Json.obj("ids" -> JsArray.empty).toString())
      ) ~> route ~> check(checkErrorResponse(emptyInputErrMsg))
    }
  }

  "NODE-969. Transactions API should return correct data for orders with attachment" in {
    def checkOrderAttachment(txInfo: JsObject, expectedAttachment: ByteStr): Assertion = {
      implicit val byteStrFormat: Format[ByteStr] = com.wavesplatform.utils.byteStrFormat
      (txInfo \ "order1" \ "attachment").asOpt[ByteStr] shouldBe Some(expectedAttachment)
    }

    val sender     = TxHelpers.signer(1100)
    val issuer     = TxHelpers.signer(1101)
    val attachment = ByteStr.fill(32)(1)
    val issuedAsset = IssuedAsset(ByteStr(new Array[Byte](32)))
    val exchange =
      TxHelpers.exchangeFromOrders(
        TxHelpers.order(OrderType.BUY, Waves, issuedAsset, version = Order.V4, attachment = Some(attachment)),
        TxHelpers.order(OrderType.SELL, Waves, issuedAsset, version = Order.V4, sender = issuer),
      )

    domain.appendBlock(
      TxHelpers.massTransfer(richAccount, Seq(sender.toAddress -> 10.waves, issuer.toAddress -> 10.waves), fee = 0.002.waves),
      exchange
    )

    domain.liquidAndSolidAssert { () =>
      Get(s"/transactions/info/${exchange.id()}") ~> route ~> check {
        checkOrderAttachment(responseAs[JsObject], attachment)
      }

      Post("/transactions/info", FormData("id" -> exchange.id().toString)) ~> route ~> check {
        checkOrderAttachment(responseAs[JsArray].value.head.as[JsObject], attachment)
      }

      Post(
        "/transactions/info",
        HttpEntity(ContentTypes.`application/json`, Json.obj("ids" -> Json.arr(exchange.id().toString)).toString())
      ) ~> route ~> check {
        checkOrderAttachment(responseAs[JsArray].value.head.as[JsObject], attachment)
      }

      Get(s"/transactions/address/${exchange.sender.toAddress}/limit/5") ~> route ~> check {
        checkOrderAttachment(responseAs[JsArray].value.head.as[JsArray].value.head.as[JsObject], attachment)
      }
    }
  }
}
