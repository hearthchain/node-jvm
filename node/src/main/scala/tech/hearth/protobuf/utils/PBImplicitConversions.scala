package tech.hearth.protobuf.utils

import tech.hearth.lang.ValidationError
import tech.hearth.protobuf.transaction.*
import tech.hearth.protobuf.*
import tech.hearth.transaction.Asset
import tech.hearth.transaction.Asset.{IssuedAsset, Waves}
import tech.hearth.crypto.Address

object PBImplicitConversions {
  import com.google.protobuf.ByteString as PBByteString

  // The canonical on-chain form is the 21-byte payload (version || hash); see PBRecipients.toAddress, which parses it back
  extension (a: Address) def toPB: Recipient = Recipient.of(PBByteString.copyFrom(a.toBytes()))

  extension (r: Recipient) def toAddress: Either[ValidationError, Address] = PBRecipients.toAddress(r)

  implicit def fromAssetIdAndAmount(v: (VanillaAssetId, Long)): Amount = v match {
    case (IssuedAsset(assetId), amount) =>
      Amount()
        .withAssetId(assetId.toByteString)
        .withAmount(amount)

    case (Waves, amount) =>
      Amount().withAmount(amount)
  }

  implicit class AmountImplicitConversions(val a: Amount) extends AnyVal {
    def longAmount: Long      = a.amount
    def vanillaAssetId: Asset = PBAmounts.toVanillaAssetId(a.assetId)
  }
}
