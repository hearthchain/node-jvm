package tech.hearth.transaction

import tech.hearth.account.{NetworkId, PublicKey}
import tech.hearth.common.state.ByteStr
import tech.hearth.common.utils.EitherExt2.*
import tech.hearth.test.PropSpec
import tech.hearth.transaction.lease.LeaseTransaction
import play.api.libs.json.*

class LeaseTransactionSpecification extends PropSpec {

  property("JSON format validation for LeaseTransaction") {
    val tx = LeaseTransaction
      .create(
        NetworkId.current,
        PublicKey(ByteStr(TxHelpers.signer(1020).publicKey())),
        TxHelpers.address(1021),
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
    (js \ "type").as[Int] shouldBe 4
    (js \ "senderPublicKey").as[String] shouldBe "bf1bc39b254cf74ee50620668f109472e2f45da23e913876b2e85084c6fc8930"
    (js \ "amount").as[Long] shouldBe 10000000L
    (js \ "fee").as[Long] shouldBe 1000000L
    (js \ "id").as[String] shouldBe tx.id().toString
  }
}
