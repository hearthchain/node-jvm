package tech.hearth.database

import com.google.common.cache.CacheBuilder
import com.google.common.collect.MultimapBuilder
import com.google.common.hash.{BloomFilter, Funnels}
import com.google.common.primitives.Ints
import com.google.common.util.concurrent.MoreExecutors
import com.typesafe.scalalogging.Logger
import tech.hearth.account.{Address, PublicKey}
import tech.hearth.api.common.HearthBalanceIterator
import tech.hearth.block.Block.BlockId
import tech.hearth.block.BlockSnapshot
import tech.hearth.common.state.ByteStr
import tech.hearth.common.utils.Base64
import tech.hearth.common.utils.EitherExt2.*
import tech.hearth.crypto.bls.BlsPublicKey
import tech.hearth.database
import tech.hearth.database.protobuf.{BlockMetaExt, StaticAssetInfo, TransactionMeta, BlockMeta as PBBlockMeta}
import tech.hearth.protobuf.block.PBBlocks
import tech.hearth.protobuf.{PBSnapshots, toByteString, toPublicKey}
import tech.hearth.settings.{BlockchainSettings, DBSettings}
import tech.hearth.state.*
import tech.hearth.transaction.Asset.{IssuedAsset, Hearth}
import tech.hearth.transaction.CommitToGenerationTransaction.DepositInEmbers
import tech.hearth.transaction.assets.*
import tech.hearth.transaction.assets.exchange.ExchangeTransaction
import tech.hearth.transaction.lease.{LeaseCancelTransaction, LeaseTransaction}
import tech.hearth.transaction.transfer.TransferTransaction
import tech.hearth.transaction.{CommitToGenerationTransaction, *}
import tech.hearth.utils.ScorexLogging
import io.netty.util.concurrent.DefaultThreadFactory
import org.rocksdb.ReadOptions
import org.slf4j.LoggerFactory

import java.time.Duration
import java.util
import java.util.concurrent.*
import scala.annotation.tailrec
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters.*
import scala.util.Using
import scala.util.Using.Releasable

object RocksDBWriter extends ScorexLogging {

  /** {{{
    * ([10, 7, 4], 5, 11) => [10, 7, 4]
    * ([10, 7], 5, 11) => [10, 7, 1]
    * }}}
    */
  private[database] def slice(v: Seq[Height], from: Height, to: Height): Seq[Height] = {
    val (c1, c2) = v.dropWhile(_ > to).partition(_ > from)
    c1 :+ c2.headOption.getOrElse(Height(1))
  }

  implicit class ReadOnlyDBExt(val db: ReadOnlyDB) extends AnyVal {
    def fromHistory[A](historyKey: Key[Seq[Height]], valueKey: Height => Key[A]): Option[A] =
      for {
        lastChange <- db.get(historyKey).headOption
      } yield db.get(valueKey(lastChange))

    def hasInHistory(historyKey: Key[Seq[Height]], v: Height => Key[?]): Boolean =
      db.get(historyKey)
        .headOption
        .exists(h => db.has(v(h)))
  }

  implicit class RWExt(val db: RW) extends AnyVal {
    def fromHistory[A](historyKey: Key[Seq[Height]], valueKey: Height => Key[A]): Option[A] =
      for {
        lastChange <- db.get(historyKey).headOption
      } yield db.get(valueKey(lastChange))
  }

  /** Merges height sequences: {{{([15, 12, 3], [12, 5], [3, 1]) => [(15, 12, 3), (12, 12, 3), (3, 5, 3), (3, 5, 1)]}}}
    * @return
    *   Each tuple contains balance components at {{{Height(max(x._1, x._2, x._3))}}}
    */
  private[database] def merge3(whs: Seq[Height], lhs: Seq[Height], dhs: Seq[Height]): Seq[(Height, Height, Height)] = {
    @tailrec
    def loop(
        wh: Height,
        wt: Seq[Height],
        lh: Height,
        lt: Seq[Height],
        dh: Height,
        dt: Seq[Height],
        r: ArrayBuffer[(Height, Height, Height)]
    ): ArrayBuffer[(Height, Height, Height)] = {
      r.append((wh, lh, dh))

      var th = Height(Int.MinValue) // target height
      if (wt.nonEmpty && wh > th) th = wh
      if (lt.nonEmpty && lh > th) th = lh
      if (dt.nonEmpty && dh > th) th = dh

      if (th == Height(Int.MinValue)) r
      else {
        val (nah, nat) = if (wh == th && wt.nonEmpty) (wt.head, wt.tail) else (wh, wt)
        val (nbh, nbt) = if (lh == th && lt.nonEmpty) (lt.head, lt.tail) else (lh, lt)
        val (nch, nct) = if (dh == th && dt.nonEmpty) (dt.head, dt.tail) else (dh, dt)

        loop(nah, nat, nbh, nbt, nch, nct, r)
      }
    }

    loop(whs.head, whs.tail, lhs.head, lhs.tail, dhs.head, dhs.tail, ArrayBuffer.empty).toSeq
  }

  def apply(
      rdb: RDB,
      settings: BlockchainSettings,
      dbSettings: DBSettings,
      isLightMode: Boolean,
      forceCleanupExecutorService: Option[ExecutorService] = None
  ): RocksDBWriter = new RocksDBWriter(
    rdb,
    settings,
    dbSettings,
    isLightMode,
    dbSettings.cleanupInterval match {
      case None => MoreExecutors.newDirectExecutorService() // We don't care if disabled
      case Some(_) =>
        forceCleanupExecutorService.getOrElse {
          new ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue[Runnable](1), // Only one task at time
            new DefaultThreadFactory("rocksdb-cleanup", true),
            { (_: Runnable, _: ThreadPoolExecutor) => /* Ignore new jobs, because TPE is busy, we will clean the data next time */ }
          )
        }
    }
  )
}

