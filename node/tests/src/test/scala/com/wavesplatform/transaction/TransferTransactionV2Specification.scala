package com.wavesplatform.transaction

import com.wavesplatform.account.{Address, AddressScheme, PublicKey}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.Base58
import com.wavesplatform.common.utils.EitherExt2.*
import com.wavesplatform.test.PropSpec
import com.wavesplatform.transaction.Asset.Waves
import com.wavesplatform.transaction.serialization.impl.TransferTxSerializer
import com.wavesplatform.transaction.transfer.*
import play.api.libs.json.Json

class TransferTransactionV2Specification extends PropSpec {

  property("VersionedTransferTransactionSpecification id doesn't depend on proof") {
    forAll(accountGen, accountGen, proofsGen, proofsGen, attachmentGen) {
      case (_, acc2, proofs1, proofs2, attachment) =>
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
                       "id": "2qMiGUpNMuRpeyTnXLa1mLuVP1cYEtxys55cQbDaXd5g",
                       "sender": "3N5GRqzDBhjVXnCn44baHcz2GoZy5qLxtTh",
                       "senderPublicKey": "FM5ojNqW7e9cZ9zhPYGkpSP1Pcd8Z3e3MNKYVS5pGJ8Z",
                       "fee": 100000000,
                       "timestamp": 1526641218066,
                       "proofs": [
                       "4bfDaqBcnK3hT8ywFEFndxtS1DTSYfncUqd4s5Vyaa66PZHawtC73rDswUur6QZu5RpqM7L9NFgBHT1vhCoox4vi"
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
      PublicKey.fromBase58String("FM5ojNqW7e9cZ9zhPYGkpSP1Pcd8Z3e3MNKYVS5pGJ8Z").explicitGet(),
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

    tx.json() shouldEqual js
  }
}
