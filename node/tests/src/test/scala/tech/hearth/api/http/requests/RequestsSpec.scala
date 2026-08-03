package tech.hearth.api.http.requests

import tech.hearth.account.PublicKey
import tech.hearth.common.utils.EitherExt2.*
import tech.hearth.test.FreeSpec
import org.scalacheck.Gen
import org.scalatest.OptionValues
import play.api.libs.json.*
import tech.hearth.crypto.SigningKey

class RequestsSpec extends FreeSpec with OptionValues {
  private def transferRequestGen(version: Int): Gen[(SigningKey, JsObject)] =
    (for {
      sender    <- accountGen
      recipient <- accountGen
      proofs    <- proofsGen
    } yield (
      sender,
      Json.obj(
        "type"            -> 4,
        "version"         -> version,
        "senderPublicKey" -> PublicKey(sender.publicKey).toString,
        "assetId"         -> JsNull,
        "attachment"      -> "",
        "feeAssetId"      -> JsNull,
        "timestamp"       -> System.currentTimeMillis(),
        "fee"             -> 100000,
        "amount"          -> 10000,
        "recipient"       -> recipient.toAddress.toBech32,
        "proofs"          -> JsArray(proofs.proofs.map(p => JsString(p.toString)))
      )
    )).label(s"Transfer Request v$version")

  "TransferRequest" - {
    "accepts proofs for version >= 2" in {
      Seq(2, 3).foreach { version =>
        forAll(transferRequestGen(version)) { case (_, json) =>
          val request = json.as[TransferRequest]
          val tx      = request.toTx.explicitGet()

          request.proofs.value should be(tx.proofs)
        }
      }

    }
  }
}
