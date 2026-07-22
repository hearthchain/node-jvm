package com.wavesplatform.transaction

import com.wavesplatform.account.{Address, AddressScheme, PublicKey}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.Base16
import com.wavesplatform.common.utils.EitherExt2.*
import com.wavesplatform.test.PropSpec
import com.wavesplatform.transaction.Asset.Waves
import com.wavesplatform.transaction.serialization.impl.TransferTxSerializer
import com.wavesplatform.transaction.transfer.*
import play.api.libs.json.Json

class TransferTransactionV2Specification extends PropSpec {

  property("VersionedTransferTransactionSpecification id doesn't depend on proof") {
    forAll(accountGen, accountGen, proofsGen, proofsGen, attachmentGen) { case (_, acc2, proofs1, proofs2, attachment) =>
      val tx1 = TransferTransaction(
        2.toByte,
        PublicKey(acc2.publicKey),
        acc2.toAddress,
        Waves,
        TxPositiveAmount.unsafeFrom(1),
        Waves,
        TxPositiveAmount.unsafeFrom(1),
        attachment,
        1,
        proofs1,
        AddressScheme.current.chainId
      )
      val tx2 = TransferTransaction(
        2.toByte,
        PublicKey(acc2.publicKey),
        acc2.toAddress,
        Waves,
        TxPositiveAmount.unsafeFrom(1),
        Waves,
        TxPositiveAmount.unsafeFrom(1),
        attachment,
        1,
        proofs2,
        AddressScheme.current.chainId
      )
      tx1.id() shouldBe tx2.id()
    }
  }

  private def assertTxs(first: TransferTransaction, second: TransferTransaction): Unit = {
    first.sender shouldEqual second.sender
    first.timestamp shouldEqual second.timestamp
    first.fee shouldEqual second.fee
    first.amount shouldEqual second.amount
    first.recipient shouldEqual second.recipient
    first.version shouldEqual second.version
    first.assetId shouldEqual second.assetId
    first.feeAssetId shouldEqual second.feeAssetId
    first.proofs shouldEqual second.proofs
    first.bytes() shouldEqual second.bytes()
  }

  property("JSON format validation") {
    val js = Json.parse("""{
                       "type": 4,
                       "id": "1b3efbfce34964e000d028a53b87632e48405a90a1d16e837c2b2d1b83e7ce5f",
                       "sender": "3N5GRqzDBhjVXnCn44baHcz2GoZy5qLxtTh",
                       "senderPublicKey": "d528aabec35ca100d87c7b7a128632faf19cd44531819457445113a32a21ef22",
                       "fee": 100000000,
                       "timestamp": 1526641218066,
                       "proofs": [
                       "b3f084c843db00e0c71e7786ce28ffc68111a3a579b924bd1989eae601ae6ced7edbd62d605b073e57146db283792ae497313f472d6d4adc871954ea3ff1738f"
                       ],
                       "version": 2,
                       "recipient": "3My3KZgFQ3CrVHgz6vGRt8687sH4oAA1qp8",
                       "assetId": null,
                       "feeAsset": null,
                       "feeAssetId":null,
                       "amount": 100000000,
                       "attachment": "4t2Xazb2SX"}
    """)

    val recipient = Address.fromString("3My3KZgFQ3CrVHgz6vGRt8687sH4oAA1qp8").explicitGet()
    val tx = TransferTransaction(
      2.toByte,
      PublicKey.fromBase16String("d528aabec35ca100d87c7b7a128632faf19cd44531819457445113a32a21ef22").explicitGet(),
      recipient,
      Waves,
      TxPositiveAmount.unsafeFrom(100000000),
      Waves,
      TxPositiveAmount.unsafeFrom(100000000),
      ByteStr.decodeBase16("66616c6166656c").get,
      1526641218066L,
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

    tx.json() shouldEqual js
  }
}
