package com.wavesplatform.state

import com.wavesplatform.account.Address
import com.wavesplatform.crypto.bls.BlsPublicKey
import com.wavesplatform.transaction.BlockchainUpdater

class ForwardingBlockchainUpdaterImpl(delegate: CompleteBlockchainUpdater) extends Blockchain with BlockchainUpdater with NG {
  export delegate.{
    settings,
    height,
    finalizedHeight,
    finalizedHeightAt,
    score,
    blockHeader,
    hitSource,
    carryFee,
    heightOf,
    approvedFeatures,
    activatedFeatures,
    featureVotes,
    blockReward,
    wavesAmount,
    transactionInfo,
    transactionInfos,
    transactionMeta,
    transactionSnapshot,
    containsTransaction,
    assetDescription,
    leaseDetails,
    filledVolumeAndFee,
    balanceAtHeight,
    balanceSnapshots,
    leaseBalance,
    leaseBalances,
    balance,
    balances,
    wavesBalances,
    effectiveBalanceBanHeights,
    lastStateHash,
    processBlock,
    processMicroBlock,
    computeNextReward,
    removeAfter,
    lastBlockInfo,
    isLastBlockId,
    referencedBlockchain,
    shutdown,
    microBlock,
    bestLastBlockInfo,
    microblockIds,
    liquidBlock,
    liquidBlockSnapshot,
    microBlockSnapshot,
    liquidTransactions,
    liquidBlockMeta,
    bestLiquidSnapshot,
    bestLiquidSnapshotAndFees,
    snapshotBlockchain,
    currentGeneratorSet,
    conflictGenerators
  }

  override def committedGenerators(at: GenerationPeriod): IndexedSeq[CommittedGenerator] = delegate.committedGenerators(at)
}
