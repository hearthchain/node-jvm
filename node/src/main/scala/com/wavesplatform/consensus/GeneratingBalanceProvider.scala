package com.wavesplatform.consensus

import com.wavesplatform.block.Block.BlockId
import com.wavesplatform.state.Blockchain
import tech.hearth.crypto.Address

object GeneratingBalanceProvider {
  val MinimalEffectiveBalanceForGenerator1: Long = 1000000000000L
  val MinimalEffectiveBalanceForGenerator2: Long = 100000000000L

  private val SecondDepth = 1000

  def isMiningAllowed(generatingBalance: Long): Boolean =
    generatingBalance >= MinimalEffectiveBalanceForGenerator2

  def isGeneratingBalanceValid(balance: Long): Boolean =
    isMiningAllowed(balance)

  def balance(blockchain: Blockchain, account: Address, blockId: Option[BlockId] = None): Long = {
    val height = blockId.flatMap(blockchain.heightOf).getOrElse(blockchain.height)
    val depth  = SecondDepth

    val maybeChallengedMiner = blockchain.blockHeader(height + 1).flatMap(_.header.challengedHeader).map(_.generator.toAddress)
    blockchain.effectiveBalance(account, depth, blockId) + maybeChallengedMiner.map(blockchain.effectiveBalance(_, depth, blockId)).getOrElse(0L)
  }
}
