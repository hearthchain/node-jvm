package tech.hearth.state

import com.google.protobuf.ByteString

case class AssetDescription(
    name: ByteString,
    description: ByteString,
    decimals: Int,
    totalVolume: BigInt,
    sequenceInBlock: Int,
    issueHeight: Height,
    minAssetFee: MinAssetFee
)
