package tech.hearth.database

import tech.hearth.common.state.ByteStr
import tech.hearth.crypto.DigestLength
import tech.hearth.protobuf.*

package object protobuf {
  implicit class BlockMetaExt(final val blockMeta: BlockMeta) extends AnyVal {
    def id: ByteStr =
      (if (blockMeta.headerHash.size() == DigestLength) blockMeta.headerHash else blockMeta.signature).toByteStr
  }
}