//noinspection UnstableApiUsage
class RocksDBWriter(
    rdb: RDB,
    val settings: BlockchainSettings,
    val dbSettings: DBSettings,
    isLightMode: Boolean,
    cleanupExecutorService: ExecutorService
) extends Caches
    with AutoCloseable {
  import rdb.db as writableDB

  private val log = Logger(LoggerFactory.getLogger(classOf[RocksDBWriter]))

  import RocksDBWriter.*

  override def close(): Unit = {
    cleanupExecutorService.shutdownNow()
    if (!cleanupExecutorService.awaitTermination(20, TimeUnit.SECONDS))
      log.warn("Not enough time for a cleanup task, try to increase the limit")
  }

  private[database] def readOnly[A](f: ReadOnlyDB => A): A = writableDB.readOnly(f)

  private def readWrite[A](f: RW => A): A = writableDB.readWrite(f)

  override protected def loadMaxAddressId(): Long = writableDB.get(Keys.lastAddressId).getOrElse(0L)

  override protected def loadAddressId(address: Address): Option[AddressId] =
    writableDB.get(Keys.addressId(address))

  override protected def loadAddressIds(addresses: Seq[Address]): Map[Address, Option[AddressId]] = readOnly { ro =>
    addresses.view.zip(ro.multiGetOpt(addresses.view.map(Keys.addressId).toVector, 8)).toMap
  }

  override protected def loadHeight(): Height = writableDB.get(Keys.height)

  override protected def loadFinalizedHeight(): Option[Height] = writableDB.get(Keys.finalizedHeight)

  override def finalizedHeightAt(at: Height): Option[Height] = writableDB.get(Keys.finalizedHeightAt(at))

  override def safeRollbackHeight: Height = writableDB.get(Keys.safeRollbackHeight)

  override protected def loadBlockMeta(height: Height): Option[PBBlockMeta] =
    writableDB.get(Keys.blockMetaAt(height))

  override protected def loadTxs(height: Height): Seq[Transaction] =
    loadTransactions(height, rdb).map(_._2)

  protected override def loadBalance(req: (Address, Asset)): CurrentBalance =
    addressId(req._1).fold(CurrentBalance.Unavailable) { addressId =>
      req._2 match {
        case asset @ IssuedAsset(_) =>
          writableDB.get(Keys.assetBalance(addressId, asset))
        case Hearth =>
          writableDB.get(Keys.hearthBalance(addressId))
      }
    }

  override protected def loadBalances(req: Seq[(Address, Asset)]): Map[(Address, Asset), CurrentBalance] = readOnly { ro =>
    val addrToId = addressIds(req.map(_._1)).collect { case (address, Some(aid)) =>
      address -> aid
    }

    val reqWithKeys = req.flatMap { case (address, asset) =>
      addrToId.get(address).map { aid =>
        (address, asset) -> (asset match {
          case Hearth                   => Keys.hearthBalance(aid)
          case issuedAsset: IssuedAsset => Keys.assetBalance(aid, issuedAsset)
        })
      }
    }

    val addressAssetToBalance = reqWithKeys
      .zip(ro.multiGet(reqWithKeys.view.map(_._2).toVector, 16))
      .collect { case (((address, asset), _), Some(balance)) =>
        (address, asset) -> balance
      }
      .toMap

    req.map { key =>
      key -> addressAssetToBalance.getOrElse(key, CurrentBalance.Unavailable)
    }.toMap
  }

  protected override def loadHearthBalances(req: Seq[(Address, Asset)]): Map[(Address, Asset), CurrentBalance] = readOnly { ro =>
    val addrToId = addressIds(req.map(_._1))
    val addrIds  = addrToId.collect { case (_, Some(aid)) => aid }.toSeq

    val idToBalance = addrIds
      .zip(
        ro.multiGet(
          addrIds.view.map { addrId =>
            Keys.hearthBalance(addrId)
          }.toVector,
          16
        )
      )
      .toMap

    req.map { case (address, asset) =>
      (address, asset) -> addrToId.get(address).flatMap(_.flatMap(idToBalance.get)).flatten.getOrElse(CurrentBalance.Unavailable)
    }.toMap
  }

  private def loadLeaseBalance(db: ReadOnlyDB, addressId: AddressId): CurrentLeaseBalance =
    db.get(Keys.leaseBalance(addressId))

  override protected def loadLeaseBalance(address: Address): CurrentLeaseBalance = readOnly { db =>
    addressId(address).fold(CurrentLeaseBalance.Unavailable)(loadLeaseBalance(db, _))
  }

  override protected def loadLeaseBalances(addresses: Seq[Address]): Map[Address, CurrentLeaseBalance] = readOnly { ro =>
    val addrToId = addressIds(addresses)
    val addrIds  = addrToId.collect { case (_, Some(aid)) => aid }.toSeq

    val idToBalance = addrIds
      .zip(
        ro.multiGet(
          addrIds.view.map { addrId =>
            Keys.leaseBalance(addrId)
          }.toVector,
          24
        )
      )
      .toMap

    addresses.map { address =>
      address -> addrToId.get(address).flatMap(_.flatMap(idToBalance.get)).flatten.getOrElse(CurrentLeaseBalance.Unavailable)
    }.toMap
  }

  override protected def loadAssetDescription(asset: IssuedAsset): Option[AssetDescription] =
    writableDB.withResource(r => database.loadAssetDescription(r, asset))

  override protected def loadVolumeAndFee(orderId: ByteStr): CurrentVolumeAndFee = writableDB.get(Keys.filledVolumeAndFee(orderId))

  override protected def loadVolumesAndFees(orders: Seq[ByteStr]): Map[ByteStr, CurrentVolumeAndFee] = readOnly { ro =>
    orders.view
      .zip(ro.multiGet(orders.view.map(Keys.filledVolumeAndFee).toVector, 24))
      .map { case (id, v) => id -> v.getOrElse(CurrentVolumeAndFee.Unavailable) }
      .toMap
  }

  override protected def loadApprovedFeatures(): Map[Short, Height] =
    writableDB.get(Keys.approvedFeatures)

  override protected def loadActivatedFeatures(): Map[Short, Height] = {
    val stateFeatures = writableDB.get(Keys.activatedFeatures)
    stateFeatures ++ settings.functionalitySettings.preActivatedFeatures.view.mapValues(Height(_))
  }

  override def hearthAmount(height: Int): BigInt =
    loadBlockMeta(Height(height)).fold(settings.initialBalance)(_.totalHearthAmount)

  override def blockReward(height: Int): Option[Long] =
    if (height == 1) None
    else loadBlockMeta(Height(height)).map(_.reward)

  private def updateHistory(rw: RW, key: Key[Seq[Height]], threshold: Height, kf: Height => Key[?]): Seq[Array[Byte]] =
    updateHistory(rw, rw.get(key), key, threshold, kf)

  private def updateHistory(rw: RW, history: Seq[Height], key: Key[Seq[Height]], threshold: Height, kf: Height => Key[?]): Seq[Array[Byte]] = {
    val (c1, c2) = history.partition(_ >= threshold)
    rw.put(key, (Height(height) +: c1) ++ c2.headOption)
    c2.drop(1).map(kf(_).keyBytes)
  }

  private def appendBalances(
      balances: Map[(AddressId, Asset), (CurrentBalance, BalanceNode)],
      rw: RW
  ): Unit = {
    var changedHearthBalances = List.empty[AddressId]
    val changedAssetBalances  = MultimapBuilder.hashKeys().hashSetValues().build[IssuedAsset, java.lang.Long]()

    for (((addressId, asset), (currentBalance, balanceNode)) <- balances) {
      asset match {
        case Hearth =>
          changedHearthBalances = addressId :: changedHearthBalances
          rw.put(Keys.hearthBalance(addressId), currentBalance)
          rw.put(Keys.hearthBalanceAt(addressId, currentBalance.height), balanceNode)
        case a: IssuedAsset =>
          changedAssetBalances.put(a, addressId.toLong)
          rw.put(Keys.assetBalance(addressId, a), currentBalance)
          rw.put(Keys.assetBalanceAt(addressId, a, currentBalance.height), balanceNode)
      }
    }

    rw.put(Keys.changedHearthBalances(Height(height)), changedHearthBalances)
    changedAssetBalances.asMap().forEach { (asset, addresses) =>
      rw.put(Keys.changedBalances(Height(height), asset), addresses.asScala.map(id => AddressId(id.toLong)).toSeq)
    }
  }

  private var TxFilterResetTs = lastBlock.fold(0L)(_.header.timestamp)
  private def mkFilter()      = BloomFilter.create[Array[Byte]](Funnels.byteArrayFunnel(), dbSettings.txBloomFilterSize, 0.001f)
  private var currentTxFilter = mkFilter()
  private var prevTxFilter = lastBlock match {
    case Some(b) =>
      TxFilterResetTs = b.header.timestamp
      val prevFilter = mkFilter()

      var fromHeight = height
      // Column families here are opened with a 10-byte capped prefix extractor (see RDB.newColumnFamilyOptions):
      // a seek() to a key without an exact match only sees keys sharing that key's prefix bucket unless
      // TotalOrderSeek is set, so both iterators below need it explicitly - without it, a fresh RocksDBWriter
      // (e.g. after a real restart) silently rebuilds these tx bloom filters as empty, and disallowDuplicateIds
      // then lets an already-mined transaction back in.
      Using.resource(new ReadOptions().setTotalOrderSeek(true)) { ro =>
        Using(writableDB.newIterator(ro)) { iter =>
          iter.seek(Keys.blockMetaAt(Height(height)).keyBytes)
          var lastBlockTs = TxFilterResetTs

          while (
            iter.isValid &&
            iter.key().startsWith(KeyTag.BlockInfoAtHeight.prefixBytes) &&
            (TxFilterResetTs - lastBlockTs) < settings.functionalitySettings.maxTransactionTimeBackOffset.toMillis * 2
          ) {
            lastBlockTs = readBlockMeta(iter.value()).getHeader.timestamp
            fromHeight = Ints.fromByteArray(iter.key().drop(2))
            iter.prev()
          }
        }

        Using(writableDB.newIterator(rdb.txHandle.handle, ro)) { iter =>
          var counter = 0
          iter.seek(Keys.transactionAt(Height(fromHeight), TxNum(0.toShort), rdb.txHandle).keyBytes)
          while (
            iter.isValid &&
            iter.key().startsWith(KeyTag.NthTransactionInfoAtHeight.prefixBytes) &&
            Ints.fromByteArray(iter.key().slice(2, 6)) <= height
          ) {
            counter += 1
            prevFilter.put(readTransaction(Height(0))(iter.value())._2.id().arr)
            iter.next()
          }
          log.debug(s"Loaded $counter tx IDs from [$fromHeight, $height]")
        }
      }

      prevFilter
    case None =>
      mkFilter()
  }

  override def containsTransaction(tx: Transaction): Boolean =
    (prevTxFilter.mightContain(tx.id().arr) || currentTxFilter.mightContain(tx.id().arr)) && {
      writableDB.get(Keys.transactionMetaById(TransactionId(tx.id()), rdb.txMetaHandle)).isDefined
    }

  override protected def doAppend(
      blockMeta: PBBlockMeta,
      snapshot: StateSnapshot,
      computedBlockStateHash: ByteStr,
      newAddresses: Map[Address, AddressId],
      balances: Map[(AddressId, Asset), (CurrentBalance, BalanceNode)],
      leaseBalances: Map[AddressId, (CurrentLeaseBalance, LeaseBalanceNode)],
      filledQuantity: Map[ByteStr, (CurrentVolumeAndFee, VolumeAndFeeNode)],
      addressTransactions: util.Map[AddressId, util.Collection[TransactionId]],
      newFinalizedHeight: Height,
      generatorSet: GeneratorSet,
      committedGenerators: Seq[(AddressId, BlsPublicKey, ByteStr)],
      committedPeriod: Option[GenerationPeriod],
      commitmentTransactionIds: Seq[TransactionId],
      registeredEnclaves: Seq[RegisteredEnclave],
      conflictGenerators: Seq[GeneratorIndex],
      stateHash: StateHashBuilder.Result
  ): Unit = {
    log.trace(s"Persisting block ${blockMeta.id} at height $height")
    readWrite { rw =>
      val expiredKeys = new ArrayBuffer[Array[Byte]]
      val h           = Height(height)

      rw.put(Keys.height, h)
      rw.put(Keys.finalizedHeight, Some(newFinalizedHeight))
      rw.put(Keys.finalizedHeightAt(h), Some(newFinalizedHeight))

      val previousSafeRollbackHeight = rw.get(Keys.safeRollbackHeight)
      val newSafeRollbackHeight      = Height(height) - dbSettings.maxRollbackDepth

      if (previousSafeRollbackHeight < newSafeRollbackHeight) {
        rw.put(Keys.safeRollbackHeight, newSafeRollbackHeight)
        dbSettings.cleanupInterval.foreach { cleanupInterval =>
          runCleanupTask(newSafeRollbackHeight - 1, cleanupInterval) // -1 because we haven't appended this block
        }
      }

      rw.put(Keys.blockMetaAt(h), Some(blockMeta))
      rw.put(Keys.heightOf(blockMeta.id), Some(height))
      blockHeightCache.put(blockMeta.id, Some(height))

      blockMeta.header.flatMap(_.challengedHeader.map(_.generator.toPublicKey.toAddress)) match {
        case Some(addr) =>
          val key          = Keys.maliciousMinerBanHeights(addr.toBytes)
          val savedHeights = rw.get(key)
          rw.put(key, Height(height) +: savedHeights)
        case _ => ()
      }

      val lastAddressId = loadMaxAddressId() + newAddresses.size
      rw.put(Keys.lastAddressId, Some(lastAddressId))

      for ((address, id) <- newAddresses) {
        val kaid = Keys.addressId(address)
        rw.put(kaid, Some(id))
        rw.put(Keys.idToAddress(id), address)
      }

      val threshold = newSafeRollbackHeight

      appendBalances(balances, rw)

      val changedAddresses = (addressTransactions.asScala.keys ++ balances.keys.map(_._1)).toSet
      rw.put(Keys.changedAddresses(Height(height)), changedAddresses.toSeq)

      // leases
      for ((addressId, (currentLeaseBalance, leaseBalanceNode)) <- leaseBalances) {
        rw.put(Keys.leaseBalance(addressId), currentLeaseBalance)
        rw.put(Keys.leaseBalanceAt(addressId, currentLeaseBalance.height), leaseBalanceNode)
      }

      for ((orderId, (currentVolumeAndFee, volumeAndFeeNode)) <- filledQuantity) {
        rw.put(Keys.filledVolumeAndFee(orderId), currentVolumeAndFee)
        rw.put(Keys.filledVolumeAndFeeAt(orderId, currentVolumeAndFee.height), volumeAndFeeNode)
      }

      for ((asset, (assetStatic, assetNum)) <- snapshot.assetStatics) {
        val pbAssetStatic = StaticAssetInfo(
          assetStatic.decimals,
          assetNum,
          height,
          asset.id.toByteString,
          assetStatic.name,
          assetStatic.description
        )
        rw.put(Keys.assetStaticInfo(asset), Some(pbAssetStatic))
      }

      // an asset's volume is fixed forever at issuance (see StateSnapshot.assetVolumes), so this is always a
      // fresh write for a newly-issued asset, never a merge with an existing one
      for ((asset, volume) <- snapshot.assetVolumes) {
        rw.put(Keys.assetVolumeDetails(asset)(Height(height)), volume)
        expiredKeys ++= updateHistory(rw, Keys.assetVolumeDetailsHistory(asset), threshold, Keys.assetVolumeDetails(asset))
      }

      for ((asset, minFee) <- snapshot.minAssetFees) {
        rw.put(Keys.assetMinFee(asset)(Height(height)), minFee)
        expiredKeys ++= updateHistory(rw, Keys.assetMinFeeHistory(asset), threshold, Keys.assetMinFee(asset))
      }
      if (snapshot.minAssetFees.nonEmpty) rw.put(Keys.assetsWithMinFee(Height(height)), snapshot.minAssetFees.keySet.toSeq)

      for ((id, li) <- snapshot.newLeases) {
        rw.put(Keys.leaseDetails(id)(Height(height)), Some(LeaseDetails(li, snapshot.cancelledLeases.getOrElse(id, LeaseDetails.Status.Active))))
        expiredKeys ++= updateHistory(rw, Keys.leaseDetailsHistory(id), threshold, Keys.leaseDetails(id))
      }

      for ((id, status) <- snapshot.cancelledLeases if !snapshot.newLeases.contains(id)) {
        leaseDetails(id).foreach { d =>
          rw.put(Keys.leaseDetails(id)(Height(height)), Some(d.copy(status = status)))
        }

        expiredKeys ++= updateHistory(rw, Keys.leaseDetailsHistory(id), threshold, Keys.leaseDetails(id))
      }

      snapshot.dcapRootCaCrl.foreach { crl =>
        rw.put(Keys.dcapRootCaCrl(Height(height)), crl)
        expiredKeys ++= updateHistory(rw, Keys.dcapRootCaCrlHistory, threshold, Keys.dcapRootCaCrl)
      }

      snapshot.dcapPckCrl.foreach { crl =>
        rw.put(Keys.dcapPckCrl(Height(height)), crl)
        expiredKeys ++= updateHistory(rw, Keys.dcapPckCrlHistory, threshold, Keys.dcapPckCrl)
      }

      for ((fmspc, payload) <- snapshot.dcapTcbInfo) {
        rw.put(Keys.dcapTcbInfo(fmspc)(Height(height)), payload)
        expiredKeys ++= updateHistory(rw, Keys.dcapTcbInfoHistory(fmspc), threshold, Keys.dcapTcbInfo(fmspc))
      }
      if (snapshot.dcapTcbInfo.nonEmpty) rw.put(Keys.dcapTcbInfoFmspcsAt(Height(height)), snapshot.dcapTcbInfo.keySet.toSeq)

      snapshot.dcapQeIdentity.foreach { identity =>
        rw.put(Keys.dcapQeIdentity(Height(height)), identity)
        expiredKeys ++= updateHistory(rw, Keys.dcapQeIdentityHistory, threshold, Keys.dcapQeIdentity)
      }

      snapshot.dcapTcbSigningIssuerChain.foreach { chain =>
        rw.put(Keys.dcapTcbSigningIssuerChain(Height(height)), chain)
        expiredKeys ++= updateHistory(rw, Keys.dcapTcbSigningIssuerChainHistory, threshold, Keys.dcapTcbSigningIssuerChain)
      }

      snapshot.dcapPckCaIssuerChain.foreach { chain =>
        rw.put(Keys.dcapPckCaIssuerChain(Height(height)), chain)
        expiredKeys ++= updateHistory(rw, Keys.dcapPckCaIssuerChainHistory, threshold, Keys.dcapPckCaIssuerChain)
      }

      expiredKeys ++= writeKeyed(rw, height, threshold, snapshot.reservedAmounts)(
        { case (sender, miner, asset) => Keys.reservedAmountSuffix(sender, miner, asset) },
        Keys.reservedAmount,
        Keys.reservedAmountHistory,
        Keys.reservedAmountKeysAt
      )
      expiredKeys ++= writeKeyed(rw, height, threshold, snapshot.apiKeyBindings)(
        { case (enclavePublicKey, sender) => Keys.apiKeyBindingSuffix(enclavePublicKey, sender) },
        Keys.apiKeyBinding,
        Keys.apiKeyBindingHistory,
        Keys.apiKeyBindingKeysAt
      )
      expiredKeys ++= writeKeyed(rw, height, threshold, snapshot.settledAmounts)(
        { case (client, miner, asset) => Keys.settledAmountSuffix(client, miner, asset) },
        Keys.settledAmount,
        Keys.settledAmountHistory,
        Keys.settledAmountKeysAt
      )
      expiredKeys ++= writeKeyed(rw, height, threshold, snapshot.workDone)(
        { case (validator, period) => Keys.workDoneSuffix(validator, period) },
        Keys.workDone,
        Keys.workDoneHistory,
        Keys.workDoneKeysAt
      )

      if (blockMeta.getHeader.timestamp - TxFilterResetTs > settings.functionalitySettings.maxTransactionTimeBackOffset.toMillis * 2) {
        log.trace(s"Rotating filter at $height, prev ts = $TxFilterResetTs, new ts = ${blockMeta.getHeader.timestamp}, interval = ${Duration
            .ofMillis(blockMeta.getHeader.timestamp - TxFilterResetTs)}")
        TxFilterResetTs = blockMeta.getHeader.timestamp
        prevTxFilter = currentTxFilter
        currentTxFilter = mkFilter()
      }

      val transactionsWithSize =
        snapshot.transactions.zipWithIndex.map { case ((id, txInfo), i) =>
          val tx   = txInfo.transaction
          val num  = TxNum(i.toShort)
          val meta = TxMeta(Height(blockMeta.height), txInfo.status, txInfo.spentComplexity)
          val txId = TransactionId(id)

          val size = rw.put(Keys.transactionAt(h, num, rdb.txHandle), Some((meta, tx)))
          rw.put(
            Keys.transactionStateSnapshotAt(h, num, rdb.txSnapshotHandle),
            Some(PBSnapshots.toProtobuf(txInfo.snapshot, txInfo.status))
          )
          rw.put(
            Keys.transactionMetaById(txId, rdb.txMetaHandle),
            Some(TransactionMeta(height, num.toShort, tx.tpe.id, meta.status.protobuf, 0, size))
          )
          currentTxFilter.put(id.arr)

          txId -> (num, tx, size)
        }.toMap

      if (dbSettings.storeTransactionsByAddress) {
        val addressTxs = addressTransactions.asScala.toSeq.map { case (aid, txIds) =>
          (aid, txIds, Keys.addressTransactionSeqNr(aid, rdb.apiHandle))
        }
        rw.multiGetInts(addressTxs.view.map(_._3).toVector)
          .zip(addressTxs)
          .foreach { case (prevSeqNr, (addressId, txIds, txSeqNrKey)) =>
            val nextSeqNr = prevSeqNr.getOrElse(0) + 1
            val txTypeNumSeq = txIds.asScala.map { txId =>
              val (num, tx, size) = transactionsWithSize(txId)
              (tx.tpe.id.toByte, num, size)
            }.toSeq
            rw.put(Keys.addressTransactionHN(addressId, nextSeqNr, rdb.apiHandle), Some((h, txTypeNumSeq.sortBy(-_._2))))
            rw.put(txSeqNrKey, nextSeqNr)
          }
      }

      if (dbSettings.storeLeaseStatesByAddress) {
        val addressIdWithLeaseIds =
          for {
            (leaseId, details) <- snapshot.newLeases.toSeq if !snapshot.cancelledLeases.contains(leaseId)
            address            <- Seq(details.recipientAddress, details.sender.toAddress)
            addressId = this.addressIdWithFallback(address, newAddresses)
          } yield (addressId, leaseId)
        val leaseIdsByAddressId = addressIdWithLeaseIds.groupMap { case (addressId, _) =>
          (addressId, Keys.addressLeaseSeqNr(addressId, rdb.apiHandle))
        }(_._2).toSeq

        rw.multiGetInts(leaseIdsByAddressId.view.map(_._1._2).toVector)
          .zip(leaseIdsByAddressId)
          .foreach { case (prevSeqNr, ((addressId, leaseSeqKey), leaseIds)) =>
            val nextSeqNr = prevSeqNr.getOrElse(0) + 1
            rw.put(Keys.addressLeaseSeq(addressId, nextSeqNr, rdb.apiHandle), Some(leaseIds))
            rw.put(leaseSeqKey, nextSeqNr)
          }
      }

      val activationWindowSize = settings.functionalitySettings.activationWindowSize(height)
      if (height % activationWindowSize == 0) {
        val minVotes = settings.functionalitySettings.blocksForFeatureActivation
        val newlyApprovedFeatures = featureVotes(h)
          .filterNot { case (featureId, _) => settings.functionalitySettings.preActivatedFeatures.contains(featureId) }
          .collect {
            case (featureId, voteCount) if voteCount + (if (blockMeta.getHeader.featureVotes.contains(featureId.toInt)) 1 else 0) >= minVotes =>
              featureId -> h
          }

        if (newlyApprovedFeatures.nonEmpty) {
          approvedFeaturesCache = newlyApprovedFeatures ++ approvedFeaturesCache
          rw.put(Keys.approvedFeatures, approvedFeaturesCache)

          val featuresToSave = (newlyApprovedFeatures.view.mapValues(h => h + activationWindowSize) ++ activatedFeaturesCache).toMap

          activatedFeaturesCache = featuresToSave ++ settings.functionalitySettings.preActivatedFeatures.view.mapValues(Height(_))
          rw.put(Keys.activatedFeatures, featuresToSave)
        }
      }

      // committedPeriod is the current period for the genesis block and the next one for any other, see Caches
      committedPeriod.foreach { committedPeriod =>
        if (committedGenerators.nonEmpty) {
          rw.put(Keys.committedGenerators(committedPeriod, h), Some(committedGenerators))

          // TODO: Option to not store
          rw.put(Keys.commitmentTransactions(committedPeriod, h), commitmentTransactionIds)
        }

        if (registeredEnclaves.nonEmpty) rw.put(Keys.registeredEnclaves(committedPeriod, h), Some(registeredEnclaves))
      }

      this.generationPeriodOf(h).foreach { currPeriod => // None checked in Caches
        if (conflictGenerators.nonEmpty) rw.put(Keys.conflictGenerators(currPeriod, h), conflictGenerators)
      }

      // TODO: Option to not store
      rw.put(Keys.generatorBalances(h, rdb.apiHandle), Some(generatorSet.map(x => x.index -> x.balance)))

      // TODO: height
      rw.put(Keys.issuedAssets(Height(height)), snapshot.assetStatics.keySet.toSeq)

      rw.put(Keys.blockStateHash(Height(height)), computedBlockStateHash)

      expiredKeys.foreach(rw.delete)

      if (dbSettings.storeStateHashes) {
        val prevStateHash =
          if (height == 1) ByteStr.empty
          else
            rw.get(Keys.stateHash(Height(height) - 1))
              .fold(
                throw new IllegalStateException(
                  s"Couldn't load state hash for ${height - 1}. Please rebuild the state or disable db.store-state-hashes"
                )
              )(_.totalHash)

        val newStateHash = stateHash.createStateHash(prevStateHash)
        rw.put(Keys.stateHash(Height(height)), Some(newStateHash))
      }
    }
    log.trace(s"Finished persisting block ${blockMeta.id} at height $height")
  }

  @volatile private var lastCleanupHeight = writableDB.get(Keys.lastCleanupHeight)
  private def runCleanupTask(newLastSafeHeightForDeletion: Height, cleanupInterval: Int): Unit =
    if (lastCleanupHeight + cleanupInterval < newLastSafeHeightForDeletion) {
      cleanupExecutorService.submit(new Runnable {
        override def run(): Unit = {
          val firstDirtyHeight  = lastCleanupHeight + 1
          val toHeightExclusive = firstDirtyHeight + cleanupInterval
          val startTs           = System.nanoTime()

          rdb.db.withOptions { (ro, wo) =>
            rdb.db.readWriteWithOptions(ro, wo.setLowPri(true)) { rw =>
              batchCleanupHearthBalances(
                fromInclusive = firstDirtyHeight,
                toExclusive = toHeightExclusive,
                rw = rw
              )

              batchCleanupAssetBalances(
                fromInclusive = firstDirtyHeight,
                toExclusive = toHeightExclusive,
                rw = rw
              )

              lastCleanupHeight = toHeightExclusive - 1
              rw.put(Keys.lastCleanupHeight, lastCleanupHeight)
            }
          }

          log.debug(s"Cleanup in [$firstDirtyHeight; $toHeightExclusive) took ${(System.nanoTime() - startTs) / 1_000_000}ms")
        }
      })
    }

  private def batchCleanupHearthBalances(fromInclusive: Height, toExclusive: Height, rw: RW): Unit = {
    val lastUpdateAt = mutable.LongMap.empty[Height]

    val updateAt     = new ArrayBuffer[(AddressId, Height)]() // AddressId -> First height of update in this range
    val updateAtKeys = new ArrayBuffer[Key[BalanceNode]]()

    val changedKeyPrefix = KeyTag.ChangedHearthBalances.prefixBytes
    val changedFromKey   = Keys.changedHearthBalances(fromInclusive) // fromInclusive doesn't affect the parsing result
    rw.iterateOverWithSeek(changedKeyPrefix, changedFromKey.keyBytes) { e =>
      val currHeight = Height(Ints.fromByteArray(e.getKey.drop(changedKeyPrefix.length)))
      val continue   = currHeight < toExclusive
      if (continue)
        changedFromKey.parse(e.getValue).foreach { addressId =>
          lastUpdateAt.updateWith(addressId.toLong) { orig =>
            if (orig.isEmpty) {
              updateAt.addOne(addressId -> currHeight)
              updateAtKeys.addOne(Keys.hearthBalanceAt(addressId, currHeight))
            }
            Some(currHeight)
          }
        }
      continue
    }

    rw.multiGet(updateAtKeys, BalanceNode.SizeInBytes)
      .view
      .zip(updateAt)
      .foreach { case (prevBalanceNode, (addressId, firstHeight)) =>
        // We have changes on: previous period = 1000, 1200, 1900, current period = 2000, 2500.
        // Removed on a previous period: 1100, 1200. We need to remove on a current period: 1900, 2000.
        // We doesn't know about 1900, so we should delete all keys from 1.
        // But there is an issue in RocksDB: https://github.com/facebook/rocksdb/issues/11407 that leads to stopped writes.
        // So we need to issue non-overlapping delete ranges and we have to read changes on 2000 to know 1900.
        // Also note: memtable_max_range_deletions doesn't have any effect.
        // TODO Use deleteRange(1, height) after RocksDB's team solves the overlapping deleteRange issue.
        val firstDeleteHeight = prevBalanceNode.fold(firstHeight) { x =>
          if (x.prevHeight == Height(0)) firstHeight // There is no previous record
          else x.prevHeight
        }

        val lastDeleteHeight = lastUpdateAt(addressId.toLong)
        if (firstDeleteHeight != lastDeleteHeight)
          rw.deleteRange(
            Keys.hearthBalanceAt(addressId, firstDeleteHeight),
            Keys.hearthBalanceAt(addressId, lastDeleteHeight) // Deletes exclusively
          )
      }

    rw.deleteRange(Keys.changedHearthBalances(fromInclusive), Keys.changedHearthBalances(toExclusive))
  }

  private def batchCleanupAssetBalances(fromInclusive: Height, toExclusive: Height, rw: RW): Unit = {
    val lastUpdateAt = mutable.HashMap.empty[(AddressId, IssuedAsset), Height]

    val updateAt     = new ArrayBuffer[(AddressId, IssuedAsset, Height)]() // First height of update in this range
    val updateAtKeys = new ArrayBuffer[Key[BalanceNode]]()

    val changedKeyPrefix = KeyTag.ChangedAssetBalances.prefixBytes
    val changedKey       = Keys.changedBalances(Height(Int.MaxValue), IssuedAsset(ByteStr.empty))
    rw.iterateOverWithSeek(changedKeyPrefix, Keys.changedBalancesAtPrefix(fromInclusive)) { e =>
      val currHeight = Height(Ints.fromByteArray(e.getKey.drop(changedKeyPrefix.length)))
      val continue   = currHeight < toExclusive
      if (continue) {
        val asset = IssuedAsset(ByteStr(e.getKey.takeRight(AssetIdLength)))
        changedKey.parse(e.getValue).foreach { addressId =>
          lastUpdateAt.updateWith((addressId, asset)) { orig =>
            if (orig.isEmpty) {
              updateAt.addOne((addressId, asset, currHeight))
              updateAtKeys.addOne(Keys.assetBalanceAt(addressId, asset, currHeight))
            }
            Some(currHeight)
          }
        }
      }
      continue
    }

    rw.multiGet(updateAtKeys, BalanceNode.SizeInBytes)
      .view
      .zip(updateAt)
      .foreach { case (prevBalanceNode, (addressId, asset, firstHeight)) =>
        val firstDeleteHeight = prevBalanceNode.fold(firstHeight) { x =>
          if (x.prevHeight == Height(0)) firstHeight
          else x.prevHeight
        }

        val lastDeleteHeight = lastUpdateAt((addressId, asset))
        if (firstDeleteHeight != lastDeleteHeight)
          rw.deleteRange(
            Keys.assetBalanceAt(addressId, asset, firstDeleteHeight),
            Keys.assetBalanceAt(addressId, asset, lastDeleteHeight)
          )
      }

    rw.deleteRange(Keys.changedBalancesAtPrefix(fromInclusive), Keys.changedBalancesAtPrefix(toExclusive))
  }

  override protected def doRollback(targetHeight: Height): DiscardedBlocks = {
    val targetBlockId = readOnly(_.get(Keys.blockMetaAt(targetHeight)))
      .map(_.id)
      .getOrElse(throw new IllegalArgumentException(s"No block at height $targetHeight"))

    log.debug(s"Rolling back to block $targetBlockId at $targetHeight")

    val discardedBlocks: DiscardedBlocks =
      for (currentHeightInt <- height until targetHeight.toInt by -1; currentHeight = Height(currentHeightInt)) yield {
        val balancesToInvalidate     = Seq.newBuilder[(Address, Asset)]
        val ordersToInvalidate       = Seq.newBuilder[ByteStr]
        val blockHeightsToInvalidate = Seq.newBuilder[ByteStr]

        val currentPeriod = this.generationPeriodOf(currentHeight)
        val discardedBlock = readWrite { rw =>
          val blockchainHeight = currentHeight.prev
          rw.put(Keys.height, blockchainHeight)

          // Happens only during a forced rollback. Reset only if we had a finalized height before
          if (finalizedHeight.exists(blockchainHeight < _)) {
            val atBlockchainHeight = rw.get(Keys.finalizedHeightAt(blockchainHeight))
            rw.put(Keys.finalizedHeight, atBlockchainHeight)
          }
          rw.delete(Keys.finalizedHeightAt(currentHeight))

          val discardedMeta = rw
            .get(Keys.blockMetaAt(currentHeight))
            .getOrElse(throw new IllegalArgumentException(s"No block at height $currentHeight"))

          log.trace(s"Removing block ${discardedMeta.id} at $currentHeight")

          val changedAddresses = for {
            addressId <- rw.get(Keys.changedAddresses(currentHeight))
          } yield addressId -> rw.get(Keys.idToAddress(addressId))

          rw.iterateOver(KeyTag.ChangedAssetBalances.prefixBytes ++ KeyHelpers.h(currentHeight)) { e =>
            val assetId = IssuedAsset(ByteStr(e.getKey.takeRight(AssetIdLength)))
            for ((addressId, address) <- changedAddresses) {
              balancesToInvalidate += address -> assetId
              rollbackBalanceHistory(rw, Keys.assetBalance(addressId, assetId), Keys.assetBalanceAt(addressId, assetId, _), currentHeight)
            }
          }

          for ((addressId, address) <- changedAddresses) {
            rw.delete(Keys.changedDataKeys(currentHeight, addressId))

            balancesToInvalidate += (address -> Hearth)
            rollbackBalanceHistory(rw, Keys.hearthBalance(addressId), Keys.hearthBalanceAt(addressId, _), currentHeight)

            rollbackLeaseBalance(rw, addressId, currentHeight)

            balanceAtHeightCache.invalidate((currentHeight, addressId))
            leaseBalanceAtHeightCache.invalidate((currentHeight, addressId))
            discardLeaseBalance(address)

            if (dbSettings.storeTransactionsByAddress) {
              val kTxSeqNr = Keys.addressTransactionSeqNr(addressId, rdb.apiHandle)
              val txSeqNr  = rw.get(kTxSeqNr)
              val kTxHNSeq = Keys.addressTransactionHN(addressId, txSeqNr, rdb.apiHandle)

              rw.get(kTxHNSeq).collect { case (`currentHeight`, _) =>
                rw.delete(kTxHNSeq)
                rw.put(kTxSeqNr, (txSeqNr - 1).max(0))
              }
            }

            if (dbSettings.storeLeaseStatesByAddress) {
              val leaseSeqNrKey = Keys.addressLeaseSeqNr(addressId, rdb.apiHandle)
              val leaseSeqNr    = rw.get(leaseSeqNrKey)
              val leaseSeqKey   = Keys.addressLeaseSeq(addressId, leaseSeqNr, rdb.apiHandle)
              rw.get(leaseSeqKey)
                .flatMap(_.headOption)
                .flatMap(leaseDetails)
                .filter(_.height == currentHeight)
                .foreach { _ =>
                  rw.delete(leaseSeqKey)
                  rw.put(leaseSeqNrKey, (leaseSeqNr - 1).max(0))
                }
            }
          }

          writableDB
            .withResource(loadLeaseIds(_, currentHeight, currentHeight, includeCancelled = true))
            .foreach(rollbackLeaseStatus(rw, _, currentHeight))

          rollbackAssetsInfo(rw, currentHeight)
          rollbackDcapCollateral(rw, currentHeight)
          rollbackKeyed(rw, currentHeight, Keys.reservedAmountKeysAt, Keys.reservedAmount, Keys.reservedAmountHistory)
          rollbackKeyed(rw, currentHeight, Keys.apiKeyBindingKeysAt, Keys.apiKeyBinding, Keys.apiKeyBindingHistory)
          rollbackKeyed(rw, currentHeight, Keys.settledAmountKeysAt, Keys.settledAmount, Keys.settledAmountHistory)
          rollbackKeyed(rw, currentHeight, Keys.workDoneKeysAt, Keys.workDone, Keys.workDoneHistory)

          val blockTxs = loadTransactions(currentHeight, rdb)
          blockTxs.view.zipWithIndex.foreach { case ((_, tx), idx) =>
            val num = TxNum(idx.toShort)
            (tx: @unchecked) match {
              case _: TransferTransaction =>
              // balances already restored

              case _: LeaseTransaction | _: LeaseCancelTransaction =>
              // leases already restored

              case tx: ExchangeTransaction =>
                ordersToInvalidate += rollbackOrderFill(rw, tx.buyOrder.id(), currentHeight)
                ordersToInvalidate += rollbackOrderFill(rw, tx.sellOrder.id(), currentHeight)
              case _: CommitToGenerationTransaction =>
            }

            rw.delete(Keys.transactionAt(currentHeight, num, rdb.txHandle))
            rw.delete(Keys.transactionMetaById(TransactionId(tx.id()), rdb.txMetaHandle))
            rw.delete(Keys.transactionStateSnapshotAt(currentHeight, num, rdb.txSnapshotHandle))
          }

          rw.delete(Keys.generatorBalances(currentHeight, rdb.apiHandle))
          currentPeriod.foreach { currentPeriod =>
            rw.delete(Keys.conflictGenerators(currentPeriod, currentHeight)) // TODO: test

            // Mirrors doAppend: the genesis block commits generators for the current period, any other block for the next one
            val committedPeriod = if (currentHeight == GenesisBlockHeight) currentPeriod else currentPeriod.next
            rw.delete(Keys.committedGenerators(committedPeriod, currentHeight))
            rw.delete(Keys.commitmentTransactions(committedPeriod, currentHeight))
            rw.delete(Keys.registeredEnclaves(committedPeriod, currentHeight))
          }

          discardedMeta.header.flatMap(_.challengedHeader.map(_.generator.toPublicKey.toAddress)) match {
            case Some(addr) =>
              val key        = Keys.maliciousMinerBanHeights(addr.toBytes)
              val banHeights = rw.get(key)
              if (banHeights.size > 1) rw.put(key, banHeights.tail) else rw.delete(key)
            case _ => ()
          }

          rw.delete(Keys.blockMetaAt(currentHeight))
          rw.delete(Keys.changedAddresses(currentHeight))
          rw.delete(Keys.changedHearthBalances(currentHeight))
          rw.delete(Keys.heightOf(discardedMeta.id))
          blockHeightsToInvalidate.addOne(discardedMeta.id)
          rw.delete(Keys.blockStateHash(currentHeight))
          rw.delete(Keys.stateHash(currentHeight))

          val disapprovedFeatures = approvedFeaturesCache.collect { case (id, approvalHeight) if approvalHeight > targetHeight => id }
          if (disapprovedFeatures.nonEmpty) {
            approvedFeaturesCache --= disapprovedFeatures
            rw.put(Keys.approvedFeatures, approvedFeaturesCache)

            activatedFeaturesCache --= disapprovedFeatures // We won't activate them in the future
            rw.put(Keys.activatedFeatures, activatedFeaturesCache)
          }

          val block = createBlock(
            PBBlocks.vanilla(
              discardedMeta.header.getOrElse(throw new IllegalArgumentException(s"Block header is missing at height ${currentHeight.toInt}"))
            ),
            ByteStr(discardedMeta.signature.toByteArray),
            blockTxs.map(_._2)
          ).explicitGet()

          val snapshot = if (isLightMode) {
            Some(BlockSnapshot(block.id(), loadTxStateSnapshotsWithStatus(currentHeight, rdb, block.transactionData)))
          } else None

          DiscardedBlock(block, Caches.toHitSource(discardedMeta), snapshot, Seq.empty) // TODO: generatorBalances
        }

        balancesToInvalidate.result().foreach(discardBalance)
        ordersToInvalidate.result().foreach(discardVolumeAndFee)
        blockHeightsToInvalidate.result().foreach(discardBlockHeight)
        discardedBlock
      }

    log.debug(s"Rollback to block $targetBlockId at $targetHeight completed")
    discardedBlocks.reverse
  }

  private def rollbackBalanceHistory(rw: RW, curBalanceKey: Key[CurrentBalance], balanceNodeKey: Height => Key[BalanceNode], height: Height): Unit = {
    val balance = rw.get(curBalanceKey)
    if (balance.height == height) {
      val prevBalanceNode = rw.get(balanceNodeKey(balance.prevHeight))
      rw.delete(balanceNodeKey(height))
      rw.put(curBalanceKey, CurrentBalance(prevBalanceNode.balance, balance.prevHeight, prevBalanceNode.prevHeight))
    }
  }

  private def rollbackAssetsInfo(rw: RW, currentHeight: Height): Unit = {
    val issuedKey    = Keys.issuedAssets(currentHeight)
    val minFeeKey    = Keys.assetsWithMinFee(currentHeight)
    val issued       = rw.get(issuedKey)
    val minFeeAssets = rw.get(minFeeKey)

    rw.delete(issuedKey)
    rw.delete(minFeeKey)

    issued.foreach { asset =>
      rw.delete(Keys.assetStaticInfo(asset))
      rw.delete(Keys.assetVolumeDetails(asset)(currentHeight))
      rw.filterHistory(Keys.assetVolumeDetailsHistory(asset), currentHeight)
    }

    minFeeAssets.foreach { asset =>
      rw.delete(Keys.assetMinFee(asset)(currentHeight))
      rw.filterHistory(Keys.assetMinFeeHistory(asset), currentHeight)
    }

    (issued ++ minFeeAssets).distinct.foreach(discardAssetDescription)
  }

  private def rollbackDcapCollateral(rw: RW, currentHeight: Height): Unit = {
    def rollbackSingleton(historyKey: Key[Seq[Height]], valueKey: Height => Key[ByteStr]): Unit =
      if (rw.get(historyKey).headOption.contains(currentHeight)) {
        rw.delete(valueKey(currentHeight))
        rw.filterHistory(historyKey, currentHeight)
      }

    rollbackSingleton(Keys.dcapRootCaCrlHistory, Keys.dcapRootCaCrl)
    rollbackSingleton(Keys.dcapPckCrlHistory, Keys.dcapPckCrl)
    rollbackSingleton(Keys.dcapQeIdentityHistory, Keys.dcapQeIdentity)
    rollbackSingleton(Keys.dcapTcbSigningIssuerChainHistory, Keys.dcapTcbSigningIssuerChain)
    rollbackSingleton(Keys.dcapPckCaIssuerChainHistory, Keys.dcapPckCaIssuerChain)

    val fmspcsKey = Keys.dcapTcbInfoFmspcsAt(currentHeight)
    rw.get(fmspcsKey).foreach { fmspc =>
      rw.delete(Keys.dcapTcbInfo(fmspc)(currentHeight))
      rw.filterHistory(Keys.dcapTcbInfoHistory(fmspc), currentHeight)
    }
    rw.delete(fmspcsKey)
  }

  /** Writes one height-keyed ledger family (reserved/apiKeyBinding/settled/workDone): put each entry's value at this
    * height, extend its history (returning the expired keys), then record the per-height suffix index. All four
    * families share this shape.
    */
  private def writeKeyed[K, V](rw: RW, height: Int, threshold: Height, entries: Map[K, V])(
      suffixOf: K => ByteStr,
      value: ByteStr => Height => Key[V],
      history: ByteStr => Key[Seq[Height]],
      keysAt: Height => Key[Seq[ByteStr]]
  ): Seq[Array[Byte]] = {
    val expired = Seq.newBuilder[Array[Byte]]
    for ((k, v) <- entries) {
      val suffix = suffixOf(k)
      rw.put(value(suffix)(Height(height)), v)
      expired ++= updateHistory(rw, history(suffix), threshold, value(suffix))
    }
    if (entries.nonEmpty) rw.put(keysAt(Height(height)), entries.keySet.toSeq.map(suffixOf))
    expired.result()
  }

  /** Rolls back one height-keyed ledger family: delete each suffix's value at this height, trim its history, then
    * drop the per-height suffix index. Mirror of writeKeyed.
    */
  private def rollbackKeyed(
      rw: RW,
      currentHeight: Height,
      keysAt: Height => Key[Seq[ByteStr]],
      value: ByteStr => Height => Key[?],
      history: ByteStr => Key[Seq[Height]]
  ): Unit = {
    val suffixesKey = keysAt(currentHeight)
    rw.get(suffixesKey).foreach { suffix =>
      rw.delete(value(suffix)(currentHeight))
      rw.filterHistory(history(suffix), currentHeight)
    }
    rw.delete(suffixesKey)
  }

  private def rollbackOrderFill(rw: RW, orderId: ByteStr, height: Height): ByteStr = {
    val curVfKey = Keys.filledVolumeAndFee(orderId)
    val vf       = rw.get(curVfKey)
    if (vf.height == height) {
      val vfNodeKey  = Keys.filledVolumeAndFeeAt(orderId, _)
      val prevVfNode = rw.get(vfNodeKey(vf.prevHeight))
      rw.delete(vfNodeKey(height))
      rw.put(curVfKey, CurrentVolumeAndFee(prevVfNode.volume, prevVfNode.fee, vf.prevHeight, prevVfNode.prevHeight))
    }
    orderId
  }

  private def rollbackLeaseBalance(rw: RW, addressId: AddressId, height: Height): Unit = {
    val curLbKey = Keys.leaseBalance(addressId)
    val lb       = rw.get(curLbKey)
    if (lb.height == height) {
      val lbNodeKey  = Keys.leaseBalanceAt(addressId, _)
      val prevLbNode = rw.get(lbNodeKey(lb.prevHeight))
      rw.delete(lbNodeKey(height))
      rw.put(curLbKey, CurrentLeaseBalance(prevLbNode.in, prevLbNode.out, lb.prevHeight, prevLbNode.prevHeight))
    }
  }

  private def rollbackLeaseStatus(rw: RW, leaseId: ByteStr, currentHeight: Height): Unit = {
    rw.delete(Keys.leaseDetails(leaseId)(currentHeight))
    rw.filterHistory(Keys.leaseDetailsHistory(leaseId), currentHeight)
  }

  override def transactionInfo(id: ByteStr): Option[(TxMeta, Transaction)] = readOnly(transactionInfo(id, _))

  override def transactionInfos(ids: Seq[ByteStr]): Seq[Option[(TxMeta, Transaction)]] = readOnly { db =>
    val tms = db.multiGetOpt(ids.view.map(id => Keys.transactionMetaById(TransactionId(id), rdb.txMetaHandle)).toVector, 36)
    val (keys, sizes) = tms.view
      .map {
        case Some(tm) => Keys.transactionAt(Height(tm.height), TxNum(tm.num.toShort), rdb.txHandle) -> tm.size
        case None     => Keys.transactionAt(Height(0), TxNum(0.toShort), rdb.txHandle)              -> 0
      }
      .toVector
      .unzip

    db.multiGetOpt(keys, sizes)
  }

  protected def transactionInfo(id: ByteStr, db: ReadOnlyDB): Option[(TxMeta, Transaction)] =
    for {
      tm        <- db.get(Keys.transactionMetaById(TransactionId(id), rdb.txMetaHandle))
      (txm, tx) <- db.get(Keys.transactionAt(Height(tm.height), TxNum(tm.num.toShort), rdb.txHandle))
    } yield (txm, tx)

  override def transactionMeta(id: ByteStr): Option[TxMeta] = {
    writableDB.get(Keys.transactionMetaById(TransactionId(id), rdb.txMetaHandle)).map { tm =>
      TxMeta(Height(tm.height), TxMeta.Status.fromProtobuf(tm.status), tm.spentComplexity)
    }
  }

  override def transactionSnapshot(id: ByteStr): Option[(StateSnapshot, TxMeta.Status)] = readOnly { db =>
    for {
      meta     <- db.get(Keys.transactionMetaById(TransactionId(id), rdb.txMetaHandle))
      snapshot <- db.get(Keys.transactionStateSnapshotAt(Height(meta.height), TxNum(meta.num.toShort), rdb.txSnapshotHandle))
    } yield PBSnapshots.fromProtobuf(snapshot, id, Height(meta.height))
  }

  override protected def loadBlockHeight(blockId: BlockId): Option[Int] = readOnly(_.get(Keys.heightOf(blockId)))

  override def leaseDetails(leaseId: ByteStr): Option[LeaseDetails] = readOnly { db =>
    for {
      h       <- db.get(Keys.leaseDetailsHistory(leaseId)).headOption
      details <- db.get(Keys.leaseDetails(leaseId)(h))
    } yield details
  }

  override def dcapRootCaCrl: Option[ByteStr] = readOnly(_.fromHistory(Keys.dcapRootCaCrlHistory, Keys.dcapRootCaCrl))
  override def dcapPckCrl: Option[ByteStr]    = readOnly(_.fromHistory(Keys.dcapPckCrlHistory, Keys.dcapPckCrl))
  override def dcapTcbInfo(fmspc: ByteStr): Option[ByteStr] =
    readOnly(_.fromHistory(Keys.dcapTcbInfoHistory(fmspc), Keys.dcapTcbInfo(fmspc)))
  override def dcapQeIdentity: Option[ByteStr] = readOnly(_.fromHistory(Keys.dcapQeIdentityHistory, Keys.dcapQeIdentity))
  override def dcapTcbSigningIssuerChain: Option[ByteStr] =
    readOnly(_.fromHistory(Keys.dcapTcbSigningIssuerChainHistory, Keys.dcapTcbSigningIssuerChain))
  override def dcapPckCaIssuerChain: Option[ByteStr] =
    readOnly(_.fromHistory(Keys.dcapPckCaIssuerChainHistory, Keys.dcapPckCaIssuerChain))

  override def reservedAmount(sender: Address, miner: Address, asset: Asset): Long = {
    val suffix = Keys.reservedAmountSuffix(sender, miner, asset)
    readOnly(_.fromHistory(Keys.reservedAmountHistory(suffix), Keys.reservedAmount(suffix))).getOrElse(0L)
  }

  override def apiKeyBinding(enclavePublicKey: ByteStr, sender: Address): Option[ByteStr] = {
    val suffix = Keys.apiKeyBindingSuffix(enclavePublicKey, sender)
    readOnly(_.fromHistory(Keys.apiKeyBindingHistory(suffix), Keys.apiKeyBinding(suffix)))
  }

  override def settledAmount(client: Address, miner: Address, asset: Asset): Long = {
    val suffix = Keys.settledAmountSuffix(client, miner, asset)
    readOnly(_.fromHistory(Keys.settledAmountHistory(suffix), Keys.settledAmount(suffix))).getOrElse(0L)
  }

  override def workDone(validator: Address, period: GenerationPeriod): Long = {
    val suffix = Keys.workDoneSuffix(validator, period)
    readOnly(_.fromHistory(Keys.workDoneHistory(suffix), Keys.workDone(suffix))).getOrElse(0L)
  }

  // These two caches are used exclusively for balance snapshots. They are not used for portfolios, because there aren't
  // as many miners, so snapshots will rarely be evicted due to overflows.

  private val balanceAtHeightCache = CacheBuilder
    .newBuilder()
    .maximumSize(100000)
    .recordStats()
    .build[(Height, AddressId), BalanceNode]()

  private val leaseBalanceAtHeightCache = CacheBuilder
    .newBuilder()
    .maximumSize(100000)
    .recordStats()
    .build[(Height, AddressId), LeaseBalanceNode]()

  override def balanceAtHeight(address: Address, height: Int, assetId: Asset = Hearth): Option[(Int, Long)] = readOnly { db =>
    db.get(Keys.addressId(address)).flatMap { aid =>
      val key = assetId match {
        case Hearth             => Keys.hearthBalanceAt(aid, Height(height))
        case asset: IssuedAsset => Keys.assetBalanceAt(aid, asset, Height(height))
      }
      Using(db.newIterator) { iter =>
        iter.seekForPrev(key.keyBytes)
        require(iter.isValid && iter.key().startsWith(key.keyBytes.dropRight(Ints.BYTES)))
        Ints.fromByteArray(iter.key().takeRight(Ints.BYTES)) -> key.parse(iter.value()).balance
      }.toOption
    }
  }

  override def balanceSnapshots(address: Address, from: Int, to: Option[BlockId]): Seq[BalanceSnapshot] = readOnly { db =>
    val toHeight = Height(to.flatMap(this.heightOf).getOrElse(this.height))

    val depositPeriods = for {
      r <- GenerationPeriod.enclosedPeriods(
        this.settings.functionalitySettings.generationPeriodLength,
        Height(from),
        toHeight
      )
    } yield r

    addressId(address).fold(Seq(BalanceSnapshot(Height(1), 0, 0, 0, 0))) { addressId =>
      val lastBalance      = balancesCache.get((address, Asset.Hearth))
      val lastLeaseBalance = leaseBalanceCache.get(address)

      @tailrec
      def collectBalanceHistory(acc: Vector[Height], hh: Height): Seq[Height] =
        if (hh < Height(from) || hh <= Height(0))
          acc :+ hh
        else {
          val bn     = balanceAtHeightCache.get((hh, addressId), () => db.get(Keys.hearthBalanceAt(addressId, hh)))
          val newAcc = if (hh > toHeight) acc else acc :+ hh
          collectBalanceHistory(newAcc, bn.prevHeight)
        }

      @tailrec
      def collectLeaseBalanceHistory(acc: Vector[Height], hh: Height): Seq[Height] =
        if (hh < Height(from) || hh <= Height(0))
          acc :+ hh
        else {
          val lbn    = leaseBalanceAtHeightCache.get((hh, addressId), () => db.get(Keys.leaseBalanceAt(addressId, hh)))
          val newAcc = if (hh > toHeight) acc else acc :+ hh
          collectLeaseBalanceHistory(newAcc, lbn.prevHeight)
        }

      val collectedDeposits = depositPeriods.fold((Nil, Map.empty)) { depositPeriods =>
        collectGenerationDepositChanges(db, addressId, depositPeriods.start, depositPeriods.end)
      }
      val slidedDepositHeights = slice(collectedDeposits.changedHeights, Height(from), toHeight)

      val cbh = collectBalanceHistory(Vector.empty, lastBalance.height)
      val wbh = slice(cbh, Height(from), toHeight)

      val clbh = collectLeaseBalanceHistory(Vector.empty, lastLeaseBalance.height)
      val lbh  = slice(clbh, Height(from), toHeight)
      for {
        (wh, lh, dh) <- merge3(wbh, lbh, slidedDepositHeights)
        wb = balanceAtHeightCache.get((wh, addressId), () => db.get(Keys.hearthBalanceAt(addressId, wh)))
        lb = leaseBalanceAtHeightCache.get((lh, addressId), () => db.get(Keys.leaseBalanceAt(addressId, lh)))
        d  = collectedDeposits.depositSize.getOrElse(dh, 0L)
      } yield {
        val maxHeight = wh.max(lh).max(dh)
        BalanceSnapshot(maxHeight, wb.balance, lb.in, lb.out, d)
      }
    }
  }

  private def collectGenerationDepositChanges(
      db: ReadOnlyDB,
      addressId: AddressId,
      fromIncl: GenerationPeriod,
      toIncl: GenerationPeriod
  ): (changedHeights: Seq[Height], depositSize: Map[Height, Long]) = {
    val depositDiffHeights = mutable.Map.empty[Height, Int] // +1 - added a deposit, -1 - released

    val toInclCommitted = toIncl.next // A generator commits to a next period, this is what we see in DB
    committedHeights(db, addressId, fromIncl, toInclCommitted).foreach { committed =>
      val punishmentHeight = conflictGenerators(committed.period).heightOf(committed.index).map(_.next)
      val releaseHeight    = punishmentHeight.getOrElse(committed.period.next.start)

      depositDiffHeights.updateWith(committed.height)(orig => Some(orig.getOrElse(0) + 1))
      depositDiffHeights.put(releaseHeight, -1)
    }

    if (depositDiffHeights.isEmpty) (Seq(Height(0)), Map(Height(0) -> 0L))
    else {
      val sortedDiffHeights = depositDiffHeights.toList.sortBy { case (h, _) => h }
      val (_, changedHeights, depositSize) = sortedDiffHeights.foldLeft((0L, List.empty[Height], Map.empty[Height, Long])) {
        case ((currentDeposit, changedHeights, depositSize), (depositHeight, diffN)) =>
          val updatedDeposit = currentDeposit + diffN * DepositInEmbers
          (updatedDeposit, depositHeight :: changedHeights, depositSize.updated(depositHeight, updatedDeposit))
      }

      (changedHeights, depositSize)
    }
  }

  private type CommittedHeightsResult = (period: GenerationPeriod, height: Height, index: GeneratorIndex)
  private def committedHeights(
      db: ReadOnlyDB,
      addressId: AddressId,
      fromIncl: GenerationPeriod,
      toIncl: GenerationPeriod
  ): Seq[CommittedHeightsResult] = {
    val committedGeneratorsKey = Keys.committedGenerators(fromIncl, Height(0))
    def getSeekBytes(at: GenerationPeriod): Array[Byte] =
      Keys.committedGenerators(at, Height(0)).keyBytes.dropRight(Ints.BYTES) // Drop height

    var r = Seq.empty[CommittedHeightsResult]
    /* Because a generator can commit once in period, we can skip all heights in period, those are after committed.
     How it works for a generator with addressId. E.g. it is g1:
     periods:    |       p2      |      p3    |
     heights:    |  h1      | h2 |   h3  | h4 |
     generators: | g2,   g1 | g3 | g3,g2 | g1 |
     iterator    | s p2, ^  |    | s p3  | ^  | s - seek
     */
    Using.resource(db.newIterator) { committedIter =>
      committedIter.seek(getSeekBytes(fromIncl))

      var currentCommittedPeriodStart = GenesisBlockHeight
      var currentGeneratorIndex       = 0
      var continue                    = true
      val prefixBytes                 = KeyTag.CommittedGenerators.prefixBytes
      val prefixLen                   = prefixBytes.length
      while (committedIter.isValid && committedIter.key().startsWith(prefixBytes) && continue) {
        val committedPeriodStartBytes = committedIter.key().slice(prefixLen, prefixLen + Ints.BYTES)
        val committedPeriodStart      = Height(Ints.fromByteArray(committedPeriodStartBytes))

        continue = committedPeriodStart <= toIncl.start
        if (continue) {
          if (currentCommittedPeriodStart != committedPeriodStart) {
            currentCommittedPeriodStart = committedPeriodStart
            currentGeneratorIndex = 0 // Reset index on new period
          }

          val committedGenerators = committedGeneratorsKey.parse(committedIter.value()).getOrElse(Seq.empty)
          val generatorIndex = committedGenerators.view.zipWithIndex.collectFirst {
            case ((currentAddressId, _, _), i) if currentAddressId == addressId => GeneratorIndex(currentGeneratorIndex + i)
          }

          generatorIndex match {
            case None =>
              committedIter.next()
              currentGeneratorIndex += committedGenerators.size

            case Some(generatorIndex) =>
              val committedPeriod = this
                .generationPeriodOf(committedPeriodStart)
                .getOrElse(
                  throw new IllegalStateException(
                    s"Database contains committed generators on height before activation: committedPeriodStart=$committedPeriodStart," +
                      s"db key=${Base64.encode(committedIter.key())}"
                  )
                )

              val nextPeriod = committedPeriod.next

              val depositHeightBytes = committedIter.key().takeRight(Ints.BYTES)
              val depositHeight      = Height(Ints.fromByteArray(depositHeightBytes))

              r = r.appended((committedPeriod, depositHeight, generatorIndex))

              // It can't committ twice in a generation period
              if (nextPeriod.start <= toIncl.start) committedIter.seek(getSeekBytes(nextPeriod))
              else continue = false
          }
        }
      }
    }
    r
  }

  override def loadHeightOf(blockId: ByteStr): Option[Int] = blockHeightCache.get(blockId)

  override def featureVotes(height: Height): Map[Short, Int] = readOnly { db =>
    settings.functionalitySettings
      .activationWindow(height.toInt)
      .flatMap { h =>
        val height = Height(h)
        db.get(Keys.blockMetaAt(height))
          .flatMap(_.header)
          .fold(Seq.empty[Short])(_.featureVotes.map(_.toShort))
      }
      .groupBy(identity)
      .view
      .mapValues(_.size)
      .toMap
  }

  def loadStateHash(height: Height): Option[StateHash] = readOnly { db =>
    db.get(Keys.stateHash(height))
  }

  // TODO: maybe add length constraint
  def loadBalanceHistory(address: Address): Seq[(Int, Long)] = writableDB.withResource { dbResource =>
    dbResource.get(Keys.addressId(address)).fold(Seq.empty[(Int, Long)]) { aid =>
      new HearthBalanceIterator(aid, dbResource).asScala.toSeq
    }
  }

  override def effectiveBalanceBanHeights(address: Address): Seq[Int] =
    readOnly(_.get(Keys.maliciousMinerBanHeights(address.toBytes))).map(_.toInt)

  override def loadCommittedGenerators(at: GenerationPeriod): IndexedSeq[CommittedGenerator] = {
    val approxGenerators = settings.functionalitySettings.maxValidEndorsers // Rough buffer size
    val rawGenerators    = new mutable.ArrayBuffer[(BlsPublicKey, ByteStr)](approxGenerators)
    val addressIds       = new mutable.ArrayBuffer[AddressId](approxGenerators)

    val key = Keys.committedGenerators(at, at.start)
    val addresses = rdb.db.readOnly { ro =>
      ro.iterateOver(key.keyBytes.dropRight(Ints.BYTES)) { dbEntry => // Drop height
        val xs = key.parse(dbEntry.getValue).getOrElse(Seq.empty)
        xs.foreach { (addressId, blsPK, vrfPK) =>
          rawGenerators.append(blsPK -> vrfPK)
          addressIds.append(addressId)
        }
      }

      // An address is stored as the bytes of its hash, see Keys.idToAddress
      ro.multiGet(addressIds.map(Keys.idToAddress), tech.hearth.crypto.Address.HASH_LEN)
    }

    addresses.view
      .lazyZip(rawGenerators)
      .lazyZip(addressIds)
      .collect {
        case (Some(address), (blsPK, vrfPK), _) => CommittedGenerator(address, blsPK, vrfPK)
        case (None, _, aid)                     => throw new IllegalStateException(s"Can't find address for address id $aid")
      }
      .toIndexedSeq
  }

  override def loadRegisteredEnclaves(at: GenerationPeriod): IndexedSeq[RegisteredEnclave] = {
    val result = new mutable.ArrayBuffer[RegisteredEnclave]()
    val key    = Keys.registeredEnclaves(at, at.start)
    rdb.db.readOnly { ro =>
      ro.iterateOver(key.keyBytes.dropRight(Ints.BYTES)) { dbEntry => // Drop height
        result ++= key.parse(dbEntry.getValue).getOrElse(Seq.empty)
      }
    }
    result.toIndexedSeq
  }

  override def loadConflictGenerators(at: GenerationPeriod): ConflictGenerators = {
    val key = Keys.conflictGenerators(at, at.start)
    rdb.db.readOnly { ro =>
      var r = ConflictGenerators.empty
      ro.iterateOver(key.keyBytes.dropRight(Ints.BYTES)) { dbEntry => // Drop height
        val h = Height(Ints.fromByteArray(dbEntry.getKey.takeRight(Ints.BYTES)))
        r = r.appendAll(h, key.parse(dbEntry.getValue)*)
      }
      r
    }
  }

  override def lastStateHash(refId: Option[ByteStr]): ByteStr =
    snapshotStateHash(height)

  def snapshotStateHash(height: Int): ByteStr =
    readOnly(_.get(Keys.blockStateHash(Height(height))))
}
