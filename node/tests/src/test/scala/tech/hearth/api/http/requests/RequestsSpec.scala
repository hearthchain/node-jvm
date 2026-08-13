package tech.hearth.api.http.requests

import tech.hearth.account.PublicKey
import tech.hearth.common.utils.EitherExt2.*
import tech.hearth.test.FreeSpec
import org.scalacheck.Gen
import org.scalatest.OptionValues
import play.api.libs.json.*
import tech.hearth.crypto.SigningKey

class RequestsSpec extends FreeSpec with OptionValues {
  private def transferRequestGen: Gen[(SigningKey, JsObject)] =
    (for {
      sender    <- accountGen
      recipient <- accountGen
      proofs    <- proofsGen
    } yield (
      sender,
      Json.obj(
        "type"            -> 2,
        "senderPublicKey" -> PublicKey(sender.publicKey).toString,
        "assetId"         -> JsNull,
        "attachment"      -> "",
        "feeAssetId"      -> JsNull,
        "timestamp"       -> System.currentTimeMillis(),
        "fee"             -> 100000,
        "transfers" -> Json.arr(
          Json.obj(
            "recipient" -> recipient.toAddress.toBech32,
            "amount"    -> 10000
          )
        ),
        "proofs" -> JsArray(proofs.proofs.map(p => JsString(p.toString)))
      )
    )).label("Transfer Request")

  "TransferRequest" - {
    "accepts proofs" in {
      forAll(transferRequestGen) { case (_, json) =>
        val request = json.as[TransferRequest]
        val tx      = request.toTx.explicitGet()

        request.proofs should be(tx.proofs)
      }
    }
  }
}
