package tech.hearth.crypto.bls

import tech.hearth.common.utils.Base64
import tech.hearth.test.{FreeSpec, produce}
import org.scalatest.EitherValues

import java.nio.charset.StandardCharsets
import scala.util.Random

/** Whitebox coverage of BlsUtils' own Either-wrapping and BlsKeyPair's derivation. The underlying blst-backed
  * ciphersuite/point-arithmetic properties (sign/verify roundtrip, aggregate/subset rejection, malformed/infinity
  * public keys, keygen_v5's short-seed handling) are covered by tech.hearth:crypto's own BlsSignatureTest, since
  * that's now the only place touching blst directly.
  */
class BlsUtilsTest extends FreeSpec with EitherValues {
  private val kp1 = mkRandomKeyPair()
  private val pk1 = kp1.publicKey.arr

  private val kp2 = mkRandomKeyPair()
  private val pk2 = kp2.publicKey.arr

  private val kp3 = mkRandomKeyPair()
  private val pk3 = kp3.publicKey.arr

  private val message = "assertion".getBytes()

  private val sig1 = kp1.sign(message).arr
  private val sig2 = kp2.sign(message).arr
  private val sig3 = kp3.sign(message).arr

  "aggregation in verifyAgg" - {
    "signed with one" - {
      "verify with same" in {
        BlsUtils.verifyAgg(sig1, message, Seq(pk1)) should beRight
      }

      "verify with other" in {
        BlsUtils.verifyAgg(sig1, message, Seq(pk2)) should produce("Wrong BLS signature")
      }
    }

    "signed with multiple" - {
      "verify with one known" in {
        val aggSig = Seq(sig1, sig2).reduceLeft(aggSig2)
        BlsUtils.verifyAgg(aggSig, message, Seq(pk2)) should produce("Wrong BLS signature")
      }

      "verify with one unknown" in {
        val aggSig = Seq(sig1, sig2).reduceLeft(aggSig2)
        BlsUtils.verifyAgg(aggSig, message, Seq(pk3)) should produce("Wrong BLS signature")
      }

      "verify with all" in {
        val aggSig = Seq(sig1, sig2).reduceLeft(aggSig2)
        BlsUtils.verifyAgg(aggSig, message, Seq(pk1, pk2)) should beRight
      }

      "verify with all and unknown" in {
        val aggSig = Seq(sig1, sig2).reduceLeft(aggSig2)
        BlsUtils.verifyAgg(aggSig, message, Seq(pk1, pk2, pk3)) should produce("Wrong BLS signature")
      }

      "verify with less" in {
        val aggSig = Seq(sig1, sig2, sig3).reduceLeft(aggSig2)
        BlsUtils.verifyAgg(aggSig, message, Seq(pk1, pk2)) should produce("Wrong BLS signature")
      }
    }

    "aggregation of two same signatures" in {
      val aggSig = aggSig2(aggSig2(sig1, sig2), sig1)

      BlsUtils.verifyAgg(aggSig, message, Seq(pk1, pk2, pk1)) should beRight
      BlsUtils.verifyAgg(aggSig, message, Seq(pk1, pk2)) should produce("Wrong BLS signature")
    }

    "different order of signatures and keys" in {
      val aggSig = aggSig2(sig1, sig2)
      BlsUtils.verifyAgg(aggSig, message, Seq(pk2, pk1)) should beRight
    }

    "associativity" in {
      val aggSig = Seq(sig1, sig2, sig3).reduceLeft(aggSig2)
      BlsUtils.verifyAgg(aggSig, message, Seq(pk2, pk1, pk3)) should beRight
    }
  }

  "signBasic" - {
    "zero message" in {
      val message = Array.emptyByteArray
      val sig     = kp1.sign(message).arr
      BlsUtils.verifyBasic(sig, message, pk1) should beRight
    }
  }

  "verifyBasic" - {
    "same pk" in {
      BlsUtils.verifyBasic(sig1, message, pk1) should beRight
    }

    "other pk" in {
      BlsUtils.verifyBasic(sig1, message, pk2) should produce("Wrong BLS signature")
    }
  }

  "zero secret/public keys and signatures" - {
    val message = "test".getBytes()

    // A seed shorter than 32 bytes collapses to the zero scalar in BlsKeyPair.fromSeed's underlying keygen
    // (still zero if even less bytes); its public key is the point at infinity, and its signature is the point
    // at infinity in G2 too, since scalar multiplication by zero is the group identity regardless of message.
    val zeroKp  = BlsKeyPair.fromSeed(Array.fill[Byte](31)(1))
    val zeroPk  = zeroKp.publicKey.arr
    val zeroSig = zeroKp.sign(message).arr

    val okKp  = BlsKeyPair.fromSeed(Array.fill[Byte](32)(0))
    val okPk  = okKp.publicKey.arr
    val okSig = okKp.sign(message).arr

    "can't validate an all-zero byte string as a public key" in {
      BlsUtils.validatePublicKey(Array.fill[Byte](BlsUtils.PublicKeySizeInBytes)(0)) should produce("Invalid BLS public key")
    }

    "zeroPk is well-formed but invalid (point at infinity)" in {
      BlsUtils.validatePublicKey(zeroPk) should produce("Invalid BLS public key")
    }

    "zeroSig not verified" - {
      "by zeroPk" in {
        BlsUtils.verifyBasic(zeroSig, message, zeroPk) should produce("Wrong BLS signature")
      }

      "by okPk" in {
        BlsUtils.verifyBasic(zeroSig, message, okPk) should produce("Wrong BLS signature")
      }
    }

    "okSig not verified by zeroPk" in {
      BlsUtils.verifyBasic(okSig, message, zeroPk) should produce("Wrong BLS signature")
    }

    "aggregate verification still succeeds when one signer's key is the degenerate zero key" in {
      val aggSig = aggSig2(zeroSig, okSig)
      BlsUtils.verifyAgg(aggSig, message, Seq(zeroPk, okPk)) should beRight
    }
  }

  "expected public keys" in forAll(
    Table(
      ("seed", "expected pk in base64"),
      (
        "-EXACTLY-32-BYTES-LENGTH-STRING-",
        "qSUdS6J92V1nNOdx4TafRu4U17qhqwVXKNyy2IVV9GWnUzUYlk/uH4l8fOoupSJj"
      ),
      (
        "a string longer than 32 bytes is used as the seed here",
        "o2DzLHA7PG7BvHXTqnz4c8arX/tjiU11YuHsQnfUH0Lo/+ksy1toSYXFFy5auEJT"
      )
    )
  ) { (seed, expectedPkInBase64) =>
    val pk = BlsKeyPair.fromSeed(seed.getBytes(StandardCharsets.UTF_8)).publicKey.arr
    Base64.encode(pk) shouldBe expectedPkInBase64
  }

  private def aggSig2(sig1: Array[Byte], sig2: Array[Byte]): Array[Byte] = BlsUtils.aggSig(Seq(sig1, sig2)).value

  private def mkRandomKeyPair(): BlsKeyPair = BlsKeyPair.fromSeed(Array.fill(32)(Random.nextInt().toByte))
}
