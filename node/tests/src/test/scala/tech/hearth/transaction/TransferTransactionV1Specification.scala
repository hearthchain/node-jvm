package tech.hearth.transaction

import tech.hearth.account.{AddressScheme, PublicKey}
import tech.hearth.common.state.ByteStr
import tech.hearth.test.*
import tech.hearth.transaction.Asset.Waves
import tech.hearth.transaction.Proofs
import tech.hearth.transaction.transfer.*
import play.api.libs.json.Json

class TransferTransactionV1Specification extends PropSpec {

  property("JSON format validation") {
    val js = Json.parse("""{
                        "type": 2,
                        "id": "e3614bdd1a9185b8bf74e5bf3e5d92a35f1a72782282f4698d3c2b260a8e2cc2",
                        "sender": "thrth152e79nwdg3hjl3rf7uwxv627075zfjez374h0k",
                        "senderPublicKey": "6fbfed5c2d3ef24b6765cd294500600915a4f5b634f526f6f68a70f5b5dc3a26",
                        "fee": 100000,
                        "chainId": 84,
                        "timestamp": 1526552510868,
                        "proofs": ["2067bd334bdb70dc3252968d8e06970e45e5d6a5abf260097fe4a8a483a549b9ac878c5aad7a2da5ac5ffc9c53ffd3d46fe12dc54c9e06033f10d729d96f4981"],
                        "recipient": "thrth1u3u7e4n4j7zqtrun5g5sgx8kyp74z328jpqatj",
                        "assetId": null,
                        "feeAsset":null,
                        "feeAssetId":null,
                        "amount": 1900000,
                        "attachment": "66616c6166656c"
                        }
    """)

    val recipient = TxHelpers.address(1020)
    val tx = TransferTransaction(
      PublicKey(ByteStr.decodeBase16("6fbfed5c2d3ef24b6765cd294500600915a4f5b634f526f6f68a70f5b5dc3a26").get),
      recipient,
      Waves,
      TxPositiveAmount.unsafeFrom(1900000),
      Waves,
      TxPositiveAmount.unsafeFrom(100000),
      ByteStr.decodeBase16("66616c6166656c").get,
      1526552510868L,
      Proofs(
        Seq(
          ByteStr
            .decodeBase16(
              "2067bd334bdb70dc3252968d8e06970e45e5d6a5abf260097fe4a8a483a549b9ac878c5aad7a2da5ac5ffc9c53ffd3d46fe12dc54c9e06033f10d729d96f4981"
            )
            .get
        )
      ),
      AddressScheme.current.chainId
    )

    tx.json() shouldEqual js
  }

  property("negative") {
    for {
      (_, sender, recipient, amount, timestamp, _, feeAmount, attachment) <- transferParamGen
    } yield TransferTransaction
      .create(PublicKey(sender.publicKey), recipient, Waves, amount, Waves, feeAmount, attachment, timestamp, Proofs.empty)
      .map(_.signWith(sender)) should produce(
      "insufficient fee"
    )
  }
}
