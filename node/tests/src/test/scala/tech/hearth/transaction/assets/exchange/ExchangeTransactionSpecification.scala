package tech.hearth.transaction.assets.exchange

import tech.hearth.account.PublicKey
import tech.hearth.common.state.ByteStr
import tech.hearth.common.utils.EitherExt2.*
import tech.hearth.lang.ValidationError
import tech.hearth.test.PropSpec
import tech.hearth.transaction.Asset.{IssuedAsset, Waves}
import tech.hearth.transaction.TxValidationError.{GenericError, OrderValidationError}
import tech.hearth.transaction.assets.exchange.AssetPair.extractAssetId
import tech.hearth.transaction.serialization.impl.PBTransactionSerializer
import tech.hearth.transaction.{Asset, Proofs, TxExchangeAmount, TxHelpers, TxMatcherFee, TxOrderPrice}
import tech.hearth.utils.JsonMatchers
import tech.hearth.NTPTime
import org.scalacheck.Gen
import play.api.libs.json.Json
import tech.hearth.crypto.SigningKey

import scala.math.pow

//noinspection ScalaStyle
class ExchangeTransactionSpecification extends PropSpec with NTPTime with JsonMatchers {
  val versionsGen: Gen[(Byte, Byte, Byte)] = Gen.oneOf(
    (1.toByte, 1.toByte, 1.toByte),
    (1.toByte, 2.toByte, 2.toByte),
    (1.toByte, 3.toByte, 2.toByte),
    (2.toByte, 2.toByte, 2.toByte),
    (2.toByte, 3.toByte, 2.toByte),
    (3.toByte, 3.toByte, 2.toByte)
  )

  val preconditions: Gen[(SigningKey, SigningKey, SigningKey, AssetPair, Asset, Asset, (Byte, Byte, Byte))] =
    for {
      sender1                 <- accountGen
      sender2                 <- accountGen
      matcher                 <- accountGen
      pair                    <- assetPairGen
      buyerAnotherAsset       <- assetIdGen.map(Asset.fromCompatId)
      sellerAnotherAsset      <- assetIdGen.map(Asset.fromCompatId)
      buyerMatcherFeeAssetId  <- Gen.oneOf(pair.amountAsset, pair.priceAsset, buyerAnotherAsset)
      sellerMatcherFeeAssetId <- Gen.oneOf(pair.amountAsset, pair.priceAsset, sellerAnotherAsset)
      versions                <- versionsGen
    } yield (sender1, sender2, matcher, pair, buyerMatcherFeeAssetId, sellerMatcherFeeAssetId, versions)

  property("ExchangeTransaction transaction serialization roundtrip") {
    forAll(exchangeTransactionGen) { om =>
      val recovered = PBTransactionSerializer.parseBytes(om.bytes()).get.asInstanceOf[ExchangeTransaction]
      om.id() shouldBe recovered.id()
      om.buyOrder.idStr() shouldBe recovered.buyOrder.idStr()
      recovered.bytes() shouldEqual om.bytes()
    }
  }

