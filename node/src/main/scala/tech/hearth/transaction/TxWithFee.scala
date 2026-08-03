package tech.hearth.transaction

import tech.hearth.transaction.Asset.Waves

sealed trait TxWithFee {
  def fee: TxPositiveAmount
  def assetFee: (Asset, Long) // TODO: Delete or rework
}

object TxWithFee {
  trait InWaves extends TxWithFee {
    override def assetFee: (Asset, Long) = (Waves, fee.value)
  }

  trait InCustomAsset extends TxWithFee {
    def feeAssetId: Asset
    override def assetFee: (Asset, Long) = (feeAssetId, fee.value)
  }
}
