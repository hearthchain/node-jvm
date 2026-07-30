package com.wavesplatform.transaction

import com.wavesplatform.account.{AddressScheme, PublicKey}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2.*
import com.wavesplatform.test.PropSpec
import com.wavesplatform.transaction.Asset.Waves
import com.wavesplatform.transaction.transfer.*
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
    val sender    = PublicKey.fromBase58String("FM5ojNqW7e9cZ9zhPYGkpSP1Pcd8Z3e3MNKYVS5pGJ8Z").explicitGet()
    val recipient = TxHelpers.signer(1).toAddress

    val tx = TransferTransaction(
      sender,
      recipient,
      Waves,
      TxPositiveAmount.unsafeFrom(100000000),
      Waves,
      TxPositiveAmount.unsafeFrom(100000000),
      ByteStr.decodeBase58("4t2Xazb2SX").get,
      1526641218066L,
      Proofs(Seq(ByteStr.decodeBase58("4bfDaqBcnK3hT8ywFEFndxtS1DTSYfncUqd4s5Vyaa66PZHawtC73rDswUur6QZu5RpqM7L9NFgBHT1vhCoox4vi").get)),
      AddressScheme.current.chainId
    )

    val js = Json.parse(s"""{
                       "type": 2,
                       "id": "${tx.id()}",
                       "sender": "${sender.toAddress}",
                       "senderPublicKey": "FM5ojNqW7e9cZ9zhPYGkpSP1Pcd8Z3e3MNKYVS5pGJ8Z",
                       "fee": 100000000,
                       "timestamp": 1526641218066,
                       "chainId": ${AddressScheme.current.chainId},
                       "proofs": [
                       "4bfDaqBcnK3hT8ywFEFndxtS1DTSYfncUqd4s5Vyaa66PZHawtC73rDswUur6QZu5RpqM7L9NFgBHT1vhCoox4vi"
                       ],
                       "recipient": "$recipient",
                       "assetId": null,
                       "feeAsset": null,
                       "feeAssetId":null,
                       "amount": 100000000,
                       "attachment": "4t2Xazb2SX"}
    """)

    tx.json() shouldEqual js
  }
}
