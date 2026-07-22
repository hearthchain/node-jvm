package com.wavesplatform.it.sync.utils

import com.google.protobuf.ByteString
import com.wavesplatform.account.{Address, AddressScheme, PublicKey}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2.*
import com.wavesplatform.it.api.SyncHttpApi.*
import com.wavesplatform.it.sync.*
import com.wavesplatform.it.transactions.BaseTransactionSuite
import com.wavesplatform.lang.script.Script
import com.wavesplatform.lang.v1.FunctionHeader
import com.wavesplatform.lang.v1.compiler.Terms
import com.wavesplatform.lang.v1.compiler.Terms.TRUE
import com.wavesplatform.state.{BinaryDataEntry, BooleanDataEntry, IntegerDataEntry}
import com.wavesplatform.test.*
import com.wavesplatform.transaction.Asset.{IssuedAsset, Waves}
import com.wavesplatform.transaction.assets.*
import com.wavesplatform.transaction.assets.exchange.*
import com.wavesplatform.transaction.lease.{LeaseCancelTransaction, LeaseTransaction}
import com.wavesplatform.transaction.smart.{InvokeScriptTransaction, SetScriptTransaction}
import com.wavesplatform.transaction.transfer.MassTransferTransaction.Transfer
import com.wavesplatform.transaction.transfer.{MassTransferTransaction, TransferTransaction}
import com.wavesplatform.transaction.{
  CreateAliasTransaction,
  DataTransaction,
  Proofs,
  Transaction,
  TxDecimals,
  TxExchangeAmount,
  TxMatcherFee,
  TxOrderPrice,
  TxPositiveAmount,
  TxVersion
}
import org.scalatest.Informing
import org.scalatest.prop.TableDrivenPropertyChecks

class TransactionSerializeSuite extends BaseTransactionSuite with TableDrivenPropertyChecks with Informing {
  private val publicKey         = PublicKey.fromBase16String("d528aabec35ca100d87c7b7a128632faf19cd44531819457445113a32a21ef22").explicitGet()
  private val ts: Long          = 1526287561757L
  private val tsOrderFrom: Long = 1526992336241L
  private val tsOrderTo: Long   = 1529584336241L

  private lazy val buyV2 = Order(
    TxVersion.V2,
    OrderAuthentication(PublicKey.fromBase16String("a10aed0ecba98e825c9a7eeeca56765e167fbf007d8125c39726b49bed267a6e").explicitGet()),
    PublicKey.fromBase16String("ddc81a3015b980628f204d30c3e1400626471de92e8271022292f48b11766716").explicitGet(),
    AssetPair.createAssetPair("WAVES", "7f1e3bff006ffd7f80fdb1a0f4008765faff2c8080ff01ff017f807fffff0100").get,
    OrderType.BUY,
    TxExchangeAmount.unsafeFrom(2),
    TxOrderPrice.unsafeFrom(60.waves),
    tsOrderFrom,
    tsOrderTo,
    TxMatcherFee.unsafeFrom(1)
  )

  private lazy val buyV1 = Order(
    TxVersion.V1,
    OrderAuthentication(PublicKey.fromBase16String("a10aed0ecba98e825c9a7eeeca56765e167fbf007d8125c39726b49bed267a6e").explicitGet()),
    PublicKey.fromBase16String("ddc81a3015b980628f204d30c3e1400626471de92e8271022292f48b11766716").explicitGet(),
    AssetPair.createAssetPair("WAVES", "7f1e3bff006ffd7f80fdb1a0f4008765faff2c8080ff01ff017f807fffff0100").get,
    OrderType.BUY,
    TxExchangeAmount.unsafeFrom(2),
    TxOrderPrice.unsafeFrom(60.waves),
    tsOrderFrom,
    tsOrderTo,
    TxMatcherFee.unsafeFrom(1)
  )

