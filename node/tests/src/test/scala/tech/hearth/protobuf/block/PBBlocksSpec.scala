package tech.hearth.protobuf.block

import tech.hearth.account.PublicKey
import tech.hearth.block.{BlockHeader, ChallengedHeader}
import tech.hearth.common.state.ByteStr
import tech.hearth.test.FreeSpec
import tech.hearth.transaction.TxHelpers

class PBBlocksSpec extends FreeSpec {
  private val vrfPublicKey = ByteStr(TxHelpers.defaultVrfKey.publicKey())

  private val header = BlockHeader(
    timestamp = 1000,
    reference = ByteStr.fill(32)(1),
    baseTarget = 100,
    generationSignature = ByteStr.fill(80)(2),
    generator = PublicKey(TxHelpers.defaultSigner.publicKey),
    featureVotes = Nil,
    transactionsRoot = ByteStr.fill(32)(3),
    stateHash = None,
    challengedHeader = None,
    finalizationVoting = None
  )

  "a header keeps the VRF public key an uncommitted generator commits with" in {
    val withKey = header.copy(vrfPublicKey = Some(vrfPublicKey))
    PBBlocks.vanilla(PBBlocks.protobuf(withKey)) shouldBe withKey
  }

  "a header without a VRF public key reads back without one" in {
    PBBlocks.vanilla(PBBlocks.protobuf(header)).vrfPublicKey shouldBe None
  }

  "a challenged header keeps the VRF public key of the block it replaces" in {
    val challenged = ChallengedHeader(
      timestamp = 1000,
      baseTarget = 100,
      generationSignature = ByteStr.fill(80)(2),
      featureVotes = Nil,
      generator = PublicKey(TxHelpers.signer(1).publicKey),
      stateHash = None,
      headerSignature = ByteStr.fill(64)(4),
      finalizationVoting = None,
      vrfPublicKey = Some(vrfPublicKey)
    )
    val withChallenge = header.copy(challengedHeader = Some(challenged))
    PBBlocks.vanilla(PBBlocks.protobuf(withChallenge)) shouldBe withChallenge
  }
}
