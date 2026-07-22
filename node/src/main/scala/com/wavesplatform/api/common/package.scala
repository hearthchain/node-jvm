package com.wavesplatform.api

import com.google.common.primitives.Longs
import com.wavesplatform.account.Address
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.database.{AddressId, DBExt, Keys, RDB}
import com.wavesplatform.state.{Height, StateSnapshot, TxMeta}
import com.wavesplatform.transaction.{Asset, Transaction, TransactionType}
import monix.eval.Task
import monix.reactive.Observable
import org.rocksdb.RocksDB

import scala.jdk.CollectionConverters.*

package object common {
  import AddressTransactions.*
  import BalanceDistribution.*

  def addressTransactions(
      rdb: RDB,
      maybeDiff: Option[(Height, StateSnapshot)],
      subject: Address,
      sender: Option[Address],
      types: Set[TransactionType],
      fromId: Option[ByteStr]
  ): Observable[TransactionMeta] =
    allAddressTransactions(rdb, maybeDiff, subject, sender, types, fromId).map { case (m, transaction, _) =>
      TransactionMeta.create(
        m.height,
        transaction,
        m.status,
        m.spentComplexity
      )
    }

  def balanceDistribution(
      db: RocksDB,
      height: Int,
      after: Option[Address],
      overrides: Map[(Address, Asset), Long],
      globalPrefix: Array[Byte],
      addressId: Array[Byte] => AddressId,
      asset: Asset
  ): Observable[(Address, Long)] =
    db.resourceObservable
      .flatMap { resource =>
        resource.fullIterator.seek(
          globalPrefix ++ after
            .flatMap(address => resource.get(Keys.addressId(address)))
            .fold(Array.emptyByteArray)(id => Longs.toByteArray(id.toLong + 1))
        )
        Observable.fromIterator(Task(new BalanceIterator(resource, globalPrefix, addressId, asset, height, overrides).asScala.filter(_._2 > 0)))
      }

  def loadTransactionMeta(
      tuple: (TxMeta, Transaction)
  ): TransactionMeta = {
    val (meta, transaction) = tuple
    TransactionMeta.create(
      meta.height,
      transaction,
      meta.status,
      meta.spentComplexity
    )
  }
}
