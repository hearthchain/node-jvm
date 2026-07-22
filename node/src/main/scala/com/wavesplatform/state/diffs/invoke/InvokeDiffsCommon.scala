package com.wavesplatform.state.diffs.invoke

import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.state.Blockchain
import com.wavesplatform.transaction.Asset.IssuedAsset
import com.wavesplatform.transaction.AssetIdLength

object InvokeDiffsCommon {

  def checkAsset(blockchain: Blockchain, assetId: ByteStr): Either[String, Unit] =
    if (assetId.size != AssetIdLength)
      Left(s"Transfer error: invalid asset ID '$assetId' length = ${assetId.size} bytes, must be $AssetIdLength")
    else if (blockchain.assetDescription(IssuedAsset(assetId)).isEmpty)
      Left(s"Transfer error: asset '$assetId' is not found on the blockchain")
    else
      Right(())
}
