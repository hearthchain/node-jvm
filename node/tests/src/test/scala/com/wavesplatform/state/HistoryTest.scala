package com.wavesplatform.state

import com.wavesplatform.block.Block
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2.*
import com.wavesplatform.crypto.*
import com.wavesplatform.db.WithState
import com.wavesplatform.lagonaki.mocks.TestBlock
import com.wavesplatform.transaction.BlockchainUpdater

trait HistoryTest {
  val genesisBlock: Block = TestBlock.withReference(ByteStr(Array.fill(SignatureLength)(0: Byte))).block

  /** Light-node block fields are always present now, so every appended block has to carry a state hash the differ
    * agrees with - TestBlock alone cannot compute one, since it needs the blockchain the block is applied to.
    */
  private def withStateHash(block: Block, blockchain: BlockchainUpdater & Blockchain): Block =
    WithState.blockWithComputedStateHash(block, TestBlock.defaultSigner, blockchain).resultE.explicitGet()

  def getNextTestBlock(blockchain: BlockchainUpdater & Blockchain): Block =
    withStateHash(TestBlock.withReference(blockchain.lastBlockId.get).block, blockchain)

  def getNextTestBlockWithVotes(blockchain: BlockchainUpdater & Blockchain, votes: Seq[Short]): Block =
    withStateHash(TestBlock.withReferenceAndFeatures(blockchain.lastBlockId.get, votes).block, blockchain)
}
