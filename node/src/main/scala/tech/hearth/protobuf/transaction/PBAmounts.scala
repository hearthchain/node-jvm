package tech.hearth.protobuf.transaction

import com.google.protobuf.ByteString
import tech.hearth.protobuf.*
import tech.hearth.state.BlockFee
import tech.hearth.transaction.Asset
import tech.hearth.transaction.Asset.{IssuedAsset, Hearth}

object PBAmounts {
  def toPBAssetId(asset: Asset): ByteString = asset match {
    case Asset.IssuedAsset(id) => id.toByteString
    case Asset.Hearth          => ByteString.EMPTY
  }

  def toVanillaAssetId(byteStr: ByteString): Asset = {
    if (byteStr.isEmpty) Hearth
    else IssuedAsset(byteStr.toByteStr)
  }

  def fromAssetAndAmount(asset: Asset, amount: Long): Amount =
    Amount(toPBAssetId(asset), amount)

  def toAssetAndAmount(value: Amount): (Asset, Long) =
    (toVanillaAssetId(value.assetId), value.amount)

  def fromBlockFee(bf: BlockFee): Seq[Amount] =
    Seq(fromAssetAndAmount(Asset.Hearth, bf.pf.balance)) ++ bf.pf.assets.map { case (i, a) => fromAssetAndAmount(i, a) }
}
