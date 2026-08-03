package tech.hearth.it.sync.utils

import tech.hearth.account.{Address, AddressScheme, PublicKey}
import tech.hearth.common.state.ByteStr
import tech.hearth.common.utils.EitherExt2.*
import tech.hearth.it.api.SyncHttpApi.*
import tech.hearth.it.sync.*
import tech.hearth.it.transactions.BaseTransactionSuite
import tech.hearth.test.*
import tech.hearth.transaction.Asset.Waves
import tech.hearth.transaction.assets.exchange.*
import tech.hearth.transaction.lease.{LeaseCancelTransaction, LeaseTransaction}
import tech.hearth.transaction.transfer.MassTransferTransaction.Transfer
import tech.hearth.transaction.transfer.{MassTransferTransaction, TransferTransaction}
import tech.hearth.transaction.{Proofs, TxExchangeAmount, TxMatcherFee, TxOrderPrice}
import org.scalatest.Informing
import org.scalatest.prop.TableDrivenPropertyChecks

class TransactionSerializeSuite extends BaseTransactionSuite with TableDrivenPropertyChecks with Informing {
  private val publicKey         = PublicKey.fromBase58String("FM5ojNqW7e9cZ9zhPYGkpSP1Pcd8Z3e3MNKYVS5pGJ8Z").explicitGet()
  private val ts: Long          = 1526287561757L
  private val tsOrderFrom: Long = 1526992336241L
  private val tsOrderTo: Long   = 1529584336241L

  private lazy val buyV2 = Order(
    Order.V2,
    OrderAuthentication(PublicKey.fromBase58String("BqeJY8CP3PeUDaByz57iRekVUGtLxoow4XxPvXfHynaZ").explicitGet()),
    PublicKey.fromBase58String("Fvk5DXmfyWVZqQVBowUBMwYtRAHDtdyZNNeRrwSjt6KP").explicitGet(),
    AssetPair.createAssetPair("WAVES", "9ZDWzK53XT5bixkmMwTJi2YzgxCqn5dUajXFcT2HcFDy").get,
    OrderType.BUY,
    TxExchangeAmount.unsafeFrom(2),
    TxOrderPrice.unsafeFrom(60.waves),
    tsOrderFrom,
    tsOrderTo,
    TxMatcherFee.unsafeFrom(1)
  )

  private lazy val sell = Order(
    Order.V1,
    OrderAuthentication(PublicKey.fromBase58String("7E9Za8v8aT6EyU1sX91CVK7tWUeAetnNYDxzKZsyjyKV").explicitGet()),
    PublicKey.fromBase58String("Fvk5DXmfyWVZqQVBowUBMwYtRAHDtdyZNNeRrwSjt6KP").explicitGet(),
    AssetPair.createAssetPair("WAVES", "9ZDWzK53XT5bixkmMwTJi2YzgxCqn5dUajXFcT2HcFDy").get,
    OrderType.SELL,
    TxExchangeAmount.unsafeFrom(3),
    TxOrderPrice.unsafeFrom(50.waves),
    tsOrderFrom,
    tsOrderTo,
    TxMatcherFee.unsafeFrom(2)
  )

  private lazy val exchange = ExchangeTransaction
    .create(
      buyV2,
      sell,
      2,
      50.waves,
      1,
      1,
      1,
      tsOrderFrom,
      Proofs(Seq(ByteStr.decodeBase58("5NxNhjMrrH5EWjSFnVnPbanpThic6fnNL48APVAkwq19y2FpQp4tNSqoAZgboC2ykUfqQs9suwBQj6wERmsWWNqa").get))
    )
    .explicitGet()

  private lazy val leasecancel = LeaseCancelTransaction
    .create(
      publicKey,
      ByteStr.decodeBase58("DJWkQxRyJNqWhq9qSQpK2D4tsrct6eZbjSv3AH4PSha6").get,
      minFee,
      ts,
      Proofs(Seq(ByteStr.decodeBase58("3h5SQLbCzaLoTHUeoCjXUHB6qhNUfHZjQQVsWTRAgTGMEdK5aeULMVUfDq63J56kkHJiviYTDT92bLGc8ELrUgvi").get))
    )
    .explicitGet()

  private lazy val lease = LeaseTransaction
    .create(
      AddressScheme.current.chainId,
      publicKey,
      Address.fromString(sender.address).explicitGet(),
      10000000,
      minFee,
      ts,
      Proofs(Seq(ByteStr.decodeBase58("5Fr3yLwvfKGDsFLi8A8JbHqToHDojrPbdEGx9mrwbeVWWoiDY5pRqS3rcX1rXC9ud52vuxVdBmGyGk5krcgwFu9q").get))
    )
    .explicitGet()

  private lazy val mass = MassTransferTransaction
    .create(
      publicKey,
      Waves,
      MassTransferTransaction
        .parseTransfersList(List(Transfer(firstKeyPair.toAddress.toString, 1.waves), Transfer(secondKeyPair.toAddress.toString, 2.waves)))
        .explicitGet(),
      2.waves,
      ts,
      ByteStr.decodeBase58("59QuUcqP6p").get,
      Proofs(Seq(ByteStr.decodeBase58("FXMNu3ecy5zBjn9b69VtpuYRwxjCbxdkZ3xZpLzB8ZeFDvcgTkmEDrD29wtGYRPtyLS3LPYrL2d5UM6TpFBMUGQ").get))
    )
    .explicitGet()

  private lazy val recipient = Address.fromString(sender.address).explicitGet()
  private lazy val transfer = TransferTransaction
    .create(
      publicKey,
      recipient,
      Waves,
      100000000,
      Waves,
      minFee,
      ByteStr.empty,
      ts,
      Proofs(Seq(ByteStr.decodeBase58("4bfDaqBcnK3hT8ywFEFndxtS1DTSYfncUqd4s5Vyaa66PZHawtC73rDswUur6QZu5RpqM7L9NFgBHT1vhCoox4vi").get))
    )
    .explicitGet()

  test("serialize transactions") {
    forAll(
      Table(
        ("tx", "name"),
        (exchange, "exchange"),
        (leasecancel, "leasecancel"),
        (lease, "lease"),
        (mass, "mass"),
        (transfer, "transfer")
      )
    ) { (tx, name) =>
      info(name)
      val r = sender.transactionSerializer(tx.json()).bytes.map(_.toByte)
      r shouldBe tx.bodyBytes()
    }
  }

}
