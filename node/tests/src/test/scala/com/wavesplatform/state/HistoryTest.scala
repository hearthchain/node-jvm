package com.wavesplatform.state

import com.wavesplatform.account.PublicKey
import com.wavesplatform.block.Block
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2.*
import com.wavesplatform.crypto.*
import com.wavesplatform.db.WithState
import com.wavesplatform.lagonaki.mocks.TestBlock
import com.wavesplatform.transaction.{BlockchainUpdater, TxHelpers}
import tech.hearth.crypto.Ecvrf

trait HistoryTest {
  val genesisBlock: Block = TestBlock.withReference(ByteStr(Array.fill(SignatureLength)(0: Byte))).block

  /** The VRF proof the next block has to carry.
    *
    * A block is only accepted if its generation signature is a proof made with the VRF key its generator committed
    * (`defaultVrfKey`, for `TestBlock.defaultSigner`) over the hit source of the referenced height - and that hit source
    * is taken 100 blocks back once the chain is that long. TestBlock fills in a random value instead, which cannot
    * verify, so the proof is built here.
    */
  private def generationSignature(blockchain: BlockchainUpdater & Blockchain): ByteStr = {
    val parentHeight  = blockchain.height
    val prevHitSource = blockchain.hitSource(if (parentHeight > 100) parentHeight - 100 else parentHeight).get
    ByteStr(Ecvrf.prove(TxHelpers.defaultVrfKey, prevHitSource.arr).proof().bytes())
  }

  /** Light-node block fields are always present now, so every appended block has to carry a state hash the differ
    * agrees with - TestBlock alone cannot compute one, since it needs the blockchain the block is applied to.
    */
  private def nextBlock(blockchain: BlockchainUpdater & Blockchain, features: Seq[Short]): Block =
    WithState
      .blockWithComputedStateHash(
        Block.create(
          timestamp = 0,
          reference = blockchain.lastBlockId.get,
          baseTarget = 2L,
          generationSignature = generationSignature(blockchain),
          generator = PublicKey(TestBlock.defaultSigner.publicKey),
          featureVotes = features,
          transactionData = Seq.empty,
          stateHash = None,
          challengedHeader = None,
          finalizationVoting = None
        ),
        TestBlock.defaultSigner,
        blockchain
      )
      .resultE
      .explicitGet()

  def getNextTestBlock(blockchain: BlockchainUpdater & Blockchain): Block =
    nextBlock(blockchain, Seq.empty)

  def getNextTestBlockWithVotes(blockchain: BlockchainUpdater & Blockchain, votes: Seq[Short]): Block =
    nextBlock(blockchain, votes)
}
