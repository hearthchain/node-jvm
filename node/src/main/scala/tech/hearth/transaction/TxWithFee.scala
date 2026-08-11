package tech.hearth.transaction

import tech.hearth.transaction.Asset.Hearth

sealed trait TxWithFee {
  def fee: TxPositiveAmount
  def assetFee: (Asset, Long) // TODO: Delete or rework
}

object TxWithFee {
  trait InHearth extends TxWithFee {
    override def assetFee: (Asset, Long) = (Hearth, fee.value)
  }

  trait InCustomAsset extends TxWithFee {
    def feeAssetId: Asset
    override def assetFee: (Asset, Long) = (feeAssetId, fee.value)
  }
}
