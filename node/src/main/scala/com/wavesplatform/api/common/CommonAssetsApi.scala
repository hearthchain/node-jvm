package com.wavesplatform.api.common

import com.wavesplatform.account.Address
import com.wavesplatform.api.common.CommonAssetsApi.AssetInfo
import com.wavesplatform.crypto
import com.wavesplatform.database.{AddressId, KeyTag}
import com.wavesplatform.state.{AssetDescription, Blockchain, StateSnapshot}
import com.wavesplatform.transaction.Asset.{IssuedAsset, Waves}
import monix.reactive.Observable
import org.rocksdb.RocksDB

trait CommonAssetsApi {
  def description(assetId: IssuedAsset): Option[AssetDescription]

  def fullInfo(assetId: IssuedAsset): Option[AssetInfo]

  def fullInfos(assetIds: Seq[IssuedAsset]): Seq[Option[AssetInfo]]

  def wavesDistribution(height: Int, after: Option[Address]): Observable[(Address, Long)]

  def assetDistribution(asset: IssuedAsset, height: Int, after: Option[Address]): Observable[(Address, Long)]
}

object CommonAssetsApi {
  final case class AssetInfo(description: AssetDescription)

  def apply(snapshot: () => StateSnapshot, db: RocksDB, blockchain: Blockchain): CommonAssetsApi = new CommonAssetsApi {
    def description(assetId: IssuedAsset): Option[AssetDescription] =
      blockchain.assetDescription(assetId)

    def fullInfo(assetId: IssuedAsset): Option[AssetInfo] =
      for {
        assetInfo <- blockchain.assetDescription(assetId)
      } yield AssetInfo(assetInfo)

    override def fullInfos(assetIds: Seq[IssuedAsset]): Seq[Option[AssetInfo]] = {
      blockchain
        .transactionInfos(assetIds.map(_.id))
        .view
        .zip(assetIds)
        .map { case (_, assetId) =>
          blockchain.assetDescription(assetId).map { desc => AssetInfo(desc) }
        }
        .toSeq
    }

    override def wavesDistribution(height: Int, after: Option[Address]): Observable[(Address, Long)] =
      balanceDistribution(
        db,
        height,
        after,
        if (height == blockchain.height) snapshot().balances else Map(),
        KeyTag.WavesBalanceHistory.prefixBytes,
        bs => AddressId.fromByteArray(bs.slice(2, bs.length - 4)),
        Waves
      )

    override def assetDistribution(asset: IssuedAsset, height: Int, after: Option[Address]): Observable[(Address, Long)] =
      balanceDistribution(
        db,
        height,
        after,
        if (height == blockchain.height) snapshot().balances else Map(),
        KeyTag.AssetBalanceHistory.prefixBytes ++ asset.id.arr,
        bs => AddressId.fromByteArray(bs.slice(2 + crypto.DigestLength, bs.length - 4)),
        asset
      )
  }
}
