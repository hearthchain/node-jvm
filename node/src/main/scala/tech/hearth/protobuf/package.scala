package tech.hearth

import com.google.protobuf.ByteString
import tech.hearth.account.{Address, PublicKey}
import tech.hearth.common.state.ByteStr
import tech.hearth.protobuf.transaction.PBRecipients
import tech.hearth.state.TransactionId
import tech.hearth.transaction.Asset
import tech.hearth.transaction.Asset.{IssuedAsset, Hearth}

import scala.annotation.targetName

package object protobuf {
  extension (bs: ByteStr) def toByteString: ByteString = ByteString.copyFrom(bs.arr)

  extension (txId: TransactionId) {
    @targetName("txIdToByteString") def toByteString: ByteString = ByteString.copyFrom(txId.arr)
  }

  extension (a: Address) def toByteString: ByteString = ByteString.copyFrom(a.toBytes)

  extension (pk: PublicKey) {
    @targetName("publicKeyToByteString") def toByteString: ByteString = ByteString.copyFrom(pk.arr)
  }

  extension (bs: ByteString) {
    def toByteStr: ByteStr           = ByteStr(bs.toByteArray)
    def toTxId: TransactionId        = TransactionId(toByteStr)
    def toIssuedAssetId: IssuedAsset = IssuedAsset(ByteStr(bs.toByteArray))
    def toAssetId: Asset             = if (bs.isEmpty) Hearth else toIssuedAssetId
    def toPublicKey: PublicKey       = PublicKey(bs.toByteArray)
    def toAddress: Address =
      PBRecipients
        .toAddress(bs.toByteArray)
        .fold(ve => throw new IllegalArgumentException(ve.toString), identity)
    def toIssuedAsset: Asset.IssuedAsset = Asset.IssuedAsset(toByteStr)
  }
}
