package com.wavesplatform.transaction

import com.wavesplatform.account.{AddressScheme, PublicKey}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.test.*
import com.wavesplatform.transaction.Asset.Waves
import com.wavesplatform.transaction.Proofs
import com.wavesplatform.transaction.transfer.*
import play.api.libs.json.Json

class TransferTransactionV1Specification extends PropSpec {

  property("JSON format validation") {
    val js = Json.parse("""{
                        "type": 2,
                        "id": "GJbbcY4TJkBhSE5QBXdPNZKPFTEUJsA772xPf9UEU5FX",
                        "sender": "thrth152e79nwdg3hjl3rf7uwxv627075zfjez374h0k",
                        "senderPublicKey": "8XDzkyFJggLsVviN3cdQoJ9LSdFYVx457mq1azfPohR7",
                        "fee": 100000,
                        "chainId": 84,
                        "timestamp": 1526552510868,
                        "proofs": ["eaV1i3hEiXyYQd6DQY7EnPg9XzpAvB9VA3bnpin2qJe4G36GZXaGnYKCgSf9xiQ61DcAwcBFzjSXh6FwCgazzFz"],
                        "recipient": "thrth1u3u7e4n4j7zqtrun5g5sgx8kyp74z328jpqatj",
                        "assetId": null,
                        "feeAsset":null,
                        "feeAssetId":null,
                        "amount": 1900000,
                        "attachment": "4t2Xazb2SX"
                        }
    """)

    val recipient = TxHelpers.address(1020)
    val tx = TransferTransaction(
      PublicKey(ByteStr.decodeBase58("8XDzkyFJggLsVviN3cdQoJ9LSdFYVx457mq1azfPohR7").get),
      recipient,
      Waves,
      TxPositiveAmount.unsafeFrom(1900000),
      Waves,
      TxPositiveAmount.unsafeFrom(100000),
      ByteStr.decodeBase58("4t2Xazb2SX").get,
      1526552510868L,
      Proofs(Seq(ByteStr.decodeBase58("eaV1i3hEiXyYQd6DQY7EnPg9XzpAvB9VA3bnpin2qJe4G36GZXaGnYKCgSf9xiQ61DcAwcBFzjSXh6FwCgazzFz").get)),
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
