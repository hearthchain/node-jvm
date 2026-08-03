package tech.hearth

import tech.hearth.account.{Address, PublicKey}
import tech.hearth.common.state.ByteStr
import tech.hearth.state.{AssetDescription, Height, TransactionId}
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

  val assetDescription: AssetDescription = AssetDescription(
    TransactionId(asset.id),
    PublicKey(TxHelpers.defaultSigner.publicKey),
    null,
    null,
    0,
    reissuable = true,
    BigInt(1),
    Height(1),
    nft = false,
    0,
    Height(1)
  )
}
