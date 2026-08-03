package tech.hearth.transaction.assets.exchange

import tech.hearth.account.PublicKey
import tech.hearth.common.state.ByteStr
import tech.hearth.common.utils.Base58
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
          "matcherPublicKey": "DZUxn4pC7QdYrRqacmaAJghatvnn1Kh1mkE2scZoLuGJ",
          "assetPair": {
            "amountAsset": "29ot86P3HoUZXH1FCoyvff7aeZ3Kt7GqPwBWXncjRF2b",
            "priceAsset": "GEtBMkg419zhDiYRXKwn2uPcabyXKqUqj4w3Gcs1dq44"
          },
          "orderType": "buy",
          "amount": 1,
          "matcherFee": 2,
          "price": 3,
          "timestamp": 0,
          "expiration": 0,
          "signature": "signature"
        } """)

    json.validate[Order] match {
      case JsError(e) =>
        fail("Error: " + e.toString())
      case JsSuccess(o, _) =>
        o.senderPublicKey shouldBe PublicKey(keyPair.publicKey)
        o.matcherPublicKey shouldBe PublicKey(Base58.tryDecodeWithLimit("DZUxn4pC7QdYrRqacmaAJghatvnn1Kh1mkE2scZoLuGJ").get)
        o.assetPair.amountAsset.compatId.get shouldBe ByteStr.decodeBase58("29ot86P3HoUZXH1FCoyvff7aeZ3Kt7GqPwBWXncjRF2b").get
        o.assetPair.priceAsset.compatId.get shouldBe ByteStr.decodeBase58("GEtBMkg419zhDiYRXKwn2uPcabyXKqUqj4w3Gcs1dq44").get
        o.price.value shouldBe 3
        o.amount.value shouldBe 1
        o.matcherFee.value shouldBe 2
        o.timestamp shouldBe 0
        o.expiration shouldBe 0
        o.signature shouldBe ByteStr(Base58.decode("signature"))
    }

    val jsonOV3 = Json.parse(s"""
        {
          "version": 3,
          "senderPublicKey": "$pubKeyStr",
          "matcherPublicKey": "DZUxn4pC7QdYrRqacmaAJghatvnn1Kh1mkE2scZoLuGJ",
          "assetPair": {
            "amountAsset": "29ot86P3HoUZXH1FCoyvff7aeZ3Kt7GqPwBWXncjRF2b",
            "priceAsset": "GEtBMkg419zhDiYRXKwn2uPcabyXKqUqj4w3Gcs1dq44"
          },
          "orderType": "buy",
          "amount": 1,
          "matcherFee": 2,
          "price": 3,
          "timestamp": 0,
          "expiration": 0,
          "signature": "signature",
          "matcherFeeAssetId": "29ot86P3HoUZXH1FCoyvff7aeZ3Kt7GqPwBWXncjRF2b"
        } """)

    jsonOV3.validate[Order] match {
      case JsError(e) =>
        fail("Error: " + e.toString())
      case JsSuccess(o, _) =>
        o.senderPublicKey shouldBe PublicKey(keyPair.publicKey)
        o.matcherPublicKey shouldBe PublicKey(Base58.tryDecodeWithLimit("DZUxn4pC7QdYrRqacmaAJghatvnn1Kh1mkE2scZoLuGJ").get)
        o.assetPair.amountAsset shouldBe IssuedAsset(ByteStr.decodeBase58("29ot86P3HoUZXH1FCoyvff7aeZ3Kt7GqPwBWXncjRF2b").get)
        o.assetPair.priceAsset shouldBe IssuedAsset(ByteStr.decodeBase58("GEtBMkg419zhDiYRXKwn2uPcabyXKqUqj4w3Gcs1dq44").get)
        o.price.value shouldBe 3
        o.amount.value shouldBe 1
        o.matcherFee.value shouldBe 2
        o.timestamp shouldBe 0
        o.expiration shouldBe 0
        o.signature shouldBe ByteStr(Base58.decode("signature"))
        o.matcherFeeAssetId shouldBe IssuedAsset(ByteStr.decodeBase58("29ot86P3HoUZXH1FCoyvff7aeZ3Kt7GqPwBWXncjRF2b").get)
    }

    val jsonOV4 = Json.parse(s"""
        {
          "version": 4,
          "senderPublicKey": "$pubKeyStr",
          "matcherPublicKey": "DZUxn4pC7QdYrRqacmaAJghatvnn1Kh1mkE2scZoLuGJ",
          "assetPair": {
            "amountAsset": "29ot86P3HoUZXH1FCoyvff7aeZ3Kt7GqPwBWXncjRF2b",
            "priceAsset": "GEtBMkg419zhDiYRXKwn2uPcabyXKqUqj4w3Gcs1dq44"
          },
          "orderType": "buy",
          "amount": 1,
          "matcherFee": 2,
          "price": 3,
          "timestamp": 4,
          "expiration": 5,
          "signature": "signature",
          "matcherFeeAssetId": "29ot86P3HoUZXH1FCoyvff7aeZ3Kt7GqPwBWXncjRF2b"
        } """)

    jsonOV4.validate[Order] match {
      case JsError(e) =>
        fail("Error: " + e.toString())
      case JsSuccess(o, _) =>
        // Pinned against the fixed sender above, so a change in how an order id is computed shows up here. Rebaselined
        // when that sender changed: the id is a function of it, and the old key could not be kept
        o.id().toString shouldBe "HanJfjSFt7JWuHZj7Y1dzN4A2MYwdxM7az3pE4mUSYRP"
        o.senderPublicKey shouldBe PublicKey(keyPair.publicKey)
        o.matcherPublicKey shouldBe PublicKey(Base58.tryDecodeWithLimit("DZUxn4pC7QdYrRqacmaAJghatvnn1Kh1mkE2scZoLuGJ").get)
        o.assetPair.amountAsset shouldBe IssuedAsset(ByteStr.decodeBase58("29ot86P3HoUZXH1FCoyvff7aeZ3Kt7GqPwBWXncjRF2b").get)
        o.assetPair.priceAsset shouldBe IssuedAsset(ByteStr.decodeBase58("GEtBMkg419zhDiYRXKwn2uPcabyXKqUqj4w3Gcs1dq44").get)
        o.price.value shouldBe 3
        o.amount.value shouldBe 1
        o.matcherFee.value shouldBe 2
        o.timestamp shouldBe 4
        o.expiration shouldBe 5
        o.signature shouldBe ByteStr(Base58.decode("signature"))
        o.matcherFeeAssetId shouldBe IssuedAsset(ByteStr.decodeBase58("29ot86P3HoUZXH1FCoyvff7aeZ3Kt7GqPwBWXncjRF2b").get)
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
          "signature": "signature"
        } """)

    json.validate[Order] match {
      case e: JsError =>
        val paths = e.errors.map(_._1)
        paths should contain.allOf(JsPath \ "matcherPublicKey", JsPath \ "senderPublicKey")
      case _ =>
        fail("Should be JsError")
    }
  }

  val base58Str = "DZUxn4pC7QdYrRqacmaAJghatvnn1Kh1mkE2scZoLuGJ"
  val json: JsValue = Json.parse(s"""
    {
      "sender": "$base58Str",
      "wrong_sender": "0abcd",
      "wrong_long": "12e",
      "publicKey": "$base58Str",
      "wrong_publicKey": "0abcd"
    }
    """)

  property("Json Reads Base58") {
    val sender = (json \ "sender").as[Option[Array[Byte]]]
    sender.get shouldBe Base58.tryDecodeWithLimit(base58Str).get

    (json \ "wrong_sender").validate[Array[Byte]] shouldBe a[JsError]
  }

  property("Json Reads PublicKey") {
    val publicKey = (json \ "publicKey").as[PublicKey]
    publicKey shouldBe PublicKey(Base58.tryDecodeWithLimit(base58Str).get)

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
          "matcherPublicKey": "DZUxn4pC7QdYrRqacmaAJghatvnn1Kh1mkE2scZoLuGJ",
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
          "signature": "signature"
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