  private lazy val sell = Order(
    TxVersion.V1,
    OrderAuthentication(PublicKey.fromBase16String("5c845a492f0442dc2436d2fc6ff81135ea2b0303fde95c73a8fcbb8a03104f60").explicitGet()),
    PublicKey.fromBase16String("ddc81a3015b980628f204d30c3e1400626471de92e8271022292f48b11766716").explicitGet(),
    AssetPair.createAssetPair("WAVES", "7f1e3bff006ffd7f80fdb1a0f4008765faff2c8080ff01ff017f807fffff0100").get,
    OrderType.SELL,
    TxExchangeAmount.unsafeFrom(3),
    TxOrderPrice.unsafeFrom(50.waves),
    tsOrderFrom,
    tsOrderTo,
    TxMatcherFee.unsafeFrom(2)
  )

  private lazy val exV1 = ExchangeTransaction
    .create(
      TxVersion.V1,
      buyV1,
      sell,
      2,
      50.waves,
      1,
      1,
      1,
      tsOrderFrom,
      Proofs(
        ByteStr
          .decodeBase16(
            "db003c16b5ca17661e989e4c61013a4c20e82d277407acdab261799a54f6e6f3f36ce1cfbe6364ebb0c7076b16a0845644bd352a339334fed738aac0491cf58d"
          )
          .get
      )
    )
    .explicitGet()

