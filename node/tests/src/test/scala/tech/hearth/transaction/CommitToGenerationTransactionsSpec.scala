package tech.hearth.transaction

import com.google.common.primitives.Ints
import tech.hearth.crypto.{Crypto, VrfKey}
import tech.hearth.account.{NetworkId, PublicKey}
import tech.hearth.common.state.ByteStr
import tech.hearth.common.utils.Base16
import tech.hearth.common.utils.EitherExt2.*
import tech.hearth.crypto.bls.{BlsKeyPair, BlsPublicKey}
import tech.hearth.db.WithDomain
import tech.hearth.state.Height
import tech.hearth.test.*
import tech.hearth.transaction.serialization.impl.PBTransactionSerializer
import play.api.libs.json.Json

import scala.util.{Failure, Success}

class CommitToGenerationTransactionsSpec extends FreeSpec with WithDomain {
  private val blsKp  = BlsKeyPair.fromSeed(Crypto.defaultBackend().sha256(Ints.toByteArray(0)))
  private val sig    = CommitToGenerationTransaction.mkPopSignature(blsKp, Height(3000))
  private val vrfKey = VrfKey.fromSeed(Crypto.defaultBackend().sha256(Ints.toByteArray(0)))
  private val vrfPk  = ByteStr(vrfKey.publicKey())
  private val vrfSig = CommitToGenerationTransaction.mkVrfPopSignature(vrfKey, Height(3000))

  private val origTx = CommitToGenerationTransaction(
    sender = PublicKey.fromBase16String("d528aabec35ca100d87c7b7a128632faf19cd44531819457445113a32a21ef22").explicitGet(),
    endorserPublicKey =
      BlsPublicKey(Base16.decode("8dbf77df79479e11e99011f5ebc66b0d160ceddf2fcecfade325f07e96213f0d5d47a9af5a7ce3314f532e6aa03a33db")).explicitGet(),
    generationPeriodStart = Height(3000),
    timestamp = 1526287561757L,
    fee = TxPositiveAmount.unsafeFrom(100000000),
    commitmentSignature = sig,
    vrfPublicKey = vrfPk,
    vrfCommitmentSignature = vrfSig,
    proofs = Proofs(
      ByteStr
        .decodeBase16(
          "38b23a9854990fde3dce854aa91b78f120cd3674e858efaf52c9ad09d7a2c7bc07e514243b061042f4048505bf7e63429acae66d6d8832ded21a39c2bcf5bd81"
        )
        .get
    ),
    networkId = NetworkId.current
  )

  "JSON parsing" in {
    val js = Json.parse(s"""{
      "id": "a86a11189e58104e702a2c06444127c89cf46b4cecf52b37a53165e804b15636",
      "type": 6,
      "fee": 100000000,
      "feeAssetId": null,
      "timestamp": 1526287561757,
      "sender": "thrth1ryd2f987gg464uf4q5jte5rcmc2xgq6kr3qe39",
      "senderPublicKey": "d528aabec35ca100d87c7b7a128632faf19cd44531819457445113a32a21ef22",
      "generationPeriodStart": 3000,
      "endorserPublicKey": "8dbf77df79479e11e99011f5ebc66b0d160ceddf2fcecfade325f07e96213f0d5d47a9af5a7ce3314f532e6aa03a33db",
      "vrfPublicKey": "$vrfPk",
      "commitmentSignature": "$sig",
      "vrfCommitmentSignature": "$vrfSig",
      "proofs": [
        "38b23a9854990fde3dce854aa91b78f120cd3674e858efaf52c9ad09d7a2c7bc07e514243b061042f4048505bf7e63429acae66d6d8832ded21a39c2bcf5bd81"
      ],
      "networkId": "thrth"
    }""")
    origTx.json() shouldEqual js
  }

  "PB roundtrip" in {
    PBTransactionSerializer.parseBytes(PBTransactionSerializer.bytes(origTx)) match {
      case Success(tx: CommitToGenerationTransaction) =>
        tx shouldBe origTx
        tx.proofs shouldBe origTx.proofs
      case Success(tx)        => fail(s"Unexpected transaction type: ${tx.tpe.transactionName}")
      case Failure(exception) => fail(exception)
    }
  }

  "Expected BLS key and PoP" in {
    // A fixed 32-byte seed. It used to be wrapped in a `PrivateKey`, which is a 64-byte expanded Ed25519 secret key
    // now and rejects it - and `BlsKeyPair.fromSeed` wants the raw bytes anyway
    val seed = ByteStr.decodeBase16("602bf6b32b3fc64a60093314116817796498787fa834d0d8cc033dd2c85c916d").get

    val blsKp = BlsKeyPair.fromSeed(seed.arr)
    blsKp.publicKey.byteStr.base64Raw shouldBe "jrugi0W0es2WxuHoptQtchqwactZsldOGucYObZrEIOpxbWmhL8dodvpnzA+2qUf"

    CommitToGenerationTransaction.mkPopSignature(blsKp, Height(1001)).byteStr.base64Raw shouldBe
      "sOlLZL2RZZ3c98PmUvKSN960aj+VJwyVGEUygI78mGDwGJflJWLHCwuqiYk1fRG7FOCJKOtKbKOG7tBykQ5iTcRu+7eLWhiodJw47YEfDOZHNwkl8dQwgxAam8+3BEvX"
  }
}