  property("ExchangeTransaction invariants validation") {

    forAll(preconditions) { case (sender1, sender2, matcher, pair, buyerMatcherFeeAssetId, sellerMatcherFeeAssetId, versions) =>
      val time                = ntpTime.correctedTime()
      val expirationTimestamp = time + Order.MaxLiveTime / 2

      val buyPrice       = 60 * Order.PriceConstant
      val sellPrice      = 50 * Order.PriceConstant
      val buyAmount      = 2
      val sellAmount     = 3
      val buyMatcherFee  = 1
      val sellMatcherFee = 2

      val (buyV, sellV, _) = versions

      val buy = TxHelpers
        .buy(
          buyV,
          sender1,
          PublicKey(matcher.publicKey),
          pair,
          buyAmount,
          buyPrice,
          time,
          expirationTimestamp,
          buyMatcherFee,
          if (buyV == 3) buyerMatcherFeeAssetId else Waves
        )
        .explicitGet()
      val sell = TxHelpers
        .sell(
          sellV,
          sender2,
          PublicKey(matcher.publicKey),
          pair,
          sellAmount,
          sellPrice,
          time,
          expirationTimestamp,
          sellMatcherFee,
          if (sellV == 3) sellerMatcherFeeAssetId else Waves
        )
        .explicitGet()

      def create(
          buyOrder: Order = buy,
          sellOrder: Order = sell,
          amount: Long = buyAmount,
          price: Long = sellPrice,
          buyMatcherFee: Long = buyMatcherFee,
          sellMatcherFee: Long = 1,
          fee: Long = 1,
          timestamp: Long = expirationTimestamp - Order.MaxLiveTime
      ): Either[ValidationError, ExchangeTransaction] = {
        ExchangeTransaction
          .create(
            order1 = buyOrder,
            order2 = sellOrder,
            amount = amount,
            price = price,
            buyMatcherFee = buyMatcherFee,
            sellMatcherFee = sellMatcherFee,
            fee = fee,
            timestamp = timestamp
          )
      }

      buy.version shouldBe buyV
      sell.version shouldBe sellV

      create() shouldBe an[Right[?, ?]]
      create(fee = pow(10, 18).toLong) shouldBe an[Right[?, ?]]
      create(amount = Order.MaxAmount) shouldBe an[Right[?, ?]]

      create(fee = -1) shouldBe an[Left[?, ?]]
      create(amount = -1) shouldBe an[Left[?, ?]]
      create(amount = Order.MaxAmount + 1) shouldBe an[Left[?, ?]]
      create(price = -1) shouldBe an[Left[?, ?]]
      create(sellMatcherFee = Order.MaxAmount + 1) shouldBe an[Left[?, ?]]
      create(buyMatcherFee = Order.MaxAmount + 1) shouldBe an[Left[?, ?]]
      create(fee = Order.MaxAmount + 1) shouldBe an[Left[?, ?]]

      create(buyOrder = buy.copy(orderType = OrderType.SELL)) shouldBe Left(GenericError("buyOrder should has OrderType.BUY"))
      create(buyOrder = buy.copy(assetPair = buy.assetPair.copy(amountAsset = sell.assetPair.priceAsset))) shouldBe an[Left[?, ?]]
      create(buyOrder = buy.copy(expiration = 1L)) shouldBe an[Left[?, ?]]
      create(buyOrder = buy.copy(expiration = buy.expiration + 1)) shouldBe an[Left[?, ?]]
      create(buyOrder = buy.copy(matcherPublicKey = PublicKey(sender2.publicKey))) shouldBe an[Left[?, ?]]

      create(sellOrder = sell.copy(orderType = OrderType.BUY)) shouldBe Left(GenericError("sellOrder should has OrderType.SELL"))
      create(sellOrder = sell.copy(assetPair = sell.assetPair.copy(priceAsset = buy.assetPair.amountAsset))) shouldBe an[Left[?, ?]]
      create(sellOrder = sell.copy(expiration = 1L)) shouldBe an[Left[?, ?]]
      create(sellOrder = sell.copy(expiration = sell.expiration + 1)) shouldBe an[Left[?, ?]]
      create(sellOrder = sell.copy(matcherPublicKey = PublicKey(sender2.publicKey))) shouldBe an[Left[?, ?]]

      // Passing the sell order as order1 is allowed - see "ExchangeTransaction V3 can have SELL order as order1"
      create(sellOrder = buy, buyOrder = sell) shouldBe an[Right[?, ?]]
      create(sellOrder = sell, buyOrder = sell) shouldBe Left(GenericError("buyOrder should has OrderType.BUY"))
      create(sellOrder = buy, buyOrder = buy) shouldBe Left(GenericError("sellOrder should has OrderType.SELL"))

      create(
        buyOrder = buy.copy(assetPair = buy.assetPair.copy(amountAsset = Waves)),
        sellOrder = sell.copy(assetPair = sell.assetPair.copy(priceAsset = IssuedAsset(ByteStr(Array(1: Byte)))))
      ) shouldBe an[Left[?, ?]]
    }
  }

