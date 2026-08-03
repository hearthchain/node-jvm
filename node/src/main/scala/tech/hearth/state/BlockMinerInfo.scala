package tech.hearth.state

import tech.hearth.block.Block.BlockId
import tech.hearth.common.state.ByteStr

case class BlockMinerInfo(baseTarget: Long, generationSignature: ByteStr, timestamp: Long, blockId: BlockId)