  private lazy val exV2 = ExchangeTransaction
    .create(
      TxVersion.V2,
      buyV2,
      sell,
      2,
      50.waves,
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

  private lazy val burnV1 = BurnTransaction
    .create(
      1.toByte,
      publicKey,
      IssuedAsset(ByteStr.decodeBase16("808912576b218e0e1d400e485dfca793c177ddfdbeccc776715710b4114ffcf9").get),
      10000000000L,
      burnFee,
      ts,
      Proofs(
        ByteStr
          .decodeBase16(
            "2d5879ade04e17578da8d2ec810e56b52809ef2944e4767cae4bd9046770fdc5463ef1978a66a1f36979360579085b1637219a065c918e0316f23f6fa30d2589"
          )
          .get
      )
    )
    .explicitGet()

  private lazy val burnV2 = BurnTransaction
    .create(
      2.toByte,
      publicKey,
      IssuedAsset(ByteStr.decodeBase16("808912576b218e0e1d400e485dfca793c177ddfdbeccc776715710b4114ffcf9").get),
      10000000000L,
      burnFee,
      ts,
      Proofs(
        Seq(
          ByteStr
            .decodeBase16(
              "76aae510e4dd0b54dd1da7ef741e1ea08a118c54ae7ecf5e920781fe747a75c3ad4f68f9a3592ed0f440eb3ad215910e0b45bb2d0a309ed5c461e114ba431280"
            )
            .get
        )
      )
    )
    .explicitGet()

  private lazy val aliasV1 = CreateAliasTransaction
    .create(
      Transaction.V1,
      publicKey,
      "myalias",
      minFee,
      ts,
      Proofs(
        ByteStr
          .decodeBase16(
            "09a65421fdb0632908bf6e157ae78f19f8cf4cf479bcb30c14aca7d75788cdcf10a7df9d6b6d2de0d1c4290be4a4f0495f05985d1a1cce62d6aa1f93b8bffb86"
          )
          .get
      )
    )
    .explicitGet()

  private lazy val aliasV2 = CreateAliasTransaction
    .create(
      Transaction.V2,
      publicKey,
      "myalias",
      minFee,
      ts,
      Proofs(
        Seq(
          ByteStr
            .decodeBase16(
              "36bb64f8a1fc8faa30938b2ed138a0466ff6913d1d4e08a7071a0d891a677350789b20ff7f9c326eec9953c7f38f19b40a34db58c10d346f7e71312a2b708c87"
            )
            .get
        )
      )
    )
    .explicitGet()

  private lazy val data = DataTransaction
    .create(
      1.toByte,
      publicKey,
      List(IntegerDataEntry("int", 24), BooleanDataEntry("bool", true), BinaryDataEntry("blob", ByteStr.decodeBase64("YWxpY2U=").get)),
      minFee,
      ts,
      Proofs(
        Seq(
          ByteStr
            .decodeBase16(
              "658e5bb4a9c2e9a93821b87c68d4417bf955136dd4f4551fcf82e49d4ec8d8f13702699a6d3a85cec3d78dce11cc69cd06a41e10b995bd3dc50ef0b5eb0f6b8b"
            )
            .get
        )
      )
    )
    .explicitGet()

  private lazy val issueV1 = IssueTransaction(
    TxVersion.V1,
    publicKey,
    ByteString.copyFromUtf8("Gigacoin"),
    ByteString.copyFromUtf8("Gigacoin"),
    TxPositiveAmount.unsafeFrom(someAssetAmount),
    TxDecimals.unsafeFrom(8.toByte),
    true,
    script = None,
    TxPositiveAmount.unsafeFrom(issueFee),
    ts,
    Proofs(
      ByteStr
        .decodeBase16(
          "38b23a9854990fde3dce854aa91b78f120cd3674e858efaf52c9ad09d7a2c7bc07e514243b061042f4048505bf7e63429acae66d6d8832ded21a39c2bcf5bd81"
        )
        .get
    ),
    AddressScheme.current.chainId
  )

  private lazy val issueV2 = IssueTransaction(
    TxVersion.V2,
    publicKey,
    ByteString.copyFromUtf8("Gigacoin"),
    ByteString.copyFromUtf8("Gigacoin"),
    TxPositiveAmount.unsafeFrom(someAssetAmount),
    TxDecimals.unsafeFrom(8.toByte),
    true,
    None,
    TxPositiveAmount.unsafeFrom(issueFee),
    ts,
    Proofs(
      Seq(
        ByteStr
          .decodeBase16(
            "982a50240551cb4b2eb74963ca54319df33bcb06b532fac5986254b0c668cf311bdafb965dab3dea5b94a5b06e03ff42a6740bc68083620cc58e44ee16d4b28b"
          )
          .get
      )
    ),
    AddressScheme.current.chainId
  )

  private lazy val leasecancelV1 = LeaseCancelTransaction
    .create(
      1.toByte,
      publicKey,
      ByteStr.decodeBase16("c905697322ae74647ff72b38bf23de8c9db40276abb2195676c78a260edcec0f").get,
      minFee,
      ts,
      Proofs(
        ByteStr
          .decodeBase16(
            "ac901cf6aa3d09e9a623652baff0eb6128e58c1d57a23f4c254f7f6035d4a5a6e0c1431fff68288d039fb83c07af40fe25e21f1fd98df9d8a67a7d0cf042e280"
          )
          .get
      )
    )
    .explicitGet()

  private lazy val leasecancelV2 = LeaseCancelTransaction
    .create(
      2.toByte,
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

  private lazy val leaseV1 = LeaseTransaction
    .create(
      1.toByte,
      publicKey,
      Address.fromString(sender.address).explicitGet(),
      10000000,
      minFee,
      ts,
      Proofs(
        ByteStr
          .decodeBase16(
            "2430aa66d62717e1c622ffb58b335b0b584534e3a04afccf913df74a1f1a0798c89d3f07cc340c9318e4ec966663ba2a497bfbdb8037468d4787466b2fd91687"
          )
          .get
      )
    )
    .explicitGet()

  private lazy val leaseV2 = LeaseTransaction
    .create(
      2.toByte,
      publicKey,
      Address.fromString(sender.address).explicitGet(),
      10000000,
      minFee,
      ts,
      Proofs(
        ByteStr
          .decodeBase16(
            "d4ded0a3a798decf46459c701e03b6db01cc2c93d1445f6973a3cb5172247f89997a9729cec3d3f288a5a46484434c012e99ed76b1abfa3c31bee1097afc9c80"
          )
          .get
      )
    )
    .explicitGet()

  private lazy val mass = MassTransferTransaction
    .create(
      1.toByte,
      publicKey,
      Waves,
      MassTransferTransaction
        .parseTransfersList(List(Transfer(firstKeyPair.toAddress.toString, 1.waves), Transfer(secondKeyPair.toAddress.toString, 2.waves)))
        .explicitGet(),
      2.waves,
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

  private lazy val reissueV1 = ReissueTransaction
    .create(
      1.toByte,
      publicKey,
      IssuedAsset(ByteStr.decodeBase16("808912576b218e0e1d400e485dfca793c177ddfdbeccc776715710b4114ffcf9").get),
      100000000L,
      true,
      1.waves,
      ts,
      Proofs(
        ByteStr
          .decodeBase16(
            "75181d1b61ed9fcd77ea1377209fafb8e91432c62802f23efe08345444b16a477e3c45e919633a7438dbb0f2de226365371669b30fe8f712c6b61c87f3878385"
          )
          .get
      )
    )
    .explicitGet()

  private lazy val reissueV2 = ReissueTransaction
    .create(
      2.toByte,
      publicKey,
      IssuedAsset(ByteStr.decodeBase16("808912576b218e0e1d400e485dfca793c177ddfdbeccc776715710b4114ffcf9").get),
      100000000L,
      true,
      1.waves,
      ts,
      Proofs(
        Seq(
          ByteStr
            .decodeBase16(
              "a09c6e2c09d8f1b76ccd2d45eab80fbdc564a02937738ba80ca1155cb5e7b95eb2fb3bbc47f28c67ed512ded60b5669c3cddb9c0fb09974a28db35162d919488"
            )
            .get
        )
      )
    )
    .explicitGet()

  private lazy val setasset = SetAssetScriptTransaction
    .create(
      1.toByte,
      publicKey,
      IssuedAsset(ByteStr.decodeBase16("b976984b007a70e0e55fc9e311ff681de900ebe11388453e44569de5ce0279f1").get),
      Some(Script.fromBase64String("base64:AQkAAGcAAAACAHho/EXujJiPAJUhuPXZYac+rt2jYg==").explicitGet()),
      1.waves,
      ts,
      Proofs(
        Seq(
          "5sRtXKcdDa",
          "ff80ff9f0eff6a64807f99125e2a0101b5bc0101fab47f29000a4c017112005dff7f804401",
          "",
          "3C",
          "8012577fff4c01ff85497f80ffb300007f95ffb0bc01820a99009da010008b167f09b58200fdff92db80ae0056017a",
          ""
        ).map(ByteStr.decodeBase16(_).get)
      )
    )
    .explicitGet()

  private lazy val setscript = SetScriptTransaction
    .create(
      1.toByte,
      publicKey,
      None,
      setScriptFee,
      ts,
      Proofs(
        Seq(
          ByteStr
            .decodeBase16(
              "2c81fb0e22374a9d9824f8a7b5c2b9c61d83b082f24ff0eb8baa4ce0088cf06ced11abf963163a4ac491e4097dd1856c285d0670d44d0dd052e3d5115b913c8b"
            )
            .get
        )
      )
    )
    .explicitGet()

  private lazy val sponsor = SponsorFeeTransaction
    .create(
      1.toByte,
      publicKey,
      IssuedAsset(ByteStr.decodeBase16("808912576b218e0e1d400e485dfca793c177ddfdbeccc776715710b4114ffcf9").get),
      Some(100000),
      1.waves,
      ts,
      Proofs(
        Seq(
          ByteStr
            .decodeBase16(
              "7899b9c4712c424c901ef829951e7c661c1d68640b098de87b8accadb912e580ef59189b751d5c82315c4d656fea66412d5b293a019dd5bd13a3c2b13ee2d488"
            )
            .get
        )
      )
    )
    .explicitGet()

  private lazy val recipient = Address.fromString(sender.address).explicitGet()
  private lazy val transferV1 = TransferTransaction(
    1.toByte,
    publicKey,
    recipient,
    Waves,
    TxPositiveAmount.unsafeFrom(1900000),
    Waves,
    TxPositiveAmount.unsafeFrom(minFee),
    ByteStr.empty,
    ts,
    Proofs(
      Seq(
        ByteStr
          .decodeBase16(
            "2067bd334bdb70dc3252968d8e06970e45e5d6a5abf260097fe4a8a483a549b9ac878c5aad7a2da5ac5ffc9c53ffd3d46fe12dc54c9e06033f10d729d96f4981"
          )
          .get
      )
    ),
    recipient.chainId
  )

  private lazy val transferV2 = TransferTransaction(
    2.toByte,
    publicKey,
    recipient,
    Waves,
    TxPositiveAmount.unsafeFrom(100000000),
    Waves,
    TxPositiveAmount.unsafeFrom(minFee),
    ByteStr.empty,
    ts,
    Proofs(
      Seq(
        ByteStr
          .decodeBase16(
            "b3f084c843db00e0c71e7786ce28ffc68111a3a579b924bd1989eae601ae6ced7edbd62d605b073e57146db283792ae497313f472d6d4adc871954ea3ff1738f"
          )
          .get
      )
    ),
    recipient.chainId
  )

  private lazy val invokeScript = InvokeScriptTransaction
    .create(
      1.toByte,
      PublicKey.fromBase16String("a10aed0ecba98e825c9a7eeeca56765e167fbf007d8125c39726b49bed267a6e").explicitGet(),
      PublicKey.fromBase16String("ddc81a3015b980628f204d30c3e1400626471de92e8271022292f48b11766716").explicitGet().toAddress,
      Some(
        Terms.FUNCTION_CALL(
          function = FunctionHeader.User("testfunc"),
          args = List(TRUE)
        )
      ),
      Seq(
        InvokeScriptTransaction.Payment(7, IssuedAsset(ByteStr.decodeBase16("59df714ead8fb10b68e31153ad01994117652cb3c960c6e32c57e7dec28a5846").get))
      ),
      smartMinFee,
      Waves,
      ts,
      Proofs(
        Seq(
          ByteStr
            .decodeBase16(
              "b3f084c843db00e0c71e7786ce28ffc68111a3a579b924bd1989eae601ae6ced7edbd62d605b073e57146db283792ae497313f472d6d4adc871954ea3ff1738f"
            )
            .get
        )
      ),
      AddressScheme.current.chainId
    )
    .explicitGet()

  test("serialize transactions") {
    forAll(
      Table(
        ("tx", "name"),
        (exV1, "exchangeV1"),
        (exV2, "exchangeV2"),
        (burnV1, "burnV1"),
        (burnV2, "burnV2"),
        (aliasV1, "aliasV1"),
        (aliasV2, "aliasV2"),
        (data, "data"),
        (issueV1, "issueV1"),
        (issueV2, "issueV2"),
        (leasecancelV1, "leasecancelV1"),
        (leasecancelV2, "leasecancelV2"),
        (leaseV1, "leaseV1"),
        (leaseV2, "leaseV2"),
        (mass, "mass"),
        (reissueV1, "reissueV1"),
        (reissueV2, "reissueV2"),
        (setasset, "setasset"),
        (setscript, "setscript"),
        (sponsor, "sponsor"),
        (transferV1, "transferV1"),
        (transferV2, "transferV2"),
        (invokeScript, "invokeScript")
      )
    ) { (tx, name) =>
      info(name)
      val r = sender.transactionSerializer(tx.json()).bytes.map(_.toByte)
      r shouldBe tx.bodyBytes()
    }
  }

}