  def createExTx(buy: Order, sell: Order, price: Long): Either[ValidationError, ExchangeTransaction] = {
    val matcherFee = 300000L
    val amount     = math.min(buy.amount.value, sell.amount.value)

    ExchangeTransaction.create(
      order1 = buy,
      order2 = sell,
      amount = amount,
      price = price,
      buyMatcherFee = (BigInt(matcherFee) * amount / buy.amount.value).toLong,
      sellMatcherFee = (BigInt(matcherFee) * amount / sell.amount.value).toLong,
      fee = matcherFee,
      timestamp = ntpTime.correctedTime()
    )
  }

  property("Test transaction with small amount and expired order") {

    forAll(preconditions) { case (sender1, sender2, matcher, pair, buyerMatcherFeeAssetId, sellerMatcherFeeAssetId, versions) =>
      val time                = ntpTime.correctedTime()
      val expirationTimestamp = time + Order.MaxLiveTime / 2
      val buyPrice            = 1 * Order.PriceConstant
      val sellPrice           = (0.50 * Order.PriceConstant).toLong
      val matcherFee          = 300000L
      val (sellV, buyV, _)    = versions

      val sell =
        TxHelpers
          .sell(
            sellV,
            sender2,
            PublicKey(matcher.publicKey),
            pair,
            2,
            sellPrice,
            time,
            expirationTimestamp,
            matcherFee,
            if (sellV == 3) sellerMatcherFeeAssetId else Waves
          )
          .explicitGet()
      val buy =
        TxHelpers
          .buy(
            buyV,
            sender1,
            PublicKey(matcher.publicKey),
            pair,
            1,
            buyPrice,
            time,
            expirationTimestamp,
            matcherFee,
            if (buyV == 3) buyerMatcherFeeAssetId else Waves
          )
          .explicitGet()

      createExTx(buy, sell, sellPrice) shouldBe an[Right[?, ?]]

      val sell1 =
        if (sellV == 3) {
          TxHelpers
            .sell(sellV, sender2, PublicKey(matcher.publicKey), pair, 1, buyPrice, time, time - 1, matcherFee, sellerMatcherFeeAssetId)
            .explicitGet()
        } else TxHelpers.sell(sellV, sender2, PublicKey(matcher.publicKey), pair, 1, buyPrice, time, time - 1, matcherFee).explicitGet()

      createExTx(buy, sell1, buyPrice) shouldBe Left(OrderValidationError(sell1, "expiration should be > currentTime"))
    }
  }

