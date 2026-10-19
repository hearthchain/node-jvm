package tech.hearth.state

import tech.hearth.common.state.ByteStr
import tech.hearth.crypto.bls.BlsKeyPair
import tech.hearth.state.GeneratorEligibility.*
import tech.hearth.test.FreeSpec
import tech.hearth.transaction.TxHelpers

class GeneratorEligibilitySpec extends FreeSpec {
  private val committedAddress = TxHelpers.signer(0).toAddress
  private val outsiderAddress  = TxHelpers.signer(1).toAddress
  private val blsPublicKey     = BlsKeyPair.fromSeed(Array.fill(32)(1)).publicKey
  private val vrfPublicKey     = ByteStr.fill(32)(2)

  private val committed     = Seq(CommittedGenerator(committedAddress, blsPublicKey, ByteStr.fill(32)(3)))
  private val canGenerate   = Seq(GeneratorInfo(GeneratorIndex(0), committedAddress, blsPublicKey, 1))
  private val implicitByOne = Seq(ImplicitGenerator(outsiderAddress, vrfPublicKey))

  "a committed generator that can generate is Committed" in {
    GeneratorEligibility(committedAddress, committed, canGenerate, Nil) shouldBe Committed
  }

  "a committed generator that can't generate is denied, even when nobody else can" in {
    GeneratorEligibility(committedAddress, committed, Nil, Nil) shouldBe a[Denied]
  }

  "an uncommitted generator is denied while a committed one can generate" in {
    GeneratorEligibility(outsiderAddress, committed, canGenerate, Nil) shouldBe a[Denied]
  }

  "an uncommitted generator may generate once no committed one can" in {
    GeneratorEligibility(outsiderAddress, committed, Nil, Nil) shouldBe Uncommitted(Set(committed.head.vrfPublicKey))
  }

  "an implicit generator keeps its key after the committed generators recover" in {
    GeneratorEligibility(outsiderAddress, committed, canGenerate, implicitByOne) shouldBe Implicit(vrfPublicKey)
  }

  "a block" - {
    "by an uncommitted generator has to carry a VRF key" in {
      Uncommitted(Set.empty).check(outsiderAddress, None) shouldBe a[Left[?, ?]]
      Uncommitted(Set.empty).check(outsiderAddress, Some(vrfPublicKey)) shouldBe Right(())
    }

    "by a generator with a registered VRF key can't carry another" in {
      Committed.check(committedAddress, Some(vrfPublicKey)) shouldBe a[Left[?, ?]]
      Implicit(vrfPublicKey).check(outsiderAddress, Some(vrfPublicKey)) shouldBe a[Left[?, ?]]
      Implicit(vrfPublicKey).check(outsiderAddress, None) shouldBe Right(())
    }
  }
}
