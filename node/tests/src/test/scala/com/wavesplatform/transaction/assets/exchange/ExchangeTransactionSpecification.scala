package com.wavesplatform.transaction.assets.exchange

import com.wavesplatform.account.PublicKey
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2.*
import com.wavesplatform.lang.ValidationError
import com.wavesplatform.test.PropSpec
import com.wavesplatform.transaction.Asset.{IssuedAsset, Waves}
import com.wavesplatform.transaction.TxValidationError.{GenericError, OrderValidationError}
import com.wavesplatform.transaction.assets.exchange.AssetPair.extractAssetId
import com.wavesplatform.transaction.serialization.impl.PBTransactionSerializer
import com.wavesplatform.transaction.{Asset, Proofs, TxExchangeAmount, TxHelpers, TxMatcherFee, TxOrderPrice, TxVersion}
import com.wavesplatform.utils.JsonMatchers
import com.wavesplatform.NTPTime
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

      val (buyV, sellV, exchangeV) = versions

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
          timestamp: Long = expirationTimestamp - Order.MaxLiveTime,
          version: Byte = exchangeV
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
            timestamp = timestamp,
            version = version
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

      create(buyOrder = buy.copy(orderType = OrderType.SELL)) shouldBe Left(GenericError("order1 should have OrderType.BUY"))
      create(buyOrder = buy.copy(assetPair = buy.assetPair.copy(amountAsset = sell.assetPair.priceAsset))) shouldBe an[Left[?, ?]]
      create(buyOrder = buy.copy(expiration = 1L)) shouldBe an[Left[?, ?]]
      create(buyOrder = buy.copy(expiration = buy.expiration + 1)) shouldBe an[Left[?, ?]]
      create(buyOrder = buy.copy(matcherPublicKey = PublicKey(sender2.publicKey))) shouldBe an[Left[?, ?]]

      create(sellOrder = sell.copy(orderType = OrderType.BUY)) shouldBe Left(GenericError("sellOrder should has OrderType.SELL"))
      create(sellOrder = sell.copy(assetPair = sell.assetPair.copy(priceAsset = buy.assetPair.amountAsset))) shouldBe an[Left[?, ?]]
      create(sellOrder = sell.copy(expiration = 1L)) shouldBe an[Left[?, ?]]
      create(sellOrder = sell.copy(expiration = sell.expiration + 1)) shouldBe an[Left[?, ?]]
      create(sellOrder = sell.copy(matcherPublicKey = PublicKey(sender2.publicKey))) shouldBe an[Left[?, ?]]

      create(sellOrder = buy, buyOrder = sell) shouldBe Left(GenericError("order1 should have OrderType.BUY"))
      create(version = TxVersion.V3, sellOrder = buy, buyOrder = sell) shouldBe an[Right[?, ?]]
      create(version = TxVersion.V3, sellOrder = sell, buyOrder = sell) shouldBe Left(GenericError("buyOrder should has OrderType.BUY"))
      create(version = TxVersion.V3, sellOrder = buy, buyOrder = buy) shouldBe Left(GenericError("sellOrder should has OrderType.SELL"))

      create(
        buyOrder = buy.copy(assetPair = buy.assetPair.copy(amountAsset = Waves)),
        sellOrder = sell.copy(assetPair = sell.assetPair.copy(priceAsset = IssuedAsset(ByteStr(Array(1: Byte)))))
      ) shouldBe an[Left[?, ?]]
    }
  }

  def createExTx(buy: Order, sell: Order, price: Long, version: TxVersion): Either[ValidationError, ExchangeTransaction] = {
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
      timestamp = ntpTime.correctedTime(),
      version = version
    )
  }

  property("Test transaction with small amount and expired order") {

    forAll(preconditions) { case (sender1, sender2, matcher, pair, buyerMatcherFeeAssetId, sellerMatcherFeeAssetId, versions) =>
      val time                     = ntpTime.correctedTime()
      val expirationTimestamp      = time + Order.MaxLiveTime / 2
      val buyPrice                 = 1 * Order.PriceConstant
      val sellPrice                = (0.50 * Order.PriceConstant).toLong
      val matcherFee               = 300000L
      val (sellV, buyV, exchangeV) = versions

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

      createExTx(buy, sell, sellPrice, exchangeV) shouldBe an[Right[?, ?]]

      val sell1 =
        if (sellV == 3) {
          TxHelpers
            .sell(sellV, sender2, PublicKey(matcher.publicKey), pair, 1, buyPrice, time, time - 1, matcherFee, sellerMatcherFeeAssetId)
            .explicitGet()
        } else TxHelpers.sell(sellV, sender2, PublicKey(matcher.publicKey), pair, 1, buyPrice, time, time - 1, matcherFee).explicitGet()

      createExTx(buy, sell1, buyPrice, exchangeV) shouldBe Left(OrderValidationError(sell1, "expiration should be > currentTime"))
    }
  }

  property("JSON format validation") {
    val js = Json.parse("""{
         "version": 1,
         "type":7,
         "id":"d88651cf4a3f1ceef2f251912d245a5a7eb4731d756bc5fe385b497ac7648103",
         "sender":"3N22UCTvst8N1i1XDvGHzyqdgmZgwDKbp44",
         "senderPublicKey":"ddc81a3015b980628f204d30c3e1400626471de92e8271022292f48b11766716",
         "fee":1,
         "feeAssetId": null,
         "timestamp":1526992336241,
         "signature":"db003c16b5ca17661e989e4c61013a4c20e82d277407acdab261799a54f6e6f3f36ce1cfbe6364ebb0c7076b16a0845644bd352a339334fed738aac0491cf58d",
         "proofs":["db003c16b5ca17661e989e4c61013a4c20e82d277407acdab261799a54f6e6f3f36ce1cfbe6364ebb0c7076b16a0845644bd352a339334fed738aac0491cf58d"],
         "order1":{
            "version": 1,
            "id":"ca7fe30fa74f59e7e9a96cdc56e45b63b2ed049450ca5ab0926bd22fba27b464",
            "sender":"3MthkhReCHXeaPZcWXcT3fa6ey1XWptLtwj",
            "senderPublicKey":"a10aed0ecba98e825c9a7eeeca56765e167fbf007d8125c39726b49bed267a6e",
            "matcherPublicKey":"ddc81a3015b980628f204d30c3e1400626471de92e8271022292f48b11766716",
            "assetPair":{"amountAsset":null,"priceAsset":"7f1e3bff006ffd7f80fdb1a0f4008765faff2c8080ff01ff017f807fffff0100"},
            "orderType":"buy",
            "price":6000000000,
            "amount":2,
            "timestamp":1526992336241,
            "expiration":1529584336241,
            "matcherFee":1,
            "signature":"01610f3c35b77ded70b072052e695bba9f64573ec4fc18da1176c92e1b2ee52d2b247c91760d381fca3fae148d323088613c06df1d495524fb5ad25d3e4a3984",
            "proofs":["01610f3c35b77ded70b072052e695bba9f64573ec4fc18da1176c92e1b2ee52d2b247c91760d381fca3fae148d323088613c06df1d495524fb5ad25d3e4a3984"]
         },
         "order2":{
            "version": 1,
            "id":"b8bd20db2bc5a34d86e73c1f6e97eb162feac333d9f3628a7056c23dd5a063d0",
            "sender":"3MswjKzUBKCD6i1w4vCosQSbC8XzzdBx1mG",
            "senderPublicKey":"5c845a492f0442dc2436d2fc6ff81135ea2b0303fde95c73a8fcbb8a03104f60",
            "matcherPublicKey":"ddc81a3015b980628f204d30c3e1400626471de92e8271022292f48b11766716",
            "assetPair":{"amountAsset":null,"priceAsset":"7f1e3bff006ffd7f80fdb1a0f4008765faff2c8080ff01ff017f807fffff0100"},
            "orderType":"sell",
            "price":5000000000,
            "amount":3,
            "timestamp":1526992336241,
            "expiration":1529584336241,
            "matcherFee":2,
            "signature":"46cae51857cc0e12b61ec90473197d9b61c8dcbbf8023808b7c59398858088404b6e3148e1d479b49e22d6b7333ad3fe20fba572547b195a9ee343aa49e62388",
            "proofs":["46cae51857cc0e12b61ec90473197d9b61c8dcbbf8023808b7c59398858088404b6e3148e1d479b49e22d6b7333ad3fe20fba572547b195a9ee343aa49e62388"]
         },
         "price":5000000000,
         "amount":2,
         "buyMatcherFee":1,
         "sellMatcherFee":1
      }
      """)

    val buy = Order(
      Order.V1,
      OrderAuthentication.OrderProofs(
        PublicKey.fromBase16String("a10aed0ecba98e825c9a7eeeca56765e167fbf007d8125c39726b49bed267a6e").explicitGet(),
        Proofs(
          ByteStr
            .decodeBase16(
              "01610f3c35b77ded70b072052e695bba9f64573ec4fc18da1176c92e1b2ee52d2b247c91760d381fca3fae148d323088613c06df1d495524fb5ad25d3e4a3984"
            )
            .get
        )
      ),
      PublicKey.fromBase16String("ddc81a3015b980628f204d30c3e1400626471de92e8271022292f48b11766716").explicitGet(),
      AssetPair.createAssetPair("WAVES", "7f1e3bff006ffd7f80fdb1a0f4008765faff2c8080ff01ff017f807fffff0100").get,
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
        PublicKey.fromBase16String("5c845a492f0442dc2436d2fc6ff81135ea2b0303fde95c73a8fcbb8a03104f60").explicitGet(),
        Proofs(
          ByteStr
            .decodeBase16(
              "46cae51857cc0e12b61ec90473197d9b61c8dcbbf8023808b7c59398858088404b6e3148e1d479b49e22d6b7333ad3fe20fba572547b195a9ee343aa49e62388"
            )
            .get
        )
      ),
      PublicKey.fromBase16String("ddc81a3015b980628f204d30c3e1400626471de92e8271022292f48b11766716").explicitGet(),
      AssetPair.createAssetPair("WAVES", "7f1e3bff006ffd7f80fdb1a0f4008765faff2c8080ff01ff017f807fffff0100").get,
      OrderType.SELL,
      TxExchangeAmount.unsafeFrom(3),
      TxOrderPrice.unsafeFrom(5000000000L),
      1526992336241L,
      1529584336241L,
      TxMatcherFee.unsafeFrom(2)
    )

    val tx = ExchangeTransaction
      .create(
        TxVersion.V1,
        buy,
        sell,
        2,
        5000000000L,
        1,
        1,
        1,
        1526992336241L,
        Proofs(
          ByteStr
            .decodeBase16(
              "db003c16b5ca17661e989e4c61013a4c20e82d277407acdab261799a54f6e6f3f36ce1cfbe6364ebb0c7076b16a0845644bd352a339334fed738aac0491cf58d"
            )
            .get
        )
      )
      .explicitGet()

    js should matchJson(tx.json())
  }

  property("JSON format validation V2") {
    val js = Json.parse("""{
         "version": 2,
         "type":7,
         "id":"4029fa0d15e14522b5708ac6625aa79fa580f0e5e81a10296522d25f259c21df",
         "sender":"3N22UCTvst8N1i1XDvGHzyqdgmZgwDKbp44",
         "senderPublicKey":"ddc81a3015b980628f204d30c3e1400626471de92e8271022292f48b11766716",
         "fee":1,
         "feeAssetId": null,
         "timestamp":1526992336241,
         "proofs":["db003c16b5ca17661e989e4c61013a4c20e82d277407acdab261799a54f6e6f3f36ce1cfbe6364ebb0c7076b16a0845644bd352a339334fed738aac0491cf58d"],
         "order1":{
            "version": 2,
            "id":"ca52d9d6f78d7808a9df1a304087bbf389c1be33f84350077faa51854603db44",
            "sender":"3MthkhReCHXeaPZcWXcT3fa6ey1XWptLtwj",
            "senderPublicKey":"a10aed0ecba98e825c9a7eeeca56765e167fbf007d8125c39726b49bed267a6e",
            "matcherPublicKey":"ddc81a3015b980628f204d30c3e1400626471de92e8271022292f48b11766716",
            "assetPair":{"amountAsset":null,"priceAsset":"7f1e3bff006ffd7f80fdb1a0f4008765faff2c8080ff01ff017f807fffff0100"},
            "orderType":"buy",
            "price":6000000000,
            "amount":2,
            "timestamp":1526992336241,
            "expiration":1529584336241,
            "matcherFee":1,
            "signature":"01610f3c35b77ded70b072052e695bba9f64573ec4fc18da1176c92e1b2ee52d2b247c91760d381fca3fae148d323088613c06df1d495524fb5ad25d3e4a3984",
            "proofs":["01610f3c35b77ded70b072052e695bba9f64573ec4fc18da1176c92e1b2ee52d2b247c91760d381fca3fae148d323088613c06df1d495524fb5ad25d3e4a3984"]
         },
         "order2":{
            "version": 1,
            "id":"b8bd20db2bc5a34d86e73c1f6e97eb162feac333d9f3628a7056c23dd5a063d0",
            "sender":"3MswjKzUBKCD6i1w4vCosQSbC8XzzdBx1mG",
            "senderPublicKey":"5c845a492f0442dc2436d2fc6ff81135ea2b0303fde95c73a8fcbb8a03104f60",
            "matcherPublicKey":"ddc81a3015b980628f204d30c3e1400626471de92e8271022292f48b11766716",
            "assetPair":{"amountAsset":null,"priceAsset":"7f1e3bff006ffd7f80fdb1a0f4008765faff2c8080ff01ff017f807fffff0100"},
            "orderType":"sell",
            "price":5000000000,
            "amount":3,
            "timestamp":1526992336241,
            "expiration":1529584336241,
            "matcherFee":2,
            "signature":"46cae51857cc0e12b61ec90473197d9b61c8dcbbf8023808b7c59398858088404b6e3148e1d479b49e22d6b7333ad3fe20fba572547b195a9ee343aa49e62388",
            "proofs":["46cae51857cc0e12b61ec90473197d9b61c8dcbbf8023808b7c59398858088404b6e3148e1d479b49e22d6b7333ad3fe20fba572547b195a9ee343aa49e62388"]
         },
         "price":5000000000,
         "amount":2,
         "buyMatcherFee":1,
         "sellMatcherFee":1
      }
      """)

    val buy = Order(
      Order.V2,
      OrderAuthentication.OrderProofs(
        PublicKey.fromBase16String("a10aed0ecba98e825c9a7eeeca56765e167fbf007d8125c39726b49bed267a6e").explicitGet(),
        Proofs(
          ByteStr
            .decodeBase16(
              "01610f3c35b77ded70b072052e695bba9f64573ec4fc18da1176c92e1b2ee52d2b247c91760d381fca3fae148d323088613c06df1d495524fb5ad25d3e4a3984"
            )
            .get
        )
      ),
      PublicKey.fromBase16String("ddc81a3015b980628f204d30c3e1400626471de92e8271022292f48b11766716").explicitGet(),
      AssetPair.createAssetPair("WAVES", "7f1e3bff006ffd7f80fdb1a0f4008765faff2c8080ff01ff017f807fffff0100").get,
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
        PublicKey.fromBase16String("5c845a492f0442dc2436d2fc6ff81135ea2b0303fde95c73a8fcbb8a03104f60").explicitGet(),
        Proofs(
          ByteStr
            .decodeBase16(
              "46cae51857cc0e12b61ec90473197d9b61c8dcbbf8023808b7c59398858088404b6e3148e1d479b49e22d6b7333ad3fe20fba572547b195a9ee343aa49e62388"
            )
            .get
        )
      ),
      PublicKey.fromBase16String("ddc81a3015b980628f204d30c3e1400626471de92e8271022292f48b11766716").explicitGet(),
      AssetPair.createAssetPair("WAVES", "7f1e3bff006ffd7f80fdb1a0f4008765faff2c8080ff01ff017f807fffff0100").get,
      OrderType.SELL,
      TxExchangeAmount.unsafeFrom(3),
      TxOrderPrice.unsafeFrom(5000000000L),
      1526992336241L,
      1529584336241L,
      TxMatcherFee.unsafeFrom(2)
    )

    val tx = ExchangeTransaction
      .create(
        TxVersion.V2,
        buy,
        sell,
        2,
        5000000000L,
        1,
        1,
        1,
        1526992336241L,
        Proofs(
          Seq(
            ByteStr
              .decodeBase16(
                "db003c16b5ca17661e989e4c61013a4c20e82d277407acdab261799a54f6e6f3f36ce1cfbe6364ebb0c7076b16a0845644bd352a339334fed738aac0491cf58d"
              )
              .get
          )
        )
      )
      .explicitGet()

    js should matchJson(tx.json())
  }

  property("JSON format validation V2 OrderV3") {
    val js = Json.parse("""{
         "version": 2,
         "type":7,
         "id":"218f9ccd79fd5487d74171ac12f25c62914f7b2dbf00d5c5cff3ea8e19550018",
         "sender":"3N22UCTvst8N1i1XDvGHzyqdgmZgwDKbp44",
         "senderPublicKey":"ddc81a3015b980628f204d30c3e1400626471de92e8271022292f48b11766716",
         "fee":1,
         "feeAssetId": null,
         "timestamp":1526992336241,
         "proofs":["db003c16b5ca17661e989e4c61013a4c20e82d277407acdab261799a54f6e6f3f36ce1cfbe6364ebb0c7076b16a0845644bd352a339334fed738aac0491cf58d"],
         "order1":{
            "version": 3,
            "id":"6cc32091a0eeef4a5c7b0e1a81901780cd8189f94dc647d63ea7db7454e7ad77",
            "sender":"3MthkhReCHXeaPZcWXcT3fa6ey1XWptLtwj",
            "senderPublicKey":"a10aed0ecba98e825c9a7eeeca56765e167fbf007d8125c39726b49bed267a6e",
            "matcherPublicKey":"ddc81a3015b980628f204d30c3e1400626471de92e8271022292f48b11766716",
            "assetPair":{"amountAsset":null,"priceAsset":"7f1e3bff006ffd7f80fdb1a0f4008765faff2c8080ff01ff017f807fffff0100"},
            "orderType":"buy",
            "price":6000000000,
            "amount":2,
            "timestamp":1526992336241,
            "expiration":1529584336241,
            "matcherFee":1,
            "matcherFeeAssetId":"7f1e3bff006ffd7f80fdb1a0f4008765faff2c8080ff01ff017f807fffff0100",
            "signature":"01610f3c35b77ded70b072052e695bba9f64573ec4fc18da1176c92e1b2ee52d2b247c91760d381fca3fae148d323088613c06df1d495524fb5ad25d3e4a3984",
            "proofs":["01610f3c35b77ded70b072052e695bba9f64573ec4fc18da1176c92e1b2ee52d2b247c91760d381fca3fae148d323088613c06df1d495524fb5ad25d3e4a3984"]
         },
         "order2":{
            "version": 1,
            "id":"b8bd20db2bc5a34d86e73c1f6e97eb162feac333d9f3628a7056c23dd5a063d0",
            "sender":"3MswjKzUBKCD6i1w4vCosQSbC8XzzdBx1mG",
            "senderPublicKey":"5c845a492f0442dc2436d2fc6ff81135ea2b0303fde95c73a8fcbb8a03104f60",
            "matcherPublicKey":"ddc81a3015b980628f204d30c3e1400626471de92e8271022292f48b11766716",
            "assetPair":{"amountAsset":null,"priceAsset":"7f1e3bff006ffd7f80fdb1a0f4008765faff2c8080ff01ff017f807fffff0100"},
            "orderType":"sell",
            "price":5000000000,
            "amount":3,
            "timestamp":1526992336241,
            "expiration":1529584336241,
            "matcherFee":2,
            "signature":"46cae51857cc0e12b61ec90473197d9b61c8dcbbf8023808b7c59398858088404b6e3148e1d479b49e22d6b7333ad3fe20fba572547b195a9ee343aa49e62388",
            "proofs":["46cae51857cc0e12b61ec90473197d9b61c8dcbbf8023808b7c59398858088404b6e3148e1d479b49e22d6b7333ad3fe20fba572547b195a9ee343aa49e62388"]
         },
         "price":5000000000,
         "amount":2,
         "buyMatcherFee":1,
         "sellMatcherFee":1
      }
      """)

    val buy = Order(
      Order.V3,
      OrderAuthentication.OrderProofs(
        PublicKey.fromBase16String("a10aed0ecba98e825c9a7eeeca56765e167fbf007d8125c39726b49bed267a6e").explicitGet(),
        Proofs(
          ByteStr
            .decodeBase16(
              "01610f3c35b77ded70b072052e695bba9f64573ec4fc18da1176c92e1b2ee52d2b247c91760d381fca3fae148d323088613c06df1d495524fb5ad25d3e4a3984"
            )
            .get
        )
      ),
      PublicKey.fromBase16String("ddc81a3015b980628f204d30c3e1400626471de92e8271022292f48b11766716").explicitGet(),
      AssetPair.createAssetPair("WAVES", "7f1e3bff006ffd7f80fdb1a0f4008765faff2c8080ff01ff017f807fffff0100").get,
      OrderType.BUY,
      TxExchangeAmount.unsafeFrom(2),
      TxOrderPrice.unsafeFrom(6000000000L),
      1526992336241L,
      1529584336241L,
      TxMatcherFee.unsafeFrom(1),
      extractAssetId("7f1e3bff006ffd7f80fdb1a0f4008765faff2c8080ff01ff017f807fffff0100").get
    )

    val sell = Order(
      Order.V1,
      OrderAuthentication.OrderProofs(
        PublicKey.fromBase16String("5c845a492f0442dc2436d2fc6ff81135ea2b0303fde95c73a8fcbb8a03104f60").explicitGet(),
        Proofs(
          ByteStr
            .decodeBase16(
              "46cae51857cc0e12b61ec90473197d9b61c8dcbbf8023808b7c59398858088404b6e3148e1d479b49e22d6b7333ad3fe20fba572547b195a9ee343aa49e62388"
            )
            .get
        )
      ),
      PublicKey.fromBase16String("ddc81a3015b980628f204d30c3e1400626471de92e8271022292f48b11766716").explicitGet(),
      AssetPair.createAssetPair("WAVES", "7f1e3bff006ffd7f80fdb1a0f4008765faff2c8080ff01ff017f807fffff0100").get,
      OrderType.SELL,
      TxExchangeAmount.unsafeFrom(3),
      TxOrderPrice.unsafeFrom(5000000000L),
      1526992336241L,
      1529584336241L,
      TxMatcherFee.unsafeFrom(2)
    )

    val tx = ExchangeTransaction
      .create(
        TxVersion.V2,
        buy,
        sell,
        2,
        5000000000L,
        1,
        1,
        1,
        1526992336241L,
        Proofs(
          Seq(
            ByteStr
              .decodeBase16(
                "db003c16b5ca17661e989e4c61013a4c20e82d277407acdab261799a54f6e6f3f36ce1cfbe6364ebb0c7076b16a0845644bd352a339334fed738aac0491cf58d"
              )
              .get
          )
        )
      )
      .explicitGet()

    js should matchJson(tx.json())
  }
}
