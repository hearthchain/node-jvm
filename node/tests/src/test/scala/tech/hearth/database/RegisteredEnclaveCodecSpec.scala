package tech.hearth.database

import tech.hearth.common.state.ByteStr
import tech.hearth.state.RegisteredEnclave
import tech.hearth.test.PropSpec
import tech.hearth.transaction.TxHelpers
import org.scalacheck.Gen

/** readRegisteredEnclaves/writeRegisteredEnclaves round-trip: fixed-width records of enclave key (32 bytes) ++
  * validator ++ operator addresses. The record layout changed when registration moved from the quote's attestation
  * key to the enclave's ephemeral key and gained the operator field, so the codec gets its own test.
  */
class RegisteredEnclaveCodecSpec extends PropSpec {
  private val enclaveGen: Gen[RegisteredEnclave] = for {
    keyBytes  <- Gen.containerOfN[Array, Byte](32, Gen.choose[Byte](Byte.MinValue, Byte.MaxValue))
    validator <- Gen.choose(0, 9).map(TxHelpers.signer(_).toAddress)
    operator  <- Gen.choose(0, 9).map(TxHelpers.signer(_).toAddress)
  } yield RegisteredEnclave(ByteStr(keyBytes), validator, operator)

  property("round-trips an empty sequence") {
    readRegisteredEnclaves(writeRegisteredEnclaves(Seq.empty)) shouldBe Seq.empty
  }

  property("round-trips multiple records, in order") {
    forAll(Gen.listOf(enclaveGen)) { values =>
      readRegisteredEnclaves(writeRegisteredEnclaves(values)) shouldBe values
    }
  }
}
