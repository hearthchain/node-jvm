package tech.hearth.network

import tech.hearth.common.state.ByteStr
import tech.hearth.crypto.*
import tech.hearth.test.FreeSpec
import org.scalacheck.Gen

class MicroBlockInvSpecSpec extends FreeSpec {

  private val microBlockInvGen: Gen[MicroBlockInv] = for {
    acc          <- accountGen
    totalSig     <- byteArrayGen(SignatureLength)
    prevBlockSig <- byteArrayGen(SignatureLength)
  } yield MicroBlockInv(acc, ByteStr(totalSig), ByteStr(prevBlockSig))

  "MicroBlockInvMessageSpec" - {
    import MicroBlockInvSpec.*

    "deserializeData(serializedData(data)) == data" in forAll(microBlockInvGen) { inv =>
      inv.signaturesValid() should beRight
      val restoredInv = deserializeData(serializeData(inv)).get
      restoredInv.signaturesValid() should beRight

      restoredInv shouldBe inv
    }
  }

}