  property("JSON format validation") {
    val buy = Order(
      Order.V1,
      OrderAuthentication.OrderProofs(
        PublicKey.fromBase58String("BqeJY8CP3PeUDaByz57iRekVUGtLxoow4XxPvXfHynaZ").explicitGet(),
        Proofs(ByteStr.decodeBase58("2bkuGwECMFGyFqgoHV4q7GRRWBqYmBFWpYRkzgYANR4nN2twgrNaouRiZBqiK2RJzuo9NooB9iRiuZ4hypBbUQs").get)
      ),
      PublicKey.fromBase58String("Fvk5DXmfyWVZqQVBowUBMwYtRAHDtdyZNNeRrwSjt6KP").explicitGet(),
      AssetPair.createAssetPair("WAVES", "9ZDWzK53XT5bixkmMwTJi2YzgxCqn5dUajXFcT2HcFDy").get,
      OrderType.BUY,
      TxExchangeAmount.unsafeFrom(2),
      TxOrderPrice.unsafeFrom(6000000000L),
      1526992336241L,
      1529584336241L,
      TxMatcherFee.unsafeFrom(1)
    )

    val sell = Order(
      Order.V1,
      OrderAuthentication.OrderProofs(
        PublicKey.fromBase58String("7E9Za8v8aT6EyU1sX91CVK7tWUeAetnNYDxzKZsyjyKV").explicitGet(),
        Proofs(ByteStr.decodeBase58("2R6JfmNjEnbXAA6nt8YuCzSf1effDS4Wkz8owpCD9BdCNn864SnambTuwgLRYzzeP5CAsKHEviYKAJ2157vdr5Zq").get)
      ),
      PublicKey.fromBase58String("Fvk5DXmfyWVZqQVBowUBMwYtRAHDtdyZNNeRrwSjt6KP").explicitGet(),
      AssetPair.createAssetPair("WAVES", "9ZDWzK53XT5bixkmMwTJi2YzgxCqn5dUajXFcT2HcFDy").get,
      OrderType.SELL,
      TxExchangeAmount.unsafeFrom(3),
      TxOrderPrice.unsafeFrom(5000000000L),
      1526992336241L,
      1529584336241L,
      TxMatcherFee.unsafeFrom(2)
    )

    val tx = ExchangeTransaction
      .create(
        buy,
        sell,
        2,
        5000000000L,
        1,
        1,
        1,
        1526992336241L,
        Proofs(ByteStr.decodeBase58("5NxNhjMrrH5EWjSFnVnPbanpThic6fnNL48APVAkwq19y2FpQp4tNSqoAZgboC2ykUfqQs9suwBQj6wERmsWWNqa").get)
      )
      .explicitGet()

    // Ids and addresses are derived rather than pasted in: an address is bech32 now and an id is a hash over bytes
    // that have changed. An ExchangeTransaction also has no version any more, is type 3, and carries its chain id.
    val js = Json.parse(s"""{
         "type":3,
         "id":"${tx.id()}",
         "sender":"${tx.sender.toAddress}",
         "senderPublicKey":"Fvk5DXmfyWVZqQVBowUBMwYtRAHDtdyZNNeRrwSjt6KP",
         "fee":1,
         "feeAssetId": null,
         "timestamp":1526992336241,
         "chainId":${tx.chainId},
         "proofs":["5NxNhjMrrH5EWjSFnVnPbanpThic6fnNL48APVAkwq19y2FpQp4tNSqoAZgboC2ykUfqQs9suwBQj6wERmsWWNqa"],
         "order1":{
            "version": 1,
            "id":"${buy.id()}",
            "sender":"${buy.sender.toAddress}",
            "senderPublicKey":"BqeJY8CP3PeUDaByz57iRekVUGtLxoow4XxPvXfHynaZ",
            "matcherPublicKey":"Fvk5DXmfyWVZqQVBowUBMwYtRAHDtdyZNNeRrwSjt6KP",
            "assetPair":{"amountAsset":null,"priceAsset":"9ZDWzK53XT5bixkmMwTJi2YzgxCqn5dUajXFcT2HcFDy"},
            "orderType":"buy",
            "price":6000000000,
            "amount":2,
            "timestamp":1526992336241,
            "expiration":1529584336241,
            "matcherFee":1,
            "signature":"2bkuGwECMFGyFqgoHV4q7GRRWBqYmBFWpYRkzgYANR4nN2twgrNaouRiZBqiK2RJzuo9NooB9iRiuZ4hypBbUQs",
            "proofs":["2bkuGwECMFGyFqgoHV4q7GRRWBqYmBFWpYRkzgYANR4nN2twgrNaouRiZBqiK2RJzuo9NooB9iRiuZ4hypBbUQs"]
         },
         "order2":{
            "version": 1,
            "id":"${sell.id()}",
            "sender":"${sell.sender.toAddress}",
            "senderPublicKey":"7E9Za8v8aT6EyU1sX91CVK7tWUeAetnNYDxzKZsyjyKV",
            "matcherPublicKey":"Fvk5DXmfyWVZqQVBowUBMwYtRAHDtdyZNNeRrwSjt6KP",
            "assetPair":{"amountAsset":null,"priceAsset":"9ZDWzK53XT5bixkmMwTJi2YzgxCqn5dUajXFcT2HcFDy"},
            "orderType":"sell",
            "price":5000000000,
            "amount":3,
            "timestamp":1526992336241,
            "expiration":1529584336241,
            "matcherFee":2,
            "signature":"2R6JfmNjEnbXAA6nt8YuCzSf1effDS4Wkz8owpCD9BdCNn864SnambTuwgLRYzzeP5CAsKHEviYKAJ2157vdr5Zq",
            "proofs":["2R6JfmNjEnbXAA6nt8YuCzSf1effDS4Wkz8owpCD9BdCNn864SnambTuwgLRYzzeP5CAsKHEviYKAJ2157vdr5Zq"]
         },
         "price":5000000000,
         "amount":2,
         "buyMatcherFee":1,
         "sellMatcherFee":1
      }
      """)

    js should matchJson(tx.json())
  }

