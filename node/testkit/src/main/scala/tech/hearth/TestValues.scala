package tech.hearth

import com.google.protobuf.ByteString
import tech.hearth.account.Address
import tech.hearth.common.state.ByteStr
import tech.hearth.state.{AssetDescription, Height, MinAssetFee}
import tech.hearth.state.diffs.FeeValidation.{FeeConstants, FeeUnit}
import tech.hearth.transaction.Asset.IssuedAsset
import tech.hearth.transaction.{TransactionType, TxHelpers}
import tech.hearth.crypto.SigningKey

object TestValues {
  val keyPair: SigningKey = TxHelpers.defaultSigner
  val address: Address    = keyPair.toAddress
  val asset: IssuedAsset  = IssuedAsset(ByteStr(("A" * 32).getBytes("ASCII")))
  val bigMoney: Long      = tech.hearth.state.diffs.ENOUGH_AMT
  val timestamp: Long     = System.currentTimeMillis()
  val fee: Long           = 1e6.toLong

  val commitToGenerationFee: Long = FeeConstants(TransactionType.CommitToGeneration) * FeeUnit

  val minAssetFee: MinAssetFee = MinAssetFee.unsafeFrom(fee)

  val assetDescription: AssetDescription = AssetDescription(
    ByteString.EMPTY,
    ByteString.EMPTY,
    0,
    BigInt(1),
    0,
    Height(1),
    minAssetFee
  )
}
