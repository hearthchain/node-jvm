package tech.hearth.transaction

import tech.hearth.account.PublicKey
import tech.hearth.common.state.ByteStr
import tech.hearth.common.utils.EitherExt2.*
import tech.hearth.test.PropSpec
import tech.hearth.transaction.lease.LeaseCancelTransaction
import play.api.libs.json.Json

class LeaseCancelTransactionSpecification extends PropSpec {

  property("JSON format validation for LeaseCancelTransactionV1") {
    val js = Json.parse("""{
                       "type": 5,
                       "id": "890566cfdf5947736893e20e21781edfd9842113b787663e9d982b54075834ff",
                       "sender": "thrth1ryd2f987gg464uf4q5jte5rcmc2xgq6kr3qe39",
                       "senderPublicKey": "d528aabec35ca100d87c7b7a128632faf19cd44531819457445113a32a21ef22",
                       "fee": 1000000,
                       "feeAssetId": null,
                       "timestamp": 1526646300260,
                       "proofs": ["ac901cf6aa3d09e9a623652baff0eb6128e58c1d57a23f4c254f7f6035d4a5a6e0c1431fff68288d039fb83c07af40fe25e21f1fd98df9d8a67a7d0cf042e280"],
                       "leaseId": "c905697322ae74647ff72b38bf23de8c9db40276abb2195676c78a260edcec0f",
                       "chainId": 84
                       }
    """)

    val tx = LeaseCancelTransaction
      .create(
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
                        "type": 5,
                        "id": "34badb2d21503897a805eb09e55a1bfdb19b1dadfe37a2939b276c4e238a1612",
                        "sender": "thrth1ryd2f987gg464uf4q5jte5rcmc2xgq6kr3qe39",
                        "senderPublicKey": "d528aabec35ca100d87c7b7a128632faf19cd44531819457445113a32a21ef22",
                        "fee": 1000000,
                        "feeAssetId":null,
                        "timestamp": 1526646300260,
                        "proofs": [
                        "86982ec3897bc1d6461c58bca6378a584fd3fb1186f125b1a89f87c2dc77316c5536054de029d14fb2d512582e4b988a5dbfb3a47acb779cec925bc625b5598f"
                        ],
                        "leaseId": "b6c8c0ec67cb74ea16339e5cba54e274310234597b193fa49035e1013b205dc7",
                        "chainId": 84
                       }
    """)

    val tx = LeaseCancelTransaction
      .create(
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
