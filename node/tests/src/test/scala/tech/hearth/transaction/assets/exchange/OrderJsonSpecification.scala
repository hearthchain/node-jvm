package tech.hearth.transaction.assets.exchange

import tech.hearth.account.PublicKey
import tech.hearth.common.state.ByteStr
import tech.hearth.common.utils.Base16
import tech.hearth.common.utils.EitherExt2.*
import tech.hearth.test.PropSpec
import tech.hearth.transaction.Asset.{IssuedAsset, Waves}
import tech.hearth.transaction.TxHelpers
import tech.hearth.transaction.assets.exchange.OrderJson.*
import tech.hearth.utils.JsonMatchers
import play.api.libs.json.*
import tech.hearth.crypto.SigningKey

class OrderJsonSpecification extends PropSpec with JsonMatchers {

  // A fixed, arbitrary sender. `SigningKey.fromSeed` takes a 32-byte Ed25519 seed, so the short literal this used to
  // hash into a key no longer works
  private val keyPair: SigningKey = TxHelpers.signer(1)
  private val pubKeyStr: String   = PublicKey(keyPair.publicKey).toString

  property("Read Order from json") {

    val json = Json.parse(s"""
        {
          "senderPublicKey": "$pubKeyStr",
          "matcherPublicKey": "ba9e7203ca62efbaa49098ec408bdf8a3dfed5a7fa7c200ece40aade905e535f",
          "assetPair": {
            "amountAsset": "111d57a6c010051999929d46fc33d829c116c13c99f0f26b76aed9d26e1302d4",
            "priceAsset": "e26db8e38198582a39819b608f824af3829700d6a8d4eacdf564047bc4fbb091"
          },
          "orderType": "buy",
          "amount": 1,
          "matcherFee": 2,
          "price": 3,
          "timestamp": 0,
          "expiration": 0,
          "signature": "aabbcc"
        } """)

    json.validate[Order] match {
      case JsError(e) =>
        fail("Error: " + e.toString())
      case JsSuccess(o, _) =>
        o.senderPublicKey shouldBe PublicKey(keyPair.publicKey)
        o.matcherPublicKey shouldBe PublicKey(Base16.tryDecodeWithLimit("ba9e7203ca62efbaa49098ec408bdf8a3dfed5a7fa7c200ece40aade905e535f").get)
        o.assetPair.amountAsset.compatId.get shouldBe ByteStr.decodeBase16("111d57a6c010051999929d46fc33d829c116c13c99f0f26b76aed9d26e1302d4").get
        o.assetPair.priceAsset.compatId.get shouldBe ByteStr.decodeBase16("e26db8e38198582a39819b608f824af3829700d6a8d4eacdf564047bc4fbb091").get
        o.price.value shouldBe 3
        o.amount.value shouldBe 1
        o.matcherFee.value shouldBe 2
        o.timestamp shouldBe 0
        o.expiration shouldBe 0
        o.signature shouldBe ByteStr(Base16.decode("aabbcc"))
    }

    val jsonOV3 = Json.parse(s"""
        {
          "version": 3,
          "senderPublicKey": "$pubKeyStr",
          "matcherPublicKey": "ba9e7203ca62efbaa49098ec408bdf8a3dfed5a7fa7c200ece40aade905e535f",
          "assetPair": {
            "amountAsset": "111d57a6c010051999929d46fc33d829c116c13c99f0f26b76aed9d26e1302d4",
            "priceAsset": "e26db8e38198582a39819b608f824af3829700d6a8d4eacdf564047bc4fbb091"
          },
          "orderType": "buy",
          "amount": 1,
          "matcherFee": 2,
          "price": 3,
          "timestamp": 0,
          "expiration": 0,
          "signature": "aabbcc",
          "matcherFeeAssetId": "111d57a6c010051999929d46fc33d829c116c13c99f0f26b76aed9d26e1302d4"
        } """)

    jsonOV3.validate[Order] match {
      case JsError(e) =>
        fail("Error: " + e.toString())
      case JsSuccess(o, _) =>
        o.senderPublicKey shouldBe PublicKey(keyPair.publicKey)
        o.matcherPublicKey shouldBe PublicKey(Base16.tryDecodeWithLimit("ba9e7203ca62efbaa49098ec408bdf8a3dfed5a7fa7c200ece40aade905e535f").get)
        o.assetPair.amountAsset shouldBe IssuedAsset(ByteStr.decodeBase16("111d57a6c010051999929d46fc33d829c116c13c99f0f26b76aed9d26e1302d4").get)
        o.assetPair.priceAsset shouldBe IssuedAsset(ByteStr.decodeBase16("e26db8e38198582a39819b608f824af3829700d6a8d4eacdf564047bc4fbb091").get)
        o.price.value shouldBe 3
        o.amount.value shouldBe 1
        o.matcherFee.value shouldBe 2
        o.timestamp shouldBe 0
        o.expiration shouldBe 0
        o.signature shouldBe ByteStr(Base16.decode("aabbcc"))
        o.matcherFeeAssetId shouldBe IssuedAsset(ByteStr.decodeBase16("111d57a6c010051999929d46fc33d829c116c13c99f0f26b76aed9d26e1302d4").get)
    }

    val jsonOV4 = Json.parse(s"""
        {
          "version": 4,
          "senderPublicKey": "$pubKeyStr",
          "matcherPublicKey": "ba9e7203ca62efbaa49098ec408bdf8a3dfed5a7fa7c200ece40aade905e535f",
          "assetPair": {
            "amountAsset": "111d57a6c010051999929d46fc33d829c116c13c99f0f26b76aed9d26e1302d4",
            "priceAsset": "e26db8e38198582a39819b608f824af3829700d6a8d4eacdf564047bc4fbb091"
          },
          "orderType": "buy",
          "amount": 1,
          "matcherFee": 2,
          "price": 3,
          "timestamp": 4,
          "expiration": 5,
          "signature": "aabbcc",
          "matcherFeeAssetId": "111d57a6c010051999929d46fc33d829c116c13c99f0f26b76aed9d26e1302d4"
        } """)

    jsonOV4.validate[Order] match {
      case JsError(e) =>
        fail("Error: " + e.toString())
      case JsSuccess(o, _) =>
        // Pinned against the fixed sender above, so a change in how an order id is computed shows up here. Rebaselined
        // when that sender changed: the id is a function of it, and the old key could not be kept
        o.id().toString shouldBe "f662623495d2a0f036d3a4f6773b6167e3c2cdd150556b425984757654936d3a"
        o.senderPublicKey shouldBe PublicKey(keyPair.publicKey)
        o.matcherPublicKey shouldBe PublicKey(Base16.tryDecodeWithLimit("ba9e7203ca62efbaa49098ec408bdf8a3dfed5a7fa7c200ece40aade905e535f").get)
        o.assetPair.amountAsset shouldBe IssuedAsset(ByteStr.decodeBase16("111d57a6c010051999929d46fc33d829c116c13c99f0f26b76aed9d26e1302d4").get)
        o.assetPair.priceAsset shouldBe IssuedAsset(ByteStr.decodeBase16("e26db8e38198582a39819b608f824af3829700d6a8d4eacdf564047bc4fbb091").get)
        o.price.value shouldBe 3
        o.amount.value shouldBe 1
        o.matcherFee.value shouldBe 2
        o.timestamp shouldBe 4
        o.expiration shouldBe 5
        o.signature shouldBe ByteStr(Base16.decode("aabbcc"))
        o.matcherFeeAssetId shouldBe IssuedAsset(ByteStr.decodeBase16("111d57a6c010051999929d46fc33d829c116c13c99f0f26b76aed9d26e1302d4").get)
    }

  }

