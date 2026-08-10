package tech.hearth.http

import tech.hearth.account.PublicKey
import tech.hearth.api.http.ApiError.*
import tech.hearth.api.http.{CustomJson, RouteTimeout, TransactionsApiRoute}
import tech.hearth.common.merkle.Merkle
import tech.hearth.common.state.ByteStr
import tech.hearth.common.utils.Base16
import tech.hearth.db.WithState.AddrWithBalance
import tech.hearth.history.defaultSigner
import tech.hearth.protobuf.transaction.PBTransactions
import tech.hearth.settings.{GenesisAssetSettings, WavesSettings}
import tech.hearth.test.DomainPresets.withGenesisAssets
import tech.hearth.state.Height
import tech.hearth.test.*
import tech.hearth.transaction.Asset.{IssuedAsset, Waves}
import tech.hearth.transaction.TransactionType
import tech.hearth.transaction.TxHelpers.defaultAddress
import tech.hearth.transaction.assets.exchange.{Order, OrderType}
import tech.hearth.transaction.transfer.TransferTransaction
import tech.hearth.transaction.{AssetIdLength, TxHelpers}
import tech.hearth.utils.SharedSchedulerMixin
import tech.hearth.{BlockGen, TestValues, crypto}
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

  // Nothing issues an asset any more, so one a test trades has to be declared in the genesis snapshot, and the genesis
  // balances have to hold exactly the quantity declared - here, all of it on the account that pays with it below.
  private val tradedAssetIssuer   = TxHelpers.signer(1101)
  private val tradedAsset         = IssuedAsset(ByteStr(new Array[Byte](AssetIdLength)))
  private val tradedAssetQuantity = 100_000_000L

  override def settings: WavesSettings = {
    val base = DomainPresets.DeterministicFinality.copy(restAPISettings = restAPISettings.copy(transactionsByAddressLimit = 5))
    // signer(500) is one of this node's generators, so that a CommitToGeneration can be signed for it
    base
      .copy(minerSettings = base.minerSettings.copy(accounts = Seq(TxHelpers.miningAccountSettings(500))))
      .withGenesisAssets(
        GenesisAssetSettings(
          id = tradedAsset.id,
          name = "test",
          decimals = 8,
          quantity = tradedAssetQuantity,
          minFee = TestValues.fee
        )
      )
  }

  override def genesisBalances: Seq[AddrWithBalance] = Seq(
    AddrWithBalance(richAddress, 1_000_000.waves),
    AddrWithBalance(defaultAddress, 1_000_000.waves, assets = Map(tradedAsset -> tradedAssetQuantity))
  )

  private val transactionsApiRoute = new TransactionsApiRoute(
    settings.restAPISettings,
    domain.transactionsApi,
    domain.wallet,
    domain.generatorKeys,
    domain.blockchain,
    () => domain.blockchain,
    () => domain.utxPool.size,
    (tx, _) => Future.successful(domain.utxPool.putIfNew(tx, forceValidate = true)),
    testTime,
    new RouteTimeout(60.seconds)(using sharedScheduler)
  )

  private val route = seal(transactionsApiRoute.route)

  // Guaranteed even length (HexFormat checks parity before individual characters) and a trailing 'z', which is
  // never a valid hex digit, so this always fails to decode with "not a hexadecimal digit", not "string length not even".
  private val invalidHexGen = choose(0, 10).map(n => "1" * (n * 2) + "zz")

  routePath("/calculateFee") - {
    "waves" in {
      val transferTx = Json.obj(
        "type"            -> 4,
        "version"         -> 1,
        "amount"          -> 1000000,
        "feeAssetId"      -> JsNull,
        "senderPublicKey" -> PublicKey(TestValues.keyPair.publicKey),
        // Not TestValues.address: that is this very sender's, and a transfer to yourself is rejected before any fee
        // is calculated
        "recipient" -> TxHelpers.secondAddress
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
                  |  "type" : ${TransactionType.LeaseCancel.id},
                  |  "id" : "${leaseCancel.id()}",
                  |  "sender" : "${sender.toAddress}",
                  |  "senderPublicKey" : "${PublicKey(sender.publicKey())}",
                  |  "fee" : ${0.001.waves},
                  |  "feeAssetId" : null,
                  |  "timestamp" : ${leaseCancel.timestamp},
                  |  "proofs" : [ "${leaseCancel.signature}" ],
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
        Get(routePath(s"/address/${Base16.encode(new Array[Byte](24))}/limit/1")) ~> route should produce(InvalidAddress)
      }

      // Addresses are bech32 now, so nothing base58-decodes them and a malformed one is simply not an address
      "invalid encoding" in {
        Get(routePath(s"/address/${"1" * 23 + "0"}/limit/1")) ~> route should produce(InvalidAddress)
      }

      "invalid limit" - {
        "limit is too big" in {
          Get(
            routePath(s"/address/$richAddress/limit/${txByAddressLimit + 1}")
          ) ~> route should produce(TooBigArrayAllocation)
        }
      }

      "invalid after" in {
        val invalidHexString = "1" * 23 + "z"
        Get(routePath(s"/address/$richAddress/limit/$txByAddressLimit?after=$invalidHexString")) ~> route ~> check {
          status shouldEqual StatusCodes.BadRequest
          (responseAs[JsObject] \ "message").as[String] shouldEqual s"Unable to decode transaction id $invalidHexString"
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
                                    |  "type" : ${TransactionType.LeaseCancel.id},
                                    |  "id" : "${leaseCancel.id()}",
                                    |  "sender" : "${lessor.toAddress}",
                                    |  "senderPublicKey" : "${PublicKey(lessor.publicKey())}",
                                    |  "fee" : 100000,
                                    |  "feeAssetId" : null,
                                    |  "timestamp" : ${leaseCancel.timestamp},
                                    |  "proofs" : [ "${leaseCancel.signature}" ],
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
      forAll(invalidHexGen) { invalidHex =>
        Get(routePath(s"/info/$invalidHex")) ~> route should produce(InvalidTransactionId("not a hexadecimal digit"), matchMsg = true)
      }

      Get(routePath(s"/info/")) ~> route should produce(InvalidTransactionId("Transaction ID was not specified"))
      Get(routePath(s"/info")) ~> route should produce(InvalidTransactionId("Transaction ID was not specified"))
    }

  }

  routePath("/status/{signature}") - {
    "handles invalid signature" in {
      forAll(invalidHexGen) { invalidHex =>
        Get(routePath(s"/status?id=$invalidHex")) ~> route should produce(InvalidIds(Seq(invalidHex)))
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
        forAll(invalidHexGen) { invalidHex =>
          Get(routePath(s"/unconfirmed/info/$invalidHex")) ~> route should produce(InvalidTransactionId("not a hexadecimal digit"), matchMsg = true)
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
      // A CommitToGeneration registers the account's own generator keys, so it is signed with the mining account -
      // the wallet holds neither those keys nor, necessarily, that account
      val sender = TxHelpers.signer(500)
      val blsKP  = TxHelpers.blsKeyOf(sender)
      val unsignedTxnJson = Json.parse(
        s"""{
           |  "type": ${TransactionType.CommitToGeneration.id},
           |  "sender": "${sender.toAddress}"
           |}""".stripMargin
      )

      Post(routePath("/sign"), unsignedTxnJson) ~> ApiKeyHeader ~> route ~> check {
        val jsObject = responseAs[JsObject]
        withClue(s"$jsObject ") {
          status shouldEqual StatusCodes.OK
        }
        (jsObject \ "generationPeriodStart").as[Int] shouldBe 3001
        (jsObject \ "senderPublicKey").as[String] shouldBe PublicKey(sender.publicKey()).toString
        (jsObject \ "endorserPublicKey").as[String] shouldBe blsKP.publicKey.base16
        (jsObject \ "commitmentSignature").asOpt[String] shouldBe defined
      }
    }
  }

  routePath("/broadcast") - {
    "checks the length of hex attachment in symbols" in {
      // Hex encodes exactly 2 chars per byte, so one byte past the string-length limit is enough to exceed it.
      val attachmentBytes = Array.fill(TransferTransaction.MaxAttachmentStringSize / 2 + 1)(1: Byte)
      val attachmentStr   = Base16.encode(attachmentBytes)

      val tx = TxHelpers
        .transfer()
        .copy(attachment = ByteStr(attachmentBytes)) // to bypass a validation
        .signWith(defaultSigner)

      // Too long to hex-decode at all: the reader reports that rather than measuring the string, see
      // `utils.byteArrayFromString`
      Post(routePath("/broadcast"), tx.json()) ~> route should produce(
        WrongJson(
          errors = Seq(
            JsPath \ "attachment" -> Seq(JsonValidationError(s"Can't parse '$attachmentStr' as base16 encoded byte array"))
          ),
          msg = Some("json data validation error, see validationErrors for details")
        )
      )
    }

    // Base58 could encode an all-zero-byte attachment more compactly than one char per byte (leading zero bytes
    // collapse to leading '1's), so a byte array one byte over MaxAttachmentSize could still round-trip through a
    // string under the generic decode-length limit, reaching this endpoint's TooBigInBytes check. Hex encodes
    // exactly two chars per byte with no such compression, so a byte array over MaxAttachmentSize is always over
    // the generic string-length limit too (they're sized from the same 140-byte bound, see Base16) and is now
    // rejected by the JSON reader before ever reaching this check - this scenario is unreachable via this endpoint.
    // The check itself is still exercised directly in MassTransferTransactionSpecification.
    "checks the length of hex attachment in bytes" ignore {}

    "CommitToGeneration transaction" in {
      val txn = TxHelpers.commitToGeneration(Height(settings.blockchainSettings.functionalitySettings.generationPeriodLength + 1))
      Post(routePath("/broadcast"), txn.json()) ~> route ~> check {
        val jsObject = responseAs[JsObject]
        withClue(s"$jsObject ") {
          status shouldEqual StatusCodes.OK
        }
      }
    }

    // A client that round-trips a transaction through its own model can end up with a literal "version": null
    // (the key present, but not a usable value) rather than the key being absent - nothing writes a version any
    // more, and a plain Option[Byte] writer renders None as null. TransactionFactory.parseRequest has to treat that
    // the same as an absent key, not read it as a value and fail on JsNull.
    "transaction with an explicit null version" in {
      val txn                 = TxHelpers.commitToGeneration(Height(settings.blockchainSettings.functionalitySettings.generationPeriodLength + 1))
      val jsonWithNullVersion = txn.json() ++ Json.obj("version" -> JsNull)
      Post(routePath("/broadcast"), jsonWithNullVersion) ~> route ~> check {
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
        val digests          = (p \ "merkleProof").as[List[String]].map(s => Base16.decode(s))

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
      // The block holds these two transactions and nothing else, so they are at 0 and 1
      val proofs = Seq(
        (tx1.id(), crypto.fastHash(PBTransactions.toByteArrayMerkle(tx1)), 0),
        (tx2.id(), crypto.fastHash(PBTransactions.toByteArrayMerkle(tx2)), 1)
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
      // The genesis block carries no transactions any more - it is a snapshot - so this asks about transactions that
      // exist as ids and belong to no block
      val unknownTransactions = Seq(TxHelpers.transfer(richAccount, TxHelpers.address(1391), 1.waves).id())

      val queryParams = unknownTransactions.map(id => s"id=$id").mkString("?", "&", "")
      val requestBody = Json.obj("ids" -> unknownTransactions.map(_.toString))

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
      implicit val byteStrFormat: Format[ByteStr] = tech.hearth.utils.byteStrFormat
      (txInfo \ "order1" \ "attachment").asOpt[ByteStr] shouldBe Some(expectedAttachment)
    }

    val sender      = TxHelpers.signer(1100)
    val issuer      = tradedAssetIssuer
    val attachment  = ByteStr.fill(32)(1)
    val issuedAsset = tradedAsset
    val exchange =
      TxHelpers.exchangeFromOrders(
        TxHelpers.order(OrderType.BUY, Waves, issuedAsset, version = Order.V4, attachment = Some(attachment)),
        TxHelpers.order(OrderType.SELL, Waves, issuedAsset, version = Order.V4, sender = issuer)
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
