package tech.hearth.block

import tech.hearth.block.Block.BlockId
import tech.hearth.state.{StateSnapshot, TxMeta}

case class BlockSnapshot(blockId: BlockId, snapshots: Seq[(StateSnapshot, TxMeta.Status)])