  property("JSON format validation V2") {

    val buy = Order(
      Order.V2,
      OrderAuthentication.OrderProofs(
        PublicKey.fromBase58String("BqeJY8CP3PeUDaByz57iRekVUGtLxoow4XxPvXfHynaZ").explicitGet(),
        Proofs(ByteStr.decodeBase58("2bkuGwECMFGyFqgoHV4q7GRRWBqYmBFWpYRkzgYANR4nN2twgrNaouRiZBqiK2RJzuo9NooB9iRiuZ4hypBbUQs").get)
      ),
      PublicKey.fromBase58String("Fvk5DXmfyWVZqQVBowUBMwYtRAHDtdyZNNeRrwSjt6KP").explicitGet(),
      AssetPair.createAssetPair("WAVES", "9ZDWzK53XT5bixkmMwTJi2YzgxCqn5dUajXFcT2HcFDy").get,
      OrderType.BUY,
      TxExchangeAmount.unsafeFrom(2),
      TxOrderPrice.unsafeFrom(6000000000L),
      1526992336241L,
      1529584336241L,
      TxMatcherFee.unsafeFrom(1)
    )

    val sell = Order(
      Order.V1,
      OrderAuthentication.OrderProofs(
        PublicKey.fromBase58String("7E9Za8v8aT6EyU1sX91CVK7tWUeAetnNYDxzKZsyjyKV").explicitGet(),
        Proofs(ByteStr.decodeBase58("2R6JfmNjEnbXAA6nt8YuCzSf1effDS4Wkz8owpCD9BdCNn864SnambTuwgLRYzzeP5CAsKHEviYKAJ2157vdr5Zq").get)
      ),
      PublicKey.fromBase58String("Fvk5DXmfyWVZqQVBowUBMwYtRAHDtdyZNNeRrwSjt6KP").explicitGet(),
      AssetPair.createAssetPair("WAVES", "9ZDWzK53XT5bixkmMwTJi2YzgxCqn5dUajXFcT2HcFDy").get,
      OrderType.SELL,
      TxExchangeAmount.unsafeFrom(3),
      TxOrderPrice.unsafeFrom(5000000000L),
      1526992336241L,
      1529584336241L,
      TxMatcherFee.unsafeFrom(2)
    )

    val tx = ExchangeTransaction
      .create(
        buy,
        sell,
        2,
        5000000000L,
        1,
        1,
        1,
        1526992336241L,
        Proofs(Seq(ByteStr.decodeBase58("5NxNhjMrrH5EWjSFnVnPbanpThic6fnNL48APVAkwq19y2FpQp4tNSqoAZgboC2ykUfqQs9suwBQj6wERmsWWNqa").get))
      )
      .explicitGet()

    // Same as above: no transaction version, type 3, chain id, and derived ids and addresses
    val js = Json.parse(s"""{
         "type":3,
         "id":"${tx.id()}",
         "sender":"${tx.sender.toAddress}",
         "senderPublicKey":"Fvk5DXmfyWVZqQVBowUBMwYtRAHDtdyZNNeRrwSjt6KP",
         "fee":1,
         "feeAssetId": null,
         "timestamp":1526992336241,
         "chainId":${tx.chainId},
         "proofs":["5NxNhjMrrH5EWjSFnVnPbanpThic6fnNL48APVAkwq19y2FpQp4tNSqoAZgboC2ykUfqQs9suwBQj6wERmsWWNqa"],
         "order1":{
            "version": 2,
            "id":"${buy.id()}",
            "sender":"${buy.sender.toAddress}",
            "senderPublicKey":"BqeJY8CP3PeUDaByz57iRekVUGtLxoow4XxPvXfHynaZ",
            "matcherPublicKey":"Fvk5DXmfyWVZqQVBowUBMwYtRAHDtdyZNNeRrwSjt6KP",
            "assetPair":{"amountAsset":null,"priceAsset":"9ZDWzK53XT5bixkmMwTJi2YzgxCqn5dUajXFcT2HcFDy"},
            "orderType":"buy",
            "price":6000000000,
            "amount":2,
            "timestamp":1526992336241,
            "expiration":1529584336241,
            "matcherFee":1,
            "signature":"2bkuGwECMFGyFqgoHV4q7GRRWBqYmBFWpYRkzgYANR4nN2twgrNaouRiZBqiK2RJzuo9NooB9iRiuZ4hypBbUQs",
            "proofs":["2bkuGwECMFGyFqgoHV4q7GRRWBqYmBFWpYRkzgYANR4nN2twgrNaouRiZBqiK2RJzuo9NooB9iRiuZ4hypBbUQs"]
         },
         "order2":{
            "version": 1,
            "id":"${sell.id()}",
            "sender":"${sell.sender.toAddress}",
            "senderPublicKey":"7E9Za8v8aT6EyU1sX91CVK7tWUeAetnNYDxzKZsyjyKV",
            "matcherPublicKey":"Fvk5DXmfyWVZqQVBowUBMwYtRAHDtdyZNNeRrwSjt6KP",
            "assetPair":{"amountAsset":null,"priceAsset":"9ZDWzK53XT5bixkmMwTJi2YzgxCqn5dUajXFcT2HcFDy"},
            "orderType":"sell",
            "price":5000000000,
            "amount":3,
            "timestamp":1526992336241,
            "expiration":1529584336241,
            "matcherFee":2,
            "signature":"2R6JfmNjEnbXAA6nt8YuCzSf1effDS4Wkz8owpCD9BdCNn864SnambTuwgLRYzzeP5CAsKHEviYKAJ2157vdr5Zq",
            "proofs":["2R6JfmNjEnbXAA6nt8YuCzSf1effDS4Wkz8owpCD9BdCNn864SnambTuwgLRYzzeP5CAsKHEviYKAJ2157vdr5Zq"]
         },
         "price":5000000000,
         "amount":2,
         "buyMatcherFee":1,
         "sellMatcherFee":1
      }
      """)

    js should matchJson(tx.json())
  }

