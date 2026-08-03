package tech.hearth.mining

import tech.hearth.settings.MinerSettings

case class MiningConstraints(total: MiningConstraint, keyBlock: MiningConstraint, micro: MiningConstraint)

object MiningConstraints {
  val MaxTxsSizeInBytes: Int = 1 * 1024 * 1024 // 1 megabyte

  def apply(minerSettings: Option[MinerSettings] = None): MiningConstraints =
    new MiningConstraints(
      total = OneDimensionalMiningConstraint(MaxTxsSizeInBytes, TxEstimators.sizeInBytes, "MaxTxsSizeInBytes"),
      keyBlock = OneDimensionalMiningConstraint(0, TxEstimators.one, "MaxTxsInKeyBlock"),
      micro =
        if (minerSettings.isDefined)
          OneDimensionalMiningConstraint(minerSettings.get.maxTransactionsInMicroBlock, TxEstimators.one, "MaxTxsInMicroBlock")
        else MiningConstraint.Unlimited
    )
}
