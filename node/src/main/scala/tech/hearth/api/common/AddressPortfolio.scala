package tech.hearth.api.common

import com.google.common.collect.AbstractIterator
import com.google.common.primitives.Ints
import tech.hearth.account.Address
import tech.hearth.common.state.ByteStr
import tech.hearth.crypto
import tech.hearth.database.{AddressId, DBResource, KeyTag, Keys, readCurrentBalance}
import tech.hearth.state.StateSnapshot
import tech.hearth.transaction.Asset
import tech.hearth.transaction.Asset.IssuedAsset

import java.nio.ByteBuffer
import scala.collection.immutable.VectorMap
import scala.jdk.CollectionConverters.*

class AssetBalanceIterator(addressId: AddressId, resource: DBResource) extends AbstractIterator[Seq[(IssuedAsset, Long)]] {
  private val prefixBytes: Array[Byte] = KeyTag.AssetBalance.prefixBytes ++ addressId.toByteArray

  resource.withSafePrefixIterator(_.seek(prefixBytes))(())

  override def computeNext(): Seq[(IssuedAsset, Long)] =
    resource.withSafePrefixIterator { dbIterator =>
      if (dbIterator.isValid) {
        val assetId = IssuedAsset(ByteStr(dbIterator.key().takeRight(crypto.DigestLength)))
        val balance = readCurrentBalance(dbIterator.value()).balance
        dbIterator.next()
        Seq((assetId, balance))
      } else endOfData()
    }(endOfData())
}

class WavesBalanceIterator(addressId: AddressId, resource: DBResource) extends AbstractIterator[(Int, Long)] {
  private val prefixBytes: Array[Byte] = KeyTag.WavesBalanceHistory.prefixBytes ++ addressId.toByteArray
  private val lastHeight: Int          = resource.get(Keys.wavesBalance(addressId)).height.toInt

  resource.withSafePrefixIterator(_.seekForPrev(prefixBytes ++ Ints.toByteArray(lastHeight)))(())

  override def computeNext(): (Int, Long) =
    resource.withSafePrefixIterator { dbIterator =>
      if (dbIterator.isValid) {
        val h       = ByteBuffer.wrap(dbIterator.key().drop(prefixBytes.length)).getInt
        val balance = ByteBuffer.wrap(dbIterator.value()).getLong
        dbIterator.prev()
        h -> balance
      } else endOfData()
    }(endOfData())
}

class BalanceIterator(
    address: Address,
    underlying: Iterator[Seq[(IssuedAsset, Long)]],
    private var pendingOverrides: VectorMap[(Address, Asset), Long]
) extends AbstractIterator[Seq[(IssuedAsset, Long)]] {
  private def nextOverride(): Seq[(IssuedAsset, Long)] =
    if (pendingOverrides.isEmpty) endOfData()
    else {
      val assetsWithBalances = pendingOverrides.collect { case ((`address`, asset: IssuedAsset), balance) => asset -> balance }.toSeq
      pendingOverrides = VectorMap.empty
      assetsWithBalances
    }

  override def computeNext(): Seq[(IssuedAsset, Long)] =
    if (underlying.hasNext) {
      underlying.next().map { case (asset, dbBalance) =>
        val key     = (address, asset)
        val balance = pendingOverrides.getOrElse(key, dbBalance)
        pendingOverrides -= key
        asset -> balance
      }
    } else nextOverride()
}

object AddressPortfolio {
  def assetBalanceIterator(
      resource: DBResource,
      address: Address,
      snapshot: StateSnapshot
  ): Iterator[Seq[(IssuedAsset, Long)]] =
    new BalanceIterator(
      address,
      resource
        .get(Keys.addressId(address))
        .fold(Iterator.empty[Seq[(IssuedAsset, Long)]])(addressId => new AssetBalanceIterator(addressId, resource).asScala),
      snapshot.balances
    ).asScala
      .map(
        _.filter { case (_, balance) => balance > 0 }
      )
}
