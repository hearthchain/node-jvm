package tech.hearth.state

case class AssetDescription(
    name: String,
    description: String,
    decimals: Int,
    totalVolume: BigInt,
    sequenceInBlock: Int,
    issueHeight: Height,
    minAssetFee: MinAssetFee
)
