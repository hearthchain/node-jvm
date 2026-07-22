package com.wavesplatform.transaction.api.http.assets

import com.wavesplatform.api.http.requests.*
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2.*
import com.wavesplatform.test.FunSuite
import play.api.libs.json.Json

class SignedRequestsTest extends FunSuite {


  test("AssetTransfer json parsing works") {
    val json =
      """
        |{
        |   "recipient":"3Myss6gmMckKYtka3cKCM563TBJofnxvfD7",
        |   "timestamp":1479462208828,
        |   "assetId":"e14fa45382b6116872fa4eb93ce97a1c582e3416c526d18ea41a59c326ea3628",
        |   "amount":100000,
        |   "fee":100000,
        |   "senderPublicKey":"b3a719f716d83848cfc8953a98597aeb65975fba7b83bc85fd33805acc1b871c",
        |   "signature":"b56ded3983ba063796ee30f9a07b753e7dcc161e28670237b222d78b986ad42006e0f874e4a2912dcbb7118a6ac3b26c3da53a92ad85711014bb7e4c03ed1803",
        |   "attachment":"A"
        |}
      """.stripMargin
    val req = Json.parse(json).validate[SignedTransferV1Request].get
    req.recipient shouldBe "3Myss6gmMckKYtka3cKCM563TBJofnxvfD7"
    req.timestamp shouldBe 1479462208828L
    req.assetId shouldBe Some("e14fa45382b6116872fa4eb93ce97a1c582e3416c526d18ea41a59c326ea3628")
    req.amount shouldBe 100000
    req.fee shouldBe 100000
    req.senderPublicKey shouldBe "b3a719f716d83848cfc8953a98597aeb65975fba7b83bc85fd33805acc1b871c"
    req.signature shouldBe "b56ded3983ba063796ee30f9a07b753e7dcc161e28670237b222d78b986ad42006e0f874e4a2912dcbb7118a6ac3b26c3da53a92ad85711014bb7e4c03ed1803"
    req.attachment shouldBe Some("A")

    val tx = req.toTx.explicitGet()
    tx.sender.toString shouldBe "b3a719f716d83848cfc8953a98597aeb65975fba7b83bc85fd33805acc1b871c"
    tx.timestamp shouldBe 1479462208828L
    tx.attachment shouldBe ByteStr.decodeBase16("09").get
    tx.assetId.maybeBase16Repr.get shouldBe "e14fa45382b6116872fa4eb93ce97a1c582e3416c526d18ea41a59c326ea3628"
    tx.amount.value shouldBe 100000
    tx.fee.value shouldBe 100000
    tx.proofs.toSignature.toString shouldBe "b56ded3983ba063796ee30f9a07b753e7dcc161e28670237b222d78b986ad42006e0f874e4a2912dcbb7118a6ac3b26c3da53a92ad85711014bb7e4c03ed1803"
  }

  test("AssetTransfer with a fee in an asset json parsing works") {
    val json =
      """
        |{
        |   "senderPublicKey":"d4998eaf0f2859ed3a97ba84bf51a462629a682282b1fd76b9c453269fd85b6b",
        |   "recipient":"3N9UuGeWuDt9NfWbC5oEACHyRoeEMApXAeq",
        |   "timestamp":1489054107569,
        |   "assetId":"4f834b32917f8f972393e7ed579d172c2be2ddccbcd5eaaa89b1d54e9d6ced11",
        |   "amount":1000,
        |   "fee":100,
        |   "feeAssetId":"4f834b32917f8f972393e7ed579d172c2be2ddccbcd5eaaa89b1d54e9d6ced11",
        |   "signature":"176d904f957685561add4f4a627c3865f197678a7dd3f81caa0f1869d4ee5b58f8510bb346615ff7fe8c29366e5678badf88126f4cf3a5516cdcb9e9dc72eb8b",
        |   "attachment":"d0bfd0b5d180d0b5d0b2d0bed0b4"
        |}
      """.stripMargin
    val req = Json.parse(json).validate[SignedTransferV1Request].get
    req.recipient shouldBe "3N9UuGeWuDt9NfWbC5oEACHyRoeEMApXAeq"
    req.timestamp shouldBe 1489054107569L
    req.assetId shouldBe Some("4f834b32917f8f972393e7ed579d172c2be2ddccbcd5eaaa89b1d54e9d6ced11")
    req.feeAssetId shouldBe Some("4f834b32917f8f972393e7ed579d172c2be2ddccbcd5eaaa89b1d54e9d6ced11")
    req.amount shouldBe 1000
    req.fee shouldBe 100
    req.senderPublicKey shouldBe "d4998eaf0f2859ed3a97ba84bf51a462629a682282b1fd76b9c453269fd85b6b"
    req.signature shouldBe "176d904f957685561add4f4a627c3865f197678a7dd3f81caa0f1869d4ee5b58f8510bb346615ff7fe8c29366e5678badf88126f4cf3a5516cdcb9e9dc72eb8b"
    req.attachment shouldBe Some("2Kk7Zsr1e9jsqSBM5hpF")

    val tx = req.toTx.explicitGet()
    tx.sender.toString shouldBe "d4998eaf0f2859ed3a97ba84bf51a462629a682282b1fd76b9c453269fd85b6b"
    tx.timestamp shouldBe 1489054107569L
    tx.attachment shouldBe ByteStr.decodeBase16("d0bfd0b5d180d0b5d0b2d0bed0b4").get
    tx.assetId.maybeBase16Repr.get shouldBe "4f834b32917f8f972393e7ed579d172c2be2ddccbcd5eaaa89b1d54e9d6ced11"
    tx.feeAssetId.maybeBase16Repr.get shouldBe "4f834b32917f8f972393e7ed579d172c2be2ddccbcd5eaaa89b1d54e9d6ced11"
    tx.amount.value shouldBe 1000
    tx.fee.value shouldBe 100
    tx.proofs.toSignature.toString shouldBe "176d904f957685561add4f4a627c3865f197678a7dd3f81caa0f1869d4ee5b58f8510bb346615ff7fe8c29366e5678badf88126f4cf3a5516cdcb9e9dc72eb8b"
  }
}
