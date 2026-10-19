package tech.hearth.block.serialization

import tech.hearth.account.PublicKey
import tech.hearth.block.BlockHeader
import tech.hearth.common.state.ByteStr
import tech.hearth.test.FreeSpec
import tech.hearth.transaction.TxHelpers

class BlockHeaderSerializerSpec extends FreeSpec {
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

  "JSON shows the VRF public key an uncommitted generator commits with" in {
    val vrfPublicKey = ByteStr(TxHelpers.defaultVrfKey.publicKey())
    (BlockHeaderSerializer.toJson(header.copy(vrfPublicKey = Some(vrfPublicKey))) \ "vrfPublicKey").as[String] shouldBe vrfPublicKey.toString
  }

  "JSON has no VRF public key for a committed generator's block" in {
    BlockHeaderSerializer.toJson(header).keys should not contain "vrfPublicKey"
  }
}
