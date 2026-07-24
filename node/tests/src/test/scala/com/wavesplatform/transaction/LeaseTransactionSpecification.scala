package com.wavesplatform.transaction

import com.wavesplatform.account.{Address, AddressScheme, PublicKey}
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
        PublicKey.fromBase58String("FM5ojNqW7e9cZ9zhPYGkpSP1Pcd8Z3e3MNKYVS5pGJ8Z").explicitGet(),
        Address.fromString("3NCBMxgdghg4tUhEEffSXy11L6hUi6fcBpd").explicitGet(),
        10000000,
        1000000,
        1526646497465L,
        Proofs(Seq(ByteStr.decodeBase58("5Fr3yLwvfKGDsFLi8A8JbHqToHDojrPbdEGx9mrwbeVWWoiDY5pRqS3rcX1rXC9ud52vuxVdBmGyGk5krcgwFu9q").get))
      )
      .explicitGet()

    val js = tx.json()
    (js \ "type").as[Int] shouldBe 8
    (js \ "version").as[Int] shouldBe 1
    (js \ "senderPublicKey").as[String] shouldBe "FM5ojNqW7e9cZ9zhPYGkpSP1Pcd8Z3e3MNKYVS5pGJ8Z"
    (js \ "amount").as[Long] shouldBe 10000000L
    (js \ "fee").as[Long] shouldBe 1000000L
    (js \ "id").as[String] shouldBe tx.id().toString
  }
}
