package tech.hearth.database

import tech.hearth.common.state.ByteStr
import tech.hearth.test.PropSpec
import org.scalacheck.Gen

/** readByteStrSeq/writeByteStrSeq (see database/package.scala, used for DCAP TCB Info FMSPC rollback bookkeeping)
  * mirror readStrings/writeStrings' length-prefixed encoding byte-for-byte, but had no dedicated round-trip test of
  * their own - only exercised indirectly, and only ever with a single 6-byte FMSPC, via
  * UpdateCollateralTransactionDiffTest's rollback case.
  */
class ByteStrSeqCodecSpec extends PropSpec {
  private val byteStrGen: Gen[ByteStr] = Gen.choose(0, 64).flatMap(n => Gen.containerOfN[Array, Byte](n, Gen.posNum[Byte])).map(ByteStr(_))

  property("round-trips an empty sequence") {
    readByteStrSeq(writeByteStrSeq(Seq.empty)) shouldBe Seq.empty
  }

  property("round-trips a single element") {
    forAll(byteStrGen) { value =>
      readByteStrSeq(writeByteStrSeq(Seq(value))) shouldBe Seq(value)
    }
  }

  property("round-trips multiple elements, in order") {
    forAll(Gen.listOf(byteStrGen)) { values =>
      readByteStrSeq(writeByteStrSeq(values)) shouldBe values
    }
  }

  property("readByteStrSeq treats a null array as empty, matching readStrings") {
    readByteStrSeq(null) shouldBe Seq.empty
  }
}
