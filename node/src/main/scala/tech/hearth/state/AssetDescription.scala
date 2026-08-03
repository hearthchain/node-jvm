package tech.hearth.state

import com.google.protobuf.ByteString
import tech.hearth.account.PublicKey

case class AssetDescription(
    originTransactionId: TransactionId,
    issuer: PublicKey,
    name: ByteString,
    description: ByteString,
    decimals: Int,
    reissuable: Boolean,
    totalVolume: BigInt,
    lastUpdatedAt: Height,
    nft: Boolean,
    sequenceInBlock: Int,
    issueHeight: Height
)
