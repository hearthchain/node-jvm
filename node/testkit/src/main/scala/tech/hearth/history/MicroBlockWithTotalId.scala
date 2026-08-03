package tech.hearth.history

import tech.hearth.block.Block.BlockId
import tech.hearth.block.MicroBlock

class MicroBlockWithTotalId(val microBlock: MicroBlock, val totalBlockId: BlockId)
object MicroBlockWithTotalId {
  implicit def toMicroBlock(mb: MicroBlockWithTotalId): MicroBlock = mb.microBlock
}
