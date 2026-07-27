package com.wavesplatform.database

import com.wavesplatform.block.Block
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.state.{BlockFee, GeneratorSet, Height, StateSnapshot}
import com.wavesplatform.transaction.DiscardedBlocks

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
