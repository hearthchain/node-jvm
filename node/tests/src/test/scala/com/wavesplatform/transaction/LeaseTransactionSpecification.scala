package com.wavesplatform.transaction

import com.wavesplatform.account.{AddressScheme, PublicKey}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2.*
import com.wavesplatform.test.PropSpec
import com.wavesplatform.transaction.lease.LeaseTransaction
import play.api.libs.json.*

class LeaseTransactionSpecification extends PropSpec {

  property("JSON format validation for LeaseTransaction") {
    val tx = LeaseTransaction
      .create(
        AddressScheme.current.chainId,
        PublicKey(ByteStr(TxHelpers.signer(1020).publicKey())),
        TxHelpers.address(1021),
        10000000,
        1000000,
        1526646497465L,
        Proofs(Seq(ByteStr.decodeBase58("5Fr3yLwvfKGDsFLi8A8JbHqToHDojrPbdEGx9mrwbeVWWoiDY5pRqS3rcX1rXC9ud52vuxVdBmGyGk5krcgwFu9q").get))
      )
      .explicitGet()

    val js = tx.json()
    (js \ "type").as[Int] shouldBe 4
    (js \ "senderPublicKey").as[String] shouldBe "Ds1RebvFmw7GoGbrafXh9oiMGLnkYSRH2XwoJ8b5eohZ"
    (js \ "amount").as[Long] shouldBe 10000000L
    (js \ "fee").as[Long] shouldBe 1000000L
    (js \ "id").as[String] shouldBe tx.id().toString
  }
}
