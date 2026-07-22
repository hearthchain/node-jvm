package com.wavesplatform.transaction

import com.wavesplatform.account.{Address, AddressScheme, PublicKey}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.Base16
import com.wavesplatform.common.utils.EitherExt2.*
import com.wavesplatform.test.*
import com.wavesplatform.transaction.Asset.Waves
import com.wavesplatform.transaction.Proofs
import com.wavesplatform.transaction.serialization.impl.TransferTxSerializer
import com.wavesplatform.transaction.transfer.*
import play.api.libs.json.Json

class TransferTransactionV1Specification extends PropSpec {

  property("JSON format validation") {
    val js = Json.parse("""{
                        "type": 4,
                        "id": "d51b4da4cda91d23da663b127b74ea61e48d6def53cb126c4f1b9b1d4677f461",
                        "sender": "3N5GRqzDBhjVXnCn44baHcz2GoZy5qLxtTh",
                        "senderPublicKey": "d528aabec35ca100d87c7b7a128632faf19cd44531819457445113a32a21ef22",
                        "fee": 100000,
                        "timestamp": 1526552510868,
                        "signature": "2067bd334bdb70dc3252968d8e06970e45e5d6a5abf260097fe4a8a483a549b9ac878c5aad7a2da5ac5ffc9c53ffd3d46fe12dc54c9e06033f10d729d96f4981",
                        "proofs": ["2067bd334bdb70dc3252968d8e06970e45e5d6a5abf260097fe4a8a483a549b9ac878c5aad7a2da5ac5ffc9c53ffd3d46fe12dc54c9e06033f10d729d96f4981"],
                        "version": 1,
                        "recipient": "3My3KZgFQ3CrVHgz6vGRt8687sH4oAA1qp8",
                        "assetId": null,
                        "feeAsset":null,
                        "feeAssetId":null,
                        "amount": 1900000,
                        "attachment": "4t2Xazb2SX"
                        }
    """)

    val recipient = Address.fromString("3My3KZgFQ3CrVHgz6vGRt8687sH4oAA1qp8").explicitGet()
    val tx = TransferTransaction(
      1.toByte,
      PublicKey.fromBase16String("d528aabec35ca100d87c7b7a128632faf19cd44531819457445113a32a21ef22").explicitGet(),
      recipient,
      Waves,
      TxPositiveAmount.unsafeFrom(1900000),
      Waves,
      TxPositiveAmount.unsafeFrom(100000),
      ByteStr.decodeBase16("66616c6166656c").get,
      1526552510868L,
      Proofs(Seq(ByteStr.decodeBase16("2067bd334bdb70dc3252968d8e06970e45e5d6a5abf260097fe4a8a483a549b9ac878c5aad7a2da5ac5ffc9c53ffd3d46fe12dc54c9e06033f10d729d96f4981").get)),
      AddressScheme.current.chainId
    )

    tx.json() shouldEqual js
  }

  property("negative") {
    for {
      (_, sender, recipient, amount, timestamp, _, feeAmount, attachment) <- transferParamGen
    } yield TransferTransaction.create(1.toByte, PublicKey(sender.publicKey), recipient, Waves, amount, Waves, feeAmount, attachment, timestamp, Proofs.empty).map(_.signWith(sender)) should produce(
      "insufficient fee"
    )
  }
}