  property("JSON format validation V2 OrderV3") {

    val buy = Order(
      Order.V3,
      OrderAuthentication.OrderProofs(
        PublicKey.fromBase58String("BqeJY8CP3PeUDaByz57iRekVUGtLxoow4XxPvXfHynaZ").explicitGet(),
        Proofs(ByteStr.decodeBase58("2bkuGwECMFGyFqgoHV4q7GRRWBqYmBFWpYRkzgYANR4nN2twgrNaouRiZBqiK2RJzuo9NooB9iRiuZ4hypBbUQs").get)
      ),
      PublicKey.fromBase58String("Fvk5DXmfyWVZqQVBowUBMwYtRAHDtdyZNNeRrwSjt6KP").explicitGet(),
      AssetPair.createAssetPair("WAVES", "9ZDWzK53XT5bixkmMwTJi2YzgxCqn5dUajXFcT2HcFDy").get,
      OrderType.BUY,
      TxExchangeAmount.unsafeFrom(2),
      TxOrderPrice.unsafeFrom(6000000000L),
      1526992336241L,
      1529584336241L,
      TxMatcherFee.unsafeFrom(1),
      extractAssetId("9ZDWzK53XT5bixkmMwTJi2YzgxCqn5dUajXFcT2HcFDy").get
    )

    val sell = Order(
      Order.V1,
      OrderAuthentication.OrderProofs(
        PublicKey.fromBase58String("7E9Za8v8aT6EyU1sX91CVK7tWUeAetnNYDxzKZsyjyKV").explicitGet(),
        Proofs(ByteStr.decodeBase58("2R6JfmNjEnbXAA6nt8YuCzSf1effDS4Wkz8owpCD9BdCNn864SnambTuwgLRYzzeP5CAsKHEviYKAJ2157vdr5Zq").get)
      ),
      PublicKey.fromBase58String("Fvk5DXmfyWVZqQVBowUBMwYtRAHDtdyZNNeRrwSjt6KP").explicitGet(),
      AssetPair.createAssetPair("WAVES", "9ZDWzK53XT5bixkmMwTJi2YzgxCqn5dUajXFcT2HcFDy").get,
      OrderType.SELL,
      TxExchangeAmount.unsafeFrom(3),
      TxOrderPrice.unsafeFrom(5000000000L),
      1526992336241L,
      1529584336241L,
      TxMatcherFee.unsafeFrom(2)
    )

    val tx = ExchangeTransaction
      .create(
        buy,
        sell,
        2,
        5000000000L,
        1,
        1,
        1,
        1526992336241L,
        Proofs(Seq(ByteStr.decodeBase58("5NxNhjMrrH5EWjSFnVnPbanpThic6fnNL48APVAkwq19y2FpQp4tNSqoAZgboC2ykUfqQs9suwBQj6wERmsWWNqa").get))
      )
      .explicitGet()

    // Same as above: no transaction version, type 3, chain id, and derived ids and addresses
    val js = Json.parse(s"""{
         "type":3,
         "id":"${tx.id()}",
         "sender":"${tx.sender.toAddress}",
         "senderPublicKey":"Fvk5DXmfyWVZqQVBowUBMwYtRAHDtdyZNNeRrwSjt6KP",
         "fee":1,
         "feeAssetId": null,
         "timestamp":1526992336241,
         "chainId":${tx.chainId},
         "proofs":["5NxNhjMrrH5EWjSFnVnPbanpThic6fnNL48APVAkwq19y2FpQp4tNSqoAZgboC2ykUfqQs9suwBQj6wERmsWWNqa"],
         "order1":{
            "version": 3,
            "id":"${buy.id()}",
            "sender":"${buy.sender.toAddress}",
            "senderPublicKey":"BqeJY8CP3PeUDaByz57iRekVUGtLxoow4XxPvXfHynaZ",
            "matcherPublicKey":"Fvk5DXmfyWVZqQVBowUBMwYtRAHDtdyZNNeRrwSjt6KP",
            "assetPair":{"amountAsset":null,"priceAsset":"9ZDWzK53XT5bixkmMwTJi2YzgxCqn5dUajXFcT2HcFDy"},
            "orderType":"buy",
            "price":6000000000,
            "amount":2,
            "timestamp":1526992336241,
            "expiration":1529584336241,
            "matcherFee":1,
            "matcherFeeAssetId":"9ZDWzK53XT5bixkmMwTJi2YzgxCqn5dUajXFcT2HcFDy",
            "signature":"2bkuGwECMFGyFqgoHV4q7GRRWBqYmBFWpYRkzgYANR4nN2twgrNaouRiZBqiK2RJzuo9NooB9iRiuZ4hypBbUQs",
            "proofs":["2bkuGwECMFGyFqgoHV4q7GRRWBqYmBFWpYRkzgYANR4nN2twgrNaouRiZBqiK2RJzuo9NooB9iRiuZ4hypBbUQs"]
         },
         "order2":{
            "version": 1,
            "id":"${sell.id()}",
            "sender":"${sell.sender.toAddress}",
            "senderPublicKey":"7E9Za8v8aT6EyU1sX91CVK7tWUeAetnNYDxzKZsyjyKV",
            "matcherPublicKey":"Fvk5DXmfyWVZqQVBowUBMwYtRAHDtdyZNNeRrwSjt6KP",
            "assetPair":{"amountAsset":null,"priceAsset":"9ZDWzK53XT5bixkmMwTJi2YzgxCqn5dUajXFcT2HcFDy"},
            "orderType":"sell",
            "price":5000000000,
            "amount":3,
            "timestamp":1526992336241,
            "expiration":1529584336241,
            "matcherFee":2,
            "signature":"2R6JfmNjEnbXAA6nt8YuCzSf1effDS4Wkz8owpCD9BdCNn864SnambTuwgLRYzzeP5CAsKHEviYKAJ2157vdr5Zq",
            "proofs":["2R6JfmNjEnbXAA6nt8YuCzSf1effDS4Wkz8owpCD9BdCNn864SnambTuwgLRYzzeP5CAsKHEviYKAJ2157vdr5Zq"]
         },
         "price":5000000000,
         "amount":2,
         "buyMatcherFee":1,
         "sellMatcherFee":1
      }
      """)

    js should matchJson(tx.json())
  }
}
