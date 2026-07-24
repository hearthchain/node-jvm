package com.wavesplatform.mining

import com.wavesplatform.block.Block
import com.wavesplatform.settings.MinerSettings
import com.wavesplatform.state.Blockchain

case class MiningConstraints(total: MiningConstraint, keyBlock: MiningConstraint, micro: MiningConstraint)

object MiningConstraints {
  val MaxTxsSizeInBytes: Int = 1 * 1024 * 1024 // 1 megabyte

  def apply(blockchain: Blockchain, height: Int, minerSettings: Option[MinerSettings] = None): MiningConstraints =
    new MiningConstraints(
      total = OneDimensionalMiningConstraint(MaxTxsSizeInBytes, TxEstimators.sizeInBytes, "MaxTxsSizeInBytes"),
      keyBlock = OneDimensionalMiningConstraint(0, TxEstimators.one, "MaxTxsInKeyBlock"),
      micro =
        if (minerSettings.isDefined)
          OneDimensionalMiningConstraint(minerSettings.get.maxTransactionsInMicroBlock, TxEstimators.one, "MaxTxsInMicroBlock")
        else MiningConstraint.Unlimited
    )
}
