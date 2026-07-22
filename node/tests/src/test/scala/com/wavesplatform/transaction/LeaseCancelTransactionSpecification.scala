package com.wavesplatform.transaction

import com.wavesplatform.account.PublicKey
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.Base16
import com.wavesplatform.common.utils.EitherExt2.*
import com.wavesplatform.test.PropSpec
import com.wavesplatform.transaction.lease.LeaseCancelTransaction
import com.wavesplatform.transaction.serialization.impl.LeaseCancelTxSerializer
import play.api.libs.json.Json

class LeaseCancelTransactionSpecification extends PropSpec {

  private def assertTxs(first: LeaseCancelTransaction, second: LeaseCancelTransaction): Unit = {
    first.leaseId shouldEqual second.leaseId
    first.fee shouldEqual second.fee
    first.proofs shouldEqual second.proofs
    first.bytes() shouldEqual second.bytes()
  }

  property("JSON format validation for LeaseCancelTransactionV1") {
    val js = Json.parse("""{
                       "type": 9,
                       "id": "6397c284b792a5539899794830b0f5675635b3137fe2589999d0bc0cffa28e58",
                       "sender": "3N5GRqzDBhjVXnCn44baHcz2GoZy5qLxtTh",
                       "senderPublicKey": "d528aabec35ca100d87c7b7a128632faf19cd44531819457445113a32a21ef22",
                       "fee": 1000000,
                       "feeAssetId": null,
                       "timestamp": 1526646300260,
                       "signature": "ac901cf6aa3d09e9a623652baff0eb6128e58c1d57a23f4c254f7f6035d4a5a6e0c1431fff68288d039fb83c07af40fe25e21f1fd98df9d8a67a7d0cf042e280",
                       "proofs": ["ac901cf6aa3d09e9a623652baff0eb6128e58c1d57a23f4c254f7f6035d4a5a6e0c1431fff68288d039fb83c07af40fe25e21f1fd98df9d8a67a7d0cf042e280"],
                       "version": 1,
                       "leaseId": "c905697322ae74647ff72b38bf23de8c9db40276abb2195676c78a260edcec0f"
                       }
    """)

    val tx = LeaseCancelTransaction
      .create(
        1.toByte,
        PublicKey.fromBase16String("d528aabec35ca100d87c7b7a128632faf19cd44531819457445113a32a21ef22").explicitGet(),
        ByteStr.decodeBase16("c905697322ae74647ff72b38bf23de8c9db40276abb2195676c78a260edcec0f").get,
        1000000,
        1526646300260L,
        Proofs(
          ByteStr
            .decodeBase16(
              "ac901cf6aa3d09e9a623652baff0eb6128e58c1d57a23f4c254f7f6035d4a5a6e0c1431fff68288d039fb83c07af40fe25e21f1fd98df9d8a67a7d0cf042e280"
            )
            .get
        )
      )
      .explicitGet()

    js shouldEqual tx.json()
  }

  property("JSON format validation for LeaseCancelTransactionV2") {
    val js = Json.parse("""{
                        "type": 9,
                        "id": "3856a8fff691dbd7ccc42d20322cb9fd6b9c90263c0f16bae7bda858617127a4",
                        "sender": "3N5GRqzDBhjVXnCn44baHcz2GoZy5qLxtTh",
                        "senderPublicKey": "d528aabec35ca100d87c7b7a128632faf19cd44531819457445113a32a21ef22",
                        "fee": 1000000,
                        "feeAssetId":null,
                        "timestamp": 1526646300260,
                        "proofs": [
                        "86982ec3897bc1d6461c58bca6378a584fd3fb1186f125b1a89f87c2dc77316c5536054de029d14fb2d512582e4b988a5dbfb3a47acb779cec925bc625b5598f"
                        ],
                        "version": 2,
                        "leaseId": "b6c8c0ec67cb74ea16339e5cba54e274310234597b193fa49035e1013b205dc7",
                        "chainId": 84
                       }
    """)

    val tx = LeaseCancelTransaction
      .create(
        2.toByte,
        PublicKey.fromBase16String("d528aabec35ca100d87c7b7a128632faf19cd44531819457445113a32a21ef22").explicitGet(),
        ByteStr.decodeBase16("b6c8c0ec67cb74ea16339e5cba54e274310234597b193fa49035e1013b205dc7").get,
        1000000,
        1526646300260L,
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

    js shouldEqual tx.json()
  }

}
