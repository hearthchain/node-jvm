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
        1,
        AddressScheme.current.chainId,
        PublicKey.fromBase16String("d528aabec35ca100d87c7b7a128632faf19cd44531819457445113a32a21ef22").explicitGet(),
        Address.fromString("3NCBMxgdghg4tUhEEffSXy11L6hUi6fcBpd").explicitGet(),
        10000000,
        1000000,
        1526646497465L,
        Proofs(
          Seq(
            ByteStr
              .decodeBase16(
                "d4ded0a3a798decf46459c701e03b6db01cc2c93d1445f6973a3cb5172247f89997a9729cec3d3f288a5a46484434c012e99ed76b1abfa3c31bee1097afc9c80"
              )
              .get
          )
        )
      )
      .explicitGet()

    val js = tx.json()
    (js \ "type").as[Int] shouldBe 8
    (js \ "version").as[Int] shouldBe 1
    (js \ "senderPublicKey").as[String] shouldBe "d528aabec35ca100d87c7b7a128632faf19cd44531819457445113a32a21ef22"
    (js \ "amount").as[Long] shouldBe 10000000L
    (js \ "fee").as[Long] shouldBe 1000000L
    (js \ "id").as[String] shouldBe tx.id().toString
  }
}
