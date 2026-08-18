package tech.hearth.transaction

import tech.hearth.account.PublicKey
import tech.hearth.common.state.ByteStr
import tech.hearth.common.utils.Base16
import tech.hearth.common.utils.EitherExt2.*
import tech.hearth.state.Height
import tech.hearth.test.FreeSpec
import play.api.libs.json.*

class TransactionFactorySpec extends FreeSpec {
  private def resourceBytes(name: String): Array[Byte] =
    getClass.getResourceAsStream(s"/dcap/$name").readAllBytes()

  // StartBoostTxValidator now structurally parses tdxQuote and rejects anything that isn't a well-formed TDX
  // quote (see StartBoostTransaction), so this needs a real quote fixture, not arbitrary bytes.
  private val tdxQuote = ByteStr(Base16.decode(new String(resourceBytes("quotev4.hex")).trim))

  // Large enough (4675 bytes raw, ~9350 hex chars) to exercise UpdateCollateralRequest's large-blob ByteStr
  // format (see requests.largeByteStrFormat) - a small value wouldn't reach the same 280-char decode-limit bug
  // StartBoostRequest hit.
  private val tcbInfo = ByteStr(resourceBytes("tcb_info_v3_sgx.json"))

  "parseRequest" - {
    "builds a StartBoostTransaction from a well-formed request" in forAll(accountGen, accountGen) { (sender, validatorKey) =>
      val validator = validatorKey.toAddress
      val json = Json.obj(
        "type"            -> TransactionType.StartBoost.id,
        "senderPublicKey" -> PublicKey(sender.publicKey()).toString,
        "validator"       -> validator.toString,
        // ByteStr.toString switches to a "base64:"-prefixed form at 1024+ bytes (a real quote is ~4935), which
        // the request-side decoder (utils.byteArrayFromString) doesn't accept - encode explicitly as base16.
        "tdxQuote"              -> Base16.encode(tdxQuote.arr),
        "generationPeriodStart" -> 100,
        "fee"                   -> 100000000
      )

      val tx = TransactionFactory.parseRequest(json).explicitGet()
      tx shouldBe a[StartBoostTransaction]
      val startBoost = tx.asInstanceOf[StartBoostTransaction]
      startBoost.validator shouldBe validator
      startBoost.tdxQuote shouldBe tdxQuote
      startBoost.generationPeriodStart shouldBe Height(100)
    }

    "builds an UpdateCollateralTransaction from a well-formed request" in forAll(accountGen) { sender =>
      val json = Json.obj(
        "type"            -> TransactionType.UpdateCollateral.id,
        "senderPublicKey" -> PublicKey(sender.publicKey()).toString,
        "tcbInfo"         -> Base16.encode(tcbInfo.arr),
        "fee"             -> 100000000
      )

      val tx = TransactionFactory.parseRequest(json).explicitGet()
      tx shouldBe a[UpdateCollateralTransaction]
      tx.asInstanceOf[UpdateCollateralTransaction].tcbInfo shouldBe Some(tcbInfo)
    }

    "rejects an UpdateCollateral request with no fields set" in forAll(accountGen) { sender =>
      val json = Json.obj(
        "type"            -> TransactionType.UpdateCollateral.id,
        "senderPublicKey" -> PublicKey(sender.publicKey()).toString,
        "fee"             -> 100000000
      )

      TransactionFactory.parseRequest(json) shouldBe a[Left[?, ?]]
    }

    "builds a ReserveTransaction from a well-formed request" in forAll(accountGen, accountGen) { (sender, minerKey) =>
      val miner = minerKey.toAddress
      val json = Json.obj(
        "type"            -> TransactionType.Reserve.id,
        "senderPublicKey" -> PublicKey(sender.publicKey()).toString,
        "amount"          -> 100000000,
        "miner"           -> miner.toString,
        "fee"             -> 100000
      )

      val tx = TransactionFactory.parseRequest(json).explicitGet()
      tx shouldBe a[ReserveTransaction]
      val reserve = tx.asInstanceOf[ReserveTransaction]
      reserve.miner shouldBe miner
      reserve.amount.value shouldBe 100000000
      reserve.assetId shouldBe Asset.Hearth
    }

    // A real HPKE-sealed API key envelope is well under 280 hex chars, but exercise BindApiKeyRequest's large-blob
    // ByteStr format anyway (see requests.largeByteStrFormat) - the same 280-char decode-limit bug StartBoostRequest
    // hit before its fields got the same treatment.
    "builds a BindApiKeyTransaction from a well-formed request, with an oversized encryptedApiKey" in forAll(accountGen) { sender =>
      val enclavePublicKey = ByteStr(Array.fill(32)(9: Byte))
      val encryptedApiKey  = ByteStr(Array.fill(200)(7: Byte))
      val json = Json.obj(
        "type"             -> TransactionType.BindApiKey.id,
        "senderPublicKey"  -> PublicKey(sender.publicKey()).toString,
        "enclavePublicKey" -> Base16.encode(enclavePublicKey.arr),
        "encryptedApiKey"  -> Base16.encode(encryptedApiKey.arr),
        "fee"              -> 100000
      )

      val tx = TransactionFactory.parseRequest(json).explicitGet()
      tx shouldBe a[BindApiKeyTransaction]
      val bindApiKey = tx.asInstanceOf[BindApiKeyTransaction]
      bindApiKey.enclavePublicKey shouldBe enclavePublicKey
      bindApiKey.encryptedApiKey shouldBe encryptedApiKey
    }

    "rejects a BindApiKey request with a malformed enclavePublicKey length" in forAll(accountGen) { sender =>
      val json = Json.obj(
        "type"             -> TransactionType.BindApiKey.id,
        "senderPublicKey"  -> PublicKey(sender.publicKey()).toString,
        "enclavePublicKey" -> Base16.encode(Array.fill(20)(9: Byte)),
        "encryptedApiKey"  -> Base16.encode(Array.fill(32)(7: Byte)),
        "fee"              -> 100000
      )

      TransactionFactory.parseRequest(json) shouldBe a[Left[?, ?]]
    }
  }
}
