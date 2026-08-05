package tech.hearth.transaction

import tech.hearth.account.{AddressScheme, PublicKey}
import tech.hearth.common.state.ByteStr
import tech.hearth.common.utils.EitherExt2.*
import tech.hearth.test.PropSpec
import tech.hearth.transaction.Asset.Waves
import tech.hearth.transaction.transfer.*
import play.api.libs.json.Json

class TransferTransactionV2Specification extends PropSpec {

  property("VersionedTransferTransactionSpecification id doesn't depend on proof") {
    forAll(accountGen, accountGen, proofsGen, proofsGen, attachmentGen) { case (_, acc2, proofs1, proofs2, attachment) =>
      val tx1 = TransferTransaction(
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

  property("JSON format validation") {
    // Addresses are bech32 and the id is a hash over the whole transaction, so both are derived rather than pasted in:
    // the base58 literals this fixture used to carry no longer parse. Everything else is still pinned.
    val sender    = PublicKey.fromBase16String("d528aabec35ca100d87c7b7a128632faf19cd44531819457445113a32a21ef22").explicitGet()
    val recipient = TxHelpers.signer(1).toAddress

    val tx = TransferTransaction(
      sender,
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

    val js = Json.parse(s"""{
                       "type": 2,
                       "id": "${tx.id()}",
                       "sender": "${sender.toAddress}",
                       "senderPublicKey": "d528aabec35ca100d87c7b7a128632faf19cd44531819457445113a32a21ef22",
                       "fee": 100000000,
                       "timestamp": 1526641218066,
                       "chainId": ${AddressScheme.current.chainId},
                       "proofs": [
                       "b3f084c843db00e0c71e7786ce28ffc68111a3a579b924bd1989eae601ae6ced7edbd62d605b073e57146db283792ae497313f472d6d4adc871954ea3ff1738f"
                       ],
                       "recipient": "$recipient",
                       "assetId": null,
                       "feeAsset": null,
                       "feeAssetId":null,
                       "amount": 100000000,
                       "attachment": "66616c6166656c"}
    """)

    tx.json() shouldEqual js
  }
}