  property("Read Order without sender and matcher PublicKey") {
    val json = Json.parse("""
        {
          "senderPublicKey": " ",
          "spendAssetId": "string",
          "receiveAssetId": "string",
          "amount": 1,
          "matcherFee": 2,
          "price": 3,
          "timestamp": 0,
          "expiration": 0,
          "signature": "aabbcc"
        } """)

    json.validate[Order] match {
      case e: JsError =>
        val paths = e.errors.map(_._1)
        paths should contain.allOf(JsPath \ "matcherPublicKey", JsPath \ "senderPublicKey")
      case _ =>
        fail("Should be JsError")
    }
  }

  val hexStr = "ba9e7203ca62efbaa49098ec408bdf8a3dfed5a7fa7c200ece40aade905e535f"
  val json: JsValue = Json.parse(s"""
    {
      "sender": "$hexStr",
      "wrong_sender": "0abcd",
      "wrong_long": "12e",
      "publicKey": "$hexStr",
      "wrong_publicKey": "0abcd"
    }
    """)

  property("Json Reads Base16") {
    val sender = (json \ "sender").as[Option[Array[Byte]]]
    sender.get shouldBe Base16.tryDecodeWithLimit(hexStr).get

    (json \ "wrong_sender").validate[Array[Byte]] shouldBe a[JsError]
  }

  property("Json Reads PublicKey") {
    val publicKey = (json \ "publicKey").as[PublicKey]
    publicKey shouldBe PublicKey(Base16.tryDecodeWithLimit(hexStr).get)

    (json \ "wrong_publicKey").validate[PublicKey] match {
      case e: JsError =>
        e.errors.head._2.head.message shouldBe "error.incorrectAccount"
      case _ => fail("Should be JsError")
    }
  }

  property("Parse signed Order") {
    forAll(orderGen) { order =>
      val json = order.json()
      json.validate[Order] match {
        case e: JsError =>
          fail("Error: " + JsError.toJson(e).toString())

        case JsSuccess(o: Order, _) =>
          o.json() should matchJson(json)
          o.firstProofIsValidSignatureAfterV6.explicitGet()
      }
    }
  }

  property("Read Order with empty assetId") {
    def mkJson(priceAsset: String): String =
      s"""
        {
          "senderPublicKey": "$pubKeyStr",
          "matcherPublicKey": "ba9e7203ca62efbaa49098ec408bdf8a3dfed5a7fa7c200ece40aade905e535f",
           "assetPair": {
             "amountAsset": "",
             "priceAsset": $priceAsset
           },
          "orderType": "sell",
          "amount": 1,
          "matcherFee": 2,
          "price": 3,
          "timestamp": 0,
          "expiration": 0,
          "signature": "aabbcc"
        } """

    val jsons = Seq(""" "" """, "null", """ "WAVES" """).map { x =>
      x -> mkJson(x)
    }

    jsons.foreach { case (priceAssetStr, rawJson) =>
      withClue(priceAssetStr) {
        Json.parse(rawJson).validate[Order] match {
          case e: JsError =>
            fail("Error: " + JsError.toJson(e).toString())
          case JsSuccess(o, _) =>
            o.assetPair.amountAsset shouldBe Waves
            o.assetPair.priceAsset shouldBe Waves
        }
      }
    }
  }
}
