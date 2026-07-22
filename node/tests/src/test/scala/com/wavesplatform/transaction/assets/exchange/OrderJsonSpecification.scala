package com.wavesplatform.transaction.assets.exchange

import com.wavesplatform.account.PublicKey
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.Base16
import com.wavesplatform.common.utils.EitherExt2.*
import com.wavesplatform.test.PropSpec
import com.wavesplatform.transaction.Asset.{IssuedAsset, Waves}
import com.wavesplatform.transaction.assets.exchange.OrderJson.*
import com.wavesplatform.transaction.Proofs
import com.wavesplatform.utils.JsonMatchers
import play.api.libs.json.*
import tech.hearth.crypto.SigningKey

class OrderJsonSpecification extends PropSpec with JsonMatchers {

  property("Read Order from json") {
    val keyPair   = SigningKey.fromSeed("123".getBytes("UTF-8"))
    val pubKeyStr = PublicKey(keyPair.publicKey).toString

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
          "signature": "171358ac6fdcf7"
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
        o.signature shouldBe ByteStr(Base16.decode("171358ac6fdcf7"))
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
          "signature": "171358ac6fdcf7",
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
        o.signature shouldBe ByteStr(Base16.decode("171358ac6fdcf7"))
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
          "signature": "171358ac6fdcf7",
          "matcherFeeAssetId": "111d57a6c010051999929d46fc33d829c116c13c99f0f26b76aed9d26e1302d4"
        } """)

    jsonOV4.validate[Order] match {
      case JsError(e) =>
        fail("Error: " + e.toString())
      case JsSuccess(o, _) =>
        o.id().toString shouldBe "9bd55472a0202f12eec8415412ff8b59c97ae1320690373c506cf503f9c373ec"
        o.senderPublicKey shouldBe PublicKey(keyPair.publicKey)
        o.matcherPublicKey shouldBe PublicKey(Base16.tryDecodeWithLimit("ba9e7203ca62efbaa49098ec408bdf8a3dfed5a7fa7c200ece40aade905e535f").get)
        o.assetPair.amountAsset shouldBe IssuedAsset(ByteStr.decodeBase16("111d57a6c010051999929d46fc33d829c116c13c99f0f26b76aed9d26e1302d4").get)
        o.assetPair.priceAsset shouldBe IssuedAsset(ByteStr.decodeBase16("e26db8e38198582a39819b608f824af3829700d6a8d4eacdf564047bc4fbb091").get)
        o.price.value shouldBe 3
        o.amount.value shouldBe 1
        o.matcherFee.value shouldBe 2
        o.timestamp shouldBe 4
        o.expiration shouldBe 5
        o.signature shouldBe ByteStr(Base16.decode("171358ac6fdcf7"))
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
          "signature": "171358ac6fdcf7"
        } """)

    json.validate[Order] match {
      case e: JsError =>
        val paths = e.errors.map(_._1)
        paths should contain.allOf(JsPath \ "matcherPublicKey", JsPath \ "senderPublicKey")
      case _ =>
        fail("Should be JsError")
    }
  }

  val base16Str = "ba9e7203ca62efbaa49098ec408bdf8a3dfed5a7fa7c200ece40aade905e535f"
  val json: JsValue = Json.parse(s"""
    {
      "sender": "$base16Str",
      "wrong_sender": "0abcd",
      "wrong_long": "12e",
      "publicKey": "$base16Str",
      "wrong_publicKey": "0abcd"
    }
    """)

  property("Json Reads Base16") {
    val sender = (json \ "sender").as[Option[Array[Byte]]]
    sender.get shouldBe Base16.tryDecodeWithLimit(base16Str).get

    (json \ "wrong_sender").validate[Array[Byte]] shouldBe a[JsError]
  }

  property("Json Reads PublicKey") {
    val publicKey = (json \ "publicKey").as[PublicKey]
    publicKey shouldBe PublicKey(Base16.tryDecodeWithLimit(base16Str).get)

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
          o.firstProofIsValidSignatureBeforeV6.explicitGet()
      }
    }
  }

  property("Read Order with empty assetId") {
    def mkJson(priceAsset: String): String =
      s"""
        {
          "senderPublicKey": "${PublicKey(SigningKey.fromSeed("123".getBytes("UTF-8")).publicKey).toString}",
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
          "signature": "171358ac6fdcf7"
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
