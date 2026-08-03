package tech.hearth.transaction.assets.exchange

import com.google.protobuf.ByteString
import tech.hearth.TestValues
import tech.hearth.account.AddressScheme
import tech.hearth.common.utils.Base58
import tech.hearth.common.utils.EitherExt2.*
import tech.hearth.protobuf.order.AssetPair as PBAssetPair
import tech.hearth.protobuf.transaction.{PBAmounts, PBOrder, PBOrders}
import tech.hearth.test.FlatSpec
import tech.hearth.transaction.Asset.Waves

class PBOrdersSpecification extends FlatSpec {
  // Order.sender is a plain field now, rather than a oneof of a public key and an ethereum signature
  private val protoOrder = PBOrder(
    chainId = AddressScheme.current.chainId.toInt,
    senderPublicKey = ByteString.copyFrom(TestValues.keyPair.publicKey),
    matcherPublicKey = ByteString.copyFrom(TestValues.keyPair.publicKey),
    assetPair = Some(PBAssetPair(PBAmounts.toPBAssetId(TestValues.asset), PBAmounts.toPBAssetId(Waves))),
    orderSide = PBOrder.Side.SELL,
    amount = 1000,
    price = 1000,
    timestamp = 1000,
    expiration = 10000,
    matcherFee = Some(PBAmounts.fromAssetAndAmount(Waves, 300000L)),
    version = 1,
    proofs = Nil
  )

  it should "validate asset pair" in {
    val doubleAssetPair = PBAssetPair(PBAmounts.toPBAssetId(TestValues.asset), PBAmounts.toPBAssetId(TestValues.asset))
    validate(protoOrder.withAssetPair(doubleAssetPair)).toEither shouldBe Left("Invalid AssetPair")
  }

  it should "validate expiration" in {
    validate(protoOrder.copy(expiration = -1)).toEither shouldBe Left("expiration should be > currentTime")
    validate(protoOrder.copy(expiration = 0)).toEither shouldBe Left("expiration should be > currentTime")
    validate(protoOrder.copy(expiration = protoOrder.timestamp + Order.MaxLiveTime + 1)).toEither shouldBe Left(
      "expiration should be earlier than 30 days"
    )
  }

  it should "validate side" in {
    val protoSellOrder = protoOrder.copy(orderSide = PBOrder.Side.SELL)
    val sellOrder      = PBOrders.vanilla(protoSellOrder).explicitGet()
    val protoBuyOrder  = protoOrder.copy(orderSide = PBOrder.Side.BUY)
    val buyOrder       = PBOrders.vanilla(protoBuyOrder).explicitGet()

    protoSellOrder.orderSide.isBuy shouldBe false
    protoSellOrder.orderSide.isSell shouldBe true
    protoBuyOrder.orderSide.isBuy shouldBe true
    protoBuyOrder.orderSide.isSell shouldBe false

    sellOrder.orderType shouldBe OrderType.SELL
    buyOrder.orderType shouldBe OrderType.BUY

    PBOrders.vanilla(protoOrder.copy(orderSide = PBOrder.Side.Unrecognized(123))) should beLeft
  }

  it should "validate version" in {
    validate(protoOrder.copy(version = 0)).toEither shouldBe Left("invalid version")
    validate(protoOrder.copy(version = 5)).toEither shouldBe Left("invalid version")
  }

  it should "validate proofs" in {
    validate(protoOrder.copy(proofs = Seq.fill[ByteString](10)(ByteString.EMPTY))).toEither shouldBe Left("Too many proofs (10), only 8 allowed")
    validate(protoOrder.copy(proofs = Seq(ByteString.copyFrom(new Array[Byte](65))))).toEither shouldBe Left(
      "Too large proof (65), must be max 64 bytes"
    )
  }

  it should "verify signature" in {
    val signed = PBOrders
      .vanilla(
        protoOrder.copy(
          proofs = Seq(ByteString.copyFrom(Base58.decode("4FbthG8Dq2kmkNykEdjawdXKQkwRFyD1rXQGCiFH8ThL5ZDceqM2USnRzCRiNwpQfXCwhNzS9c5RQ8wuQsSAxRSu")))
        )
      )
      .explicitGet()
    signed.firstProofIsValidSignatureAfterV6 shouldBe Symbol("right")

    val signedV4 = PBOrders
      .vanilla(
        protoOrder.copy(
          version = Order.V4,
          proofs = Seq(ByteString.copyFrom(Base58.decode("wx7L8EBk9hWmu9oZtFFWjpikJEin7TsXtiSv4K5Nw4g1pD86EEzpuV32hiaqoJECQCa9YuonprmrSqjA1rEKX5Z")))
        )
      )
      .explicitGet()

    signedV4.firstProofIsValidSignatureAfterV6 shouldBe Symbol("right")
  }

  it should "handle roundtrip" in {
    val vanilla                = PBOrders.vanilla(protoOrder).explicitGet()
    val reserializedProtoOrder = PBOrders.protobuf(vanilla)
    reserializedProtoOrder shouldBe protoOrder
  }

  private def validate(protoOrder: PBOrder): Validation = {
    val order = PBOrders.vanilla(protoOrder).explicitGet()
    order.isValid(order.timestamp)
  }
}
