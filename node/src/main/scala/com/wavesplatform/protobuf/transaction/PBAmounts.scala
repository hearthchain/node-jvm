package com.wavesplatform.protobuf.transaction

import com.google.protobuf.ByteString
import com.wavesplatform.protobuf.*
import com.wavesplatform.state.BlockFee
import com.wavesplatform.transaction.Asset
import com.wavesplatform.transaction.Asset.{IssuedAsset, Waves}

object PBAmounts {
  def toPBAssetId(asset: Asset): ByteString = asset match {
    case Asset.IssuedAsset(id) => id.toByteString
    case Asset.Waves           => ByteString.EMPTY
  }

  def toVanillaAssetId(byteStr: ByteString): Asset = {
    if (byteStr.isEmpty) Waves
    else IssuedAsset(byteStr.toByteStr)
  }

  def fromAssetAndAmount(asset: Asset, amount: Long): Amount =
    Amount(toPBAssetId(asset), amount)

  def toAssetAndAmount(value: Amount): (Asset, Long) =
    (toVanillaAssetId(value.assetId), value.amount)

  def fromBlockFee(bf: BlockFee): Seq[Amount] =
    Seq(fromAssetAndAmount(Asset.Waves, bf.pf.balance)) ++ bf.pf.assets.map { case (i, a) => fromAssetAndAmount(i, a) }
}
