package tech.hearth.it.sync.utils

import tech.hearth.account.{Address, AddressScheme, PublicKey}
import tech.hearth.common.state.ByteStr
import tech.hearth.common.utils.EitherExt2.*
import tech.hearth.it.api.SyncHttpApi.*
import tech.hearth.it.sync.*
import tech.hearth.it.transactions.BaseTransactionSuite
import tech.hearth.test.*
import tech.hearth.transaction.Asset.Hearth
import tech.hearth.transaction.assets.exchange.*
import tech.hearth.transaction.lease.{LeaseCancelTransaction, LeaseTransaction}
import tech.hearth.transaction.transfer.TransferTransaction
import tech.hearth.transaction.transfer.TransferTransaction.{ParsedTransfer, Transfer}
import tech.hearth.transaction.{Proofs, TxExchangeAmount, TxMatcherFee, TxNonNegativeAmount, TxOrderPrice}
import org.scalatest.Informing
import org.scalatest.prop.TableDrivenPropertyChecks

class TransactionSerializeSuite extends BaseTransactionSuite with TableDrivenPropertyChecks with Informing {
  private val publicKey         = PublicKey.fromBase16String("d528aabec35ca100d87c7b7a128632faf19cd44531819457445113a32a21ef22").explicitGet()
  private val ts: Long          = 1526287561757L
  private val tsOrderFrom: Long = 1526992336241L
  private val tsOrderTo: Long   = 1529584336241L

  private lazy val buyV2 = Order(
    Order.V2,
    OrderAuthentication(PublicKey.fromBase16String("a10aed0ecba98e825c9a7eeeca56765e167fbf007d8125c39726b49bed267a6e").explicitGet()),
    PublicKey.fromBase16String("ddc81a3015b980628f204d30c3e1400626471de92e8271022292f48b11766716").explicitGet(),
    AssetPair.createAssetPair("HRTH", "7f1e3bff006ffd7f80fdb1a0f4008765faff2c8080ff01ff017f807fffff0100").get,
    OrderType.BUY,
    TxExchangeAmount.unsafeFrom(2),
    TxOrderPrice.unsafeFrom(60.hearth),
    tsOrderFrom,
    tsOrderTo,
    TxMatcherFee.unsafeFrom(1)
  )

  private lazy val sell = Order(
    Order.V1,
    OrderAuthentication(PublicKey.fromBase16String("5c845a492f0442dc2436d2fc6ff81135ea2b0303fde95c73a8fcbb8a03104f60").explicitGet()),
    PublicKey.fromBase16String("ddc81a3015b980628f204d30c3e1400626471de92e8271022292f48b11766716").explicitGet(),
    AssetPair.createAssetPair("HRTH", "7f1e3bff006ffd7f80fdb1a0f4008765faff2c8080ff01ff017f807fffff0100").get,
    OrderType.SELL,
    TxExchangeAmount.unsafeFrom(3),
    TxOrderPrice.unsafeFrom(50.hearth),
    tsOrderFrom,
    tsOrderTo,
    TxMatcherFee.unsafeFrom(2)
  )

  private lazy val exchange = ExchangeTransaction
    .create(
      buyV2,
      sell,
      2,
      50.hearth,
      1,
      1,
      1,
      tsOrderFrom,
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

  private lazy val leasecancel = LeaseCancelTransaction
    .create(
      publicKey,
      ByteStr.decodeBase16("b6c8c0ec67cb74ea16339e5cba54e274310234597b193fa49035e1013b205dc7").get,
      minFee,
      ts,
      Proofs(
        Seq(
          ByteStr
            .decodeBase16(
              "86982ec3897bc1d6461c58bca6378a584fd3fb1186f125b1a89f87c2dc77316c5536054de029d14fb2d512582e4b988a5dbfb3a47acb779cec925bc625b5598f"
            )
            .get
        )
      )
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
      Proofs(
        Seq(
          ByteStr
            .decodeBase16(
              "d4ded0a3a798decf46459c701e03b6db01cc2c93d1445f6973a3cb5172247f89997a9729cec3d3f288a5a46484434c012e99ed76b1abfa3c31bee1097afc9c80"
            )
            .get
        )
      )
    )
    .explicitGet()

  private lazy val mass = TransferTransaction
    .create(
      publicKey,
      Hearth,
      TransferTransaction
        .parseTransfersList(List(Transfer(firstKeyPair.toAddress.toString, 1.hearth), Transfer(secondKeyPair.toAddress.toString, 2.hearth)))
        .explicitGet(),
      2.hearth,
      ts,
      ByteStr.decodeBase16("6d617373706179").get,
      Proofs(
        Seq(
          ByteStr
            .decodeBase16(
              "0c863b41d8c03da0d9c07a645c120477b5d0644fc4ee2862fffbf7462cdda96d9a9693340d6249e8f7322ce39c61b781bcb271e3d5efdae0938083081088b289"
            )
            .get
        )
      )
    )
    .explicitGet()

  private lazy val recipient = Address.fromString(sender.address).explicitGet()
  private lazy val transfer = TransferTransaction
    .create(
      publicKey,
      Hearth,
      Seq(ParsedTransfer(recipient, TxNonNegativeAmount.unsafeFrom(100000000))),
      minFee,
      ts,
      ByteStr.empty,
      Proofs(
        Seq(
          ByteStr
            .decodeBase16(
              "b3f084c843db00e0c71e7786ce28ffc68111a3a579b924bd1989eae601ae6ced7edbd62d605b073e57146db283792ae497313f472d6d4adc871954ea3ff1738f"
            )
            .get
        )
      )
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
