package tech.hearth.protobuf.block

import com.google.protobuf.ByteString
import tech.hearth.protobuf.utils.PBUtils

private[block] object PBBlockSerialization {
  def signedBytes(block: PBBlock): Array[Byte] = {
    PBUtils.encodeDeterministic(block)
  }

  def unsignedBytes(block: PBBlock): Array[Byte] = {
    PBUtils.encodeDeterministic(block.withSignature(ByteString.EMPTY))
  }
}
