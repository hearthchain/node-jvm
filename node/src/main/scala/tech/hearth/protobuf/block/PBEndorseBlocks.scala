package tech.hearth.protobuf.block

import tech.hearth.block.BlockEndorsement
import tech.hearth.crypto.bls.BlsSignature
import tech.hearth.protobuf.*
import tech.hearth.state.{GeneratorIndex, Height}

object PBEndorseBlocks {
  def vanilla(x: PBEndorseBlock, sig: BlsSignature): BlockEndorsement = BlockEndorsement(
    GeneratorIndex(x.endorserIndex),
    x.finalizedBlockId.toByteStr,
    Height(x.finalizedBlockHeight),
    x.endorsedBlockId.toByteStr,
    sig
  )

  def protobuf(x: BlockEndorsement): PBEndorseBlock = PBEndorseBlock.of(
    endorserIndex = x.endorserIndex.toInt,
    finalizedBlockId = x.finalizedId.toByteString,
    finalizedBlockHeight = x.finalizedHeight.toInt,
    endorsedBlockId = x.endorsedId.toByteString,
    signature = x.signature.byteStr.toByteString
  )
}
