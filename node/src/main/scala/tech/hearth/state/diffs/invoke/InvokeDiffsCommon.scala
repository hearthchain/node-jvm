package tech.hearth.state.diffs.invoke

import tech.hearth.common.state.ByteStr
import tech.hearth.state.Blockchain
import tech.hearth.transaction.Asset.IssuedAsset
import tech.hearth.transaction.AssetIdLength

object InvokeDiffsCommon {

  def checkAsset(blockchain: Blockchain, assetId: ByteStr): Either[String, Unit] =
    if (assetId.size != AssetIdLength)
      Left(s"Transfer error: invalid asset ID '$assetId' length = ${assetId.size} bytes, must be $AssetIdLength")
    else if (blockchain.assetDescription(IssuedAsset(assetId)).isEmpty)
      Left(s"Transfer error: asset '$assetId' is not found on the blockchain")
    else
      Right(())
}
