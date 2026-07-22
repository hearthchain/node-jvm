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
        |   "assetId":"GAXAj8T4pSjunDqpz6Q3bit4fJJN9PD4t8AK8JZVSa5u",
        |   "amount":100000,
        |   "fee":100000,
        |   "senderPublicKey":"D6HmGZqpXCyAqpz8mCAfWijYDWsPKncKe5v3jq1nTpf5",
        |   "signature":"4dPRTW6XyRQUTQwwpuZDCNy1UDHYG9WGsEQnn5v49Lj5uyh4XGDdwtEq3t6ZottweAXHieK32UokHwiTxGFtz9bQ",
        |   "attachment":"A"
        |}
      """.stripMargin
    val req = Json.parse(json).validate[SignedTransferV1Request].get
    req.recipient shouldBe "3Myss6gmMckKYtka3cKCM563TBJofnxvfD7"
    req.timestamp shouldBe 1479462208828L
    req.assetId shouldBe Some("GAXAj8T4pSjunDqpz6Q3bit4fJJN9PD4t8AK8JZVSa5u")
    req.amount shouldBe 100000
    req.fee shouldBe 100000
    req.senderPublicKey shouldBe "D6HmGZqpXCyAqpz8mCAfWijYDWsPKncKe5v3jq1nTpf5"
    req.signature shouldBe "4dPRTW6XyRQUTQwwpuZDCNy1UDHYG9WGsEQnn5v49Lj5uyh4XGDdwtEq3t6ZottweAXHieK32UokHwiTxGFtz9bQ"
    req.attachment shouldBe Some("A")

    val tx = req.toTx.explicitGet()
    tx.sender.toString shouldBe "D6HmGZqpXCyAqpz8mCAfWijYDWsPKncKe5v3jq1nTpf5"
    tx.timestamp shouldBe 1479462208828L
    tx.attachment shouldBe ByteStr.decodeBase58("A").get
    tx.assetId.maybeBase58Repr.get shouldBe "GAXAj8T4pSjunDqpz6Q3bit4fJJN9PD4t8AK8JZVSa5u"
    tx.amount.value shouldBe 100000
    tx.fee.value shouldBe 100000
    tx.proofs.toSignature.toString shouldBe "4dPRTW6XyRQUTQwwpuZDCNy1UDHYG9WGsEQnn5v49Lj5uyh4XGDdwtEq3t6ZottweAXHieK32UokHwiTxGFtz9bQ"
  }

  test("AssetTransfer with a fee in an asset json parsing works") {
    val json =
      """
        |{
        |   "senderPublicKey":"FJuErRxhV9JaFUwcYLabFK5ENvDRfyJbRz8FeVfYpBLn",
        |   "recipient":"3N9UuGeWuDt9NfWbC5oEACHyRoeEMApXAeq",
        |   "timestamp":1489054107569,
        |   "assetId":"6MPKrD5B7GrfbciHECg1MwdvRUhRETApgNZspreBJ8JL",
        |   "amount":1000,
        |   "fee":100,
        |   "feeAssetId":"6MPKrD5B7GrfbciHECg1MwdvRUhRETApgNZspreBJ8JL",
        |   "signature":"UAhYXYdkFAFBuwAuUFP3yw7E8aRTyx56ZL4UPbT4ufomBzVLMRpdW2dCtJmfpCuPPMhGTvdzhXwb7o4ER6HAUpJ",
        |   "attachment":"2Kk7Zsr1e9jsqSBM5hpF"
        |}
      """.stripMargin
    val req = Json.parse(json).validate[SignedTransferV1Request].get
    req.recipient shouldBe "3N9UuGeWuDt9NfWbC5oEACHyRoeEMApXAeq"
    req.timestamp shouldBe 1489054107569L
    req.assetId shouldBe Some("6MPKrD5B7GrfbciHECg1MwdvRUhRETApgNZspreBJ8JL")
    req.feeAssetId shouldBe Some("6MPKrD5B7GrfbciHECg1MwdvRUhRETApgNZspreBJ8JL")
    req.amount shouldBe 1000
    req.fee shouldBe 100
    req.senderPublicKey shouldBe "FJuErRxhV9JaFUwcYLabFK5ENvDRfyJbRz8FeVfYpBLn"
    req.signature shouldBe "UAhYXYdkFAFBuwAuUFP3yw7E8aRTyx56ZL4UPbT4ufomBzVLMRpdW2dCtJmfpCuPPMhGTvdzhXwb7o4ER6HAUpJ"
    req.attachment shouldBe Some("2Kk7Zsr1e9jsqSBM5hpF")

    val tx = req.toTx.explicitGet()
    tx.sender.toString shouldBe "FJuErRxhV9JaFUwcYLabFK5ENvDRfyJbRz8FeVfYpBLn"
    tx.timestamp shouldBe 1489054107569L
    tx.attachment shouldBe ByteStr.decodeBase58("2Kk7Zsr1e9jsqSBM5hpF").get
    tx.assetId.maybeBase58Repr.get shouldBe "6MPKrD5B7GrfbciHECg1MwdvRUhRETApgNZspreBJ8JL"
    tx.feeAssetId.maybeBase58Repr.get shouldBe "6MPKrD5B7GrfbciHECg1MwdvRUhRETApgNZspreBJ8JL"
    tx.amount.value shouldBe 1000
    tx.fee.value shouldBe 100
    tx.proofs.toSignature.toString shouldBe "UAhYXYdkFAFBuwAuUFP3yw7E8aRTyx56ZL4UPbT4ufomBzVLMRpdW2dCtJmfpCuPPMhGTvdzhXwb7o4ER6HAUpJ"
  }
}
