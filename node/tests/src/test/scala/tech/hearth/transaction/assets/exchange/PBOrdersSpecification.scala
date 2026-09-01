package tech.hearth.transaction.assets.exchange

import com.google.protobuf.ByteString
import tech.hearth.TestValues
import tech.hearth.account.NetworkId
import tech.hearth.common.utils.Base16
import tech.hearth.common.utils.EitherExt2.*
import tech.hearth.protobuf.order.AssetPair as PBAssetPair
import tech.hearth.protobuf.transaction.{PBAmounts, PBOrder, PBOrders}
import tech.hearth.test.FlatSpec
import tech.hearth.transaction.Asset.Hearth

class PBOrdersSpecification extends FlatSpec {
  // Order.sender is a plain field now, rather than a oneof of a public key and an ethereum signature
  private val protoOrder = PBOrder(
    networkId = NetworkId.current.value,
    senderPublicKey = ByteString.copyFrom(TestValues.keyPair.publicKey),
    matcherPublicKey = ByteString.copyFrom(TestValues.keyPair.publicKey),
    assetPair = Some(PBAssetPair(PBAmounts.toPBAssetId(TestValues.asset), PBAmounts.toPBAssetId(Hearth))),
    orderSide = PBOrder.Side.SELL,
    amount = 1000,
    price = 1000,
    timestamp = 1000,
    expiration = 10000,
    matcherFee = Some(PBAmounts.fromAssetAndAmount(Hearth, 300000L)),
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
          proofs = Seq(
            ByteString.copyFrom(
              Base16.decode(
                "2b7c933627252ea5e8cd1ad840f32b8e33da0565690c2465d63edea13ecebb90f907c0f0658346f40fbf39dfe0ee084208a81a8d8970d5c66dba0c848f853a05"
              )
            )
          )
        )
      )
      .explicitGet()
    signed.firstProofIsValidSignatureAfterV6 shouldBe Symbol("right")

    val signedV4 = PBOrders
      .vanilla(
        protoOrder.copy(
          version = Order.V4,
          proofs = Seq(
            ByteString.copyFrom(
              Base16.decode(
                "d00c480210fccb2dfc03ac9661fc2d4253738c0b2830044d2b1696c63271a8402b05bc41cae6fc5e149db94dd9e390ca3abe6f20294de432a75d65d391661e07"
              )
            )
          )
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
