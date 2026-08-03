package tech.hearth.database

import tech.hearth.block.Block
import tech.hearth.common.state.ByteStr
import tech.hearth.state.{BlockFee, GeneratorSet, Height, StateSnapshot}
import tech.hearth.transaction.DiscardedBlocks

trait Storage {
  def append(
      snapshot: StateSnapshot,
      carryFee: BlockFee,
      totalFee: BlockFee,
      reward: Option[Long],
      hitSource: ByteStr,
      computedBlockStateHash: ByteStr,
      block: Block,
      newFinalizedHeight: Height,
      generatorSet: GeneratorSet
  ): Unit
  def lastBlock: Option[Block]
  def rollbackTo(height: Height): Either[String, DiscardedBlocks]
  def safeRollbackHeight: Height
}
