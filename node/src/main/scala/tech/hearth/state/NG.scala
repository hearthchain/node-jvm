package tech.hearth.state

import tech.hearth.api.BlockMeta
import tech.hearth.block.Block.BlockId
import tech.hearth.block.{Block, MicroBlock}
import tech.hearth.transaction.Transaction

trait NG {
  def microBlock(totalBlockId: BlockId): Option[MicroBlock]

  def bestLastBlockInfo(maxMicroblockTimestampMs: Long): Option[BlockMinerInfo]

  def microblockIds: Seq[BlockId]

  def liquidBlock(totalBlockId: BlockId): Option[Block]

  def liquidBlockSnapshot(totalBlockId: BlockId): Option[StateSnapshot]

  def microBlockSnapshot(totalBlockId: BlockId): Option[StateSnapshot]

  def liquidTransactions(totalBlockId: BlockId): Option[Seq[(TxMeta, Transaction)]]

  def liquidBlockMeta: Option[BlockMeta]

  def bestLiquidSnapshot: Option[StateSnapshot]

  def bestLiquidSnapshotAndFees: Option[(StateSnapshot, BlockFee, BlockFee)]

  def snapshotBlockchain: SnapshotBlockchain

  def currentGeneratorSet: Option[GeneratorSet]
}
