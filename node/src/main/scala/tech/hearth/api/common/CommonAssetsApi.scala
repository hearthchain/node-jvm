package tech.hearth.api.common

import tech.hearth.account.Address
import tech.hearth.api.common.CommonAssetsApi.AssetInfo
import tech.hearth.crypto
import tech.hearth.database.{AddressId, KeyTag}
import tech.hearth.state.{AssetDescription, Blockchain, StateSnapshot}
import tech.hearth.transaction.Asset.{IssuedAsset, Hearth}
import monix.reactive.Observable
import org.rocksdb.RocksDB

trait CommonAssetsApi {
  def description(assetId: IssuedAsset): Option[AssetDescription]

  def fullInfo(assetId: IssuedAsset): Option[AssetInfo]

  def fullInfos(assetIds: Seq[IssuedAsset]): Seq[Option[AssetInfo]]

  def hearthDistribution(height: Int, after: Option[Address]): Observable[(Address, Long)]

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

    override def hearthDistribution(height: Int, after: Option[Address]): Observable[(Address, Long)] =
      balanceDistribution(
        db,
        height,
        after,
        if (height == blockchain.height) snapshot().balances else Map(),
        KeyTag.HearthBalanceHistory.prefixBytes,
        bs => AddressId.fromByteArray(bs.slice(2, bs.length - 4)),
        Hearth
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
