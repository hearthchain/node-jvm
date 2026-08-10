package tech.hearth.database

import com.google.common.cache.{CacheBuilder, CacheLoader, LoadingCache}
import com.google.common.collect.ArrayListMultimap
import com.google.protobuf.ByteString
import com.typesafe.scalalogging.StrictLogging
import tech.hearth.account.{Address, PublicKey}
import tech.hearth.block.{Block, SignedBlockHeader}
import tech.hearth.common.state.ByteStr
import tech.hearth.crypto.bls.BlsPublicKey
import tech.hearth.database.protobuf.{BlockMetaExt, BlockMeta as PBBlockMeta}
import tech.hearth.protobuf.block.PBBlocks
import tech.hearth.protobuf.toByteStr
import tech.hearth.protobuf.transaction.PBAmounts
import tech.hearth.settings.DBSettings
import tech.hearth.state.*
import tech.hearth.transaction.Asset.{IssuedAsset, Hearth}
import tech.hearth.transaction.{Asset, CommitToGenerationTransaction, DiscardedBlocks, Transaction}
import tech.hearth.utils.ObservedLoadingCache
import monix.reactive.Observer

import java.{lang, util}
import scala.collection.immutable.VectorMap
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.reflect.ClassTag

abstract class Caches extends Blockchain, Storage, StrictLogging {
  import Caches.*

  val dbSettings: DBSettings

  @volatile
  private var current = loadCurrentBlock()

  @volatile
  private var currentFinalizedHeight = loadFinalizedHeight()

  private def loadCurrentBlock() = {
    val height = loadHeight()
    CurrentBlockInfo(height, loadBlockMeta(height), loadTxs(height))
  }

  protected def loadHeight(): Height
  protected def loadFinalizedHeight(): Option[Height]

  protected def loadBlockMeta(height: Height): Option[PBBlockMeta]
  protected def loadTxs(height: Height): Seq[Transaction]

  override def height: Int                     = current.height.toInt
  override def finalizedHeight: Option[Height] = currentFinalizedHeight

  override def score: BigInt = current.score

  override def lastBlock: Option[Block] = current.block

  override def blockHeader(height: Int): Option[SignedBlockHeader] =
    if (current.height == Height(height)) current.signedHeader else loadBlockMeta(Height(height)).map(toSignedHeader)

  override def hitSource(height: Int): Option[ByteStr] =
    if (current.height == Height(height)) current.hitSource else loadBlockMeta(Height(height)).map(toHitSource)

  override def carryFee(refId: ByteStr): Either[String, BlockFee] =
    for {
      b <- (if (current.id.contains(refId)) current.meta else blockHeightCache.get(refId).flatMap(h => loadBlockMeta(Height(h))))
        .toRight(s"Meta for block $refId is not available")
      bf <- BlockFee.combineAll(b.carryFee.map(PBAmounts.toAssetAndAmount))
    } yield bf

  def loadHeightOf(blockId: ByteStr): Option[Int]

  override def heightOf(blockId: ByteStr): Option[Int] = if (current.id.contains(blockId)) Some(height) else loadHeightOf(blockId)

  protected val leaseBalanceCache: LoadingCache[Address, CurrentLeaseBalance] =
    cache(dbSettings.maxCacheSize, loadLeaseBalance, keys => loadLeaseBalances(keys.asScala.toSeq).asJava)
  protected def loadLeaseBalance(address: Address): CurrentLeaseBalance
  protected def loadLeaseBalances(addresses: Seq[Address]): Map[Address, CurrentLeaseBalance]
  protected def discardLeaseBalance(address: Address): Unit = leaseBalanceCache.invalidate(address)
  override def leaseBalance(address: Address): LeaseBalance = {
    val currentLeaseBalance = leaseBalanceCache.get(address)
    LeaseBalance(currentLeaseBalance.in, currentLeaseBalance.out)
  }

  override def leaseBalances(addresses: Seq[Address]): Map[Address, LeaseBalance] = {
    leaseBalanceCache
      .getAll(addresses.asJava)
      .asScala
      .view
      .map { case (address, leaseBalance) =>
        address -> LeaseBalance(leaseBalance.in, leaseBalance.out)
      }
      .toMap
  }

  protected val balancesCache: LoadingCache[(Address, Asset), CurrentBalance] =
    cache(dbSettings.maxCacheSize * 16, loadBalance, keys => loadBalances(keys.asScala.toSeq).asJava)
  protected def discardBalance(key: (Address, Asset)): Unit         = balancesCache.invalidate(key)
  override def balance(address: Address, mayBeAssetId: Asset): Long = balancesCache.get(address -> mayBeAssetId).balance

  override def balances(req: Seq[(Address, Asset)]): Map[(Address, Asset), Long] =
    balancesCache
      .getAll(req.asJava)
      .asScala
      .view
      .map { case ((address, asset), balance) =>
        (address, asset) -> balance.balance
      }
      .toMap

  def loadCacheData(addresses: Set[Address], orders: Set[ByteStr]): Unit = {
    addressIdCache.getAll(addresses.asJava)
    balancesCache.getAll(addresses.map(_ -> Hearth).asJava)
    leaseBalanceCache.getAll(addresses.asJava)
    volumeAndFeeCache.getAll(orders.asJava)
  }

  override def hearthBalances(addresses: Seq[Address]): Map[Address, Long] =
    balancesCache
      .getAll(addresses.map(_ -> Hearth).asJava)
      .asScala
      .view
      .map { case ((address, _), balance) =>
        address -> balance.balance
      }
      .toMap
  protected def loadBalance(req: (Address, Asset)): CurrentBalance
  protected def loadBalances(req: Seq[(Address, Asset)]): Map[(Address, Asset), CurrentBalance]
  protected def loadHearthBalances(req: Seq[(Address, Asset)]): Map[(Address, Asset), CurrentBalance]

  private val assetDescriptionCache: LoadingCache[IssuedAsset, Option[AssetDescription]] = cache(dbSettings.maxCacheSize, loadAssetDescription)
  protected def loadAssetDescription(asset: IssuedAsset): Option[AssetDescription]
  protected def discardAssetDescription(asset: IssuedAsset): Unit             = assetDescriptionCache.invalidate(asset)
  override def assetDescription(asset: IssuedAsset): Option[AssetDescription] = assetDescriptionCache.get(asset)

  private val volumeAndFeeCache: LoadingCache[ByteStr, CurrentVolumeAndFee] =
    cache(dbSettings.maxCacheSize, loadVolumeAndFee, keys => loadVolumesAndFees(keys.asScala.toSeq).asJava)
  protected def loadVolumeAndFee(orderId: ByteStr): CurrentVolumeAndFee
  protected def loadVolumesAndFees(orders: Seq[ByteStr]): Map[ByteStr, CurrentVolumeAndFee]
  protected def discardVolumeAndFee(orderId: ByteStr): Unit = volumeAndFeeCache.invalidate(orderId)
  override def filledVolumeAndFee(orderId: ByteStr): VolumeAndFee = {
    val curVf = volumeAndFeeCache.get(orderId)
    VolumeAndFee(curVf.volume, curVf.fee)
  }

  private var lastAddressId = loadMaxAddressId()
  protected def loadMaxAddressId(): Long

  private val addressIdCache: LoadingCache[Address, Option[AddressId]] =
    cache(dbSettings.maxCacheSize, loadAddressId, keys => loadAddressIds(keys.asScala.toSeq).asJava)
  protected def loadAddressId(address: Address): Option[AddressId]
  protected def loadAddressIds(addresses: Seq[Address]): Map[Address, Option[AddressId]]

  protected def addressIdWithFallback(address: Address, newAddresses: Map[Address, AddressId]): AddressId =
    newAddresses.getOrElse(address, addressIdCache.get(address).get)

  private[database] def addressId(address: Address): Option[AddressId] = addressIdCache.get(address)
  private[database] def addressIds(addresses: Seq[Address]): Map[Address, Option[AddressId]] =
    addressIdCache.getAll(addresses.asJava).asScala.toMap

  protected val blockHeightCache: LoadingCache[ByteStr, Option[Int]] = cache(dbSettings.maxRollbackDepth + 1000, loadBlockHeight)
  protected def loadBlockHeight(blockId: ByteStr): Option[Int]
  protected def discardBlockHeight(blockId: ByteStr): Unit = blockHeightCache.invalidate(blockId)

  @volatile
  protected var approvedFeaturesCache: Map[Short, Height] = loadApprovedFeatures()
  protected def loadApprovedFeatures(): Map[Short, Height]
  override def approvedFeatures: Map[Short, Height] = approvedFeaturesCache

  // Also contains features those will be activated in the future (activationHeight > currentHeight), because they were approved now or before.
  @volatile
  protected var activatedFeaturesCache: Map[Short, Height] = loadActivatedFeatures()
  protected def loadActivatedFeatures(): Map[Short, Height]
  override def activatedFeatures: Map[Short, Height] = activatedFeaturesCache

  @volatile
  private var committedGeneratorsCache = Map.empty[GenerationPeriod, IndexedSeq[CommittedGenerator]] // Only this and next periods
  override def committedGenerators(at: GenerationPeriod): IndexedSeq[CommittedGenerator] =
    this.currentGenerationPeriod.fold(Vector.empty) { curr =>
      if (at == curr || at == curr.next) {
        committedGeneratorsCache.getOrElse(
          at, {
            val r = loadCommittedGenerators(at)
            committedGeneratorsCache = committedGeneratorsCache.updated(at, r)
            r
          }
        )
      } else loadCommittedGenerators(at)
    }
  protected def loadCommittedGenerators(at: GenerationPeriod): IndexedSeq[CommittedGenerator]

  @volatile
  private var conflictGeneratorsCache = Map.empty[GenerationPeriod, ConflictGenerators]
  override def conflictGenerators(at: GenerationPeriod): ConflictGenerators =
    this.currentGenerationPeriod.fold(ConflictGenerators.empty) { curr =>
      if (at == curr || at == curr.next) {
        conflictGeneratorsCache.getOrElse(
          at, {
            val r = loadConflictGenerators(at)
            conflictGeneratorsCache = conflictGeneratorsCache.updated(at, r)
            r
          }
        )
      } else loadConflictGenerators(at)
    }
  protected def loadConflictGenerators(at: GenerationPeriod): ConflictGenerators

  protected def doAppend(
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
      conflictGenerators: Seq[GeneratorIndex],
      stateHash: StateHashBuilder.Result
  ): Unit

  override def append(
      snapshot: StateSnapshot,
      carryFee: BlockFee,
      totalFee: BlockFee,
      reward: Option[Long],
      hitSource: ByteStr,
      computedBlockStateHash: ByteStr,
      block: Block,
      newFinalizedHeight: Height,
      generatorSet: GeneratorSet
  ): Unit = {
    val newHeight = current.height + 1
    val newScore  = block.blockScore() + current.score

    val conflictEndorsersInPrevBlock = for {
      parentBlock <- lastBlock
      voting      <- parentBlock.header.finalizationVoting
    } yield voting.conflict.size

    val totalHearthAmount = current.meta.fold(settings.initialBalance)(_.totalHearthAmount) +
      reward.getOrElse(0L) * this.blockRewardBoost(newHeight) -
      conflictEndorsersInPrevBlock.getOrElse(0) * CommitToGenerationTransaction.DepositInEmbers

    val newMeta = PBBlockMeta(
      Some(PBBlocks.protobuf(block.header)),
      ByteString.copyFrom(block.signature.arr),
      ByteString.copyFrom(block.id().arr),
      newHeight.toInt,
      block.bytes().length,
      block.transactionData.size,
      PBAmounts.fromBlockFee(totalFee),
      PBAmounts.fromBlockFee(carryFee),
      reward.getOrElse(0),
      ByteString.copyFrom(hitSource.arr),
      ByteString.copyFrom(newScore.toByteArray),
      totalHearthAmount
    )
    current = CurrentBlockInfo(newHeight, Some(newMeta), block.transactionData)
    currentFinalizedHeight = Some(newFinalizedHeight)

    val newAddresses =
      mutable.Set[Address]() ++
        (snapshot.balances.map(_._1._1) ++ snapshot.leaseBalances.keys)
          .filter(addressIdCache.get(_).isEmpty)

    for (address <- snapshot.transactions.flatMap(_._2.affected) if addressIdCache.get(address).isEmpty)
      newAddresses += address

    // A genesis generator may have no balance entry of its own
    for (gc <- snapshot.nextCommittedGenerators; address = gc.sender.toAddress if addressIdCache.get(address).isEmpty)
      newAddresses += address

    val newAddressIds = (for {
      (address, offset) <- newAddresses.zipWithIndex
    } yield address -> AddressId(lastAddressId + offset + 1)).toMap

    lastAddressId += newAddressIds.size

    val leaseBalancesWithNodes = snapshot.leaseBalances.flatMap { case (address, lease) =>
      val prevCurrentLeaseBalance = leaseBalanceCache.get(address)
      if (prevCurrentLeaseBalance.in == lease.in && prevCurrentLeaseBalance.out == lease.out)
        Map()
      else
        Map(
          address ->
            (
              CurrentLeaseBalance(lease.in, lease.out, Height(height), prevCurrentLeaseBalance.height),
              LeaseBalanceNode(lease.in, lease.out, prevCurrentLeaseBalance.height)
            )
        )
    }
    val leaseBalances = leaseBalancesWithNodes.map { case (address, (balance, _)) =>
      (address, balance)
    }

    val addressTransactions      = ArrayListMultimap.create[AddressId, TransactionId]()
    var commitmentTransactionIds = Vector.empty[TransactionId]
    for ((_, nti) <- snapshot.transactions) {
      for (addr <- nti.affected)
        addressTransactions.put(addressIdWithFallback(addr, newAddressIds), TransactionId(nti.transaction.id()))

      nti.transaction match {
        case txn: CommitToGenerationTransaction => commitmentTransactionIds = commitmentTransactionIds.appended(TransactionId(txn.id()))
        case _                                  =>
      }
    }

    // Taken from the snapshot rather than from the transactions, because the genesis block commits generators without any:
    // for a regular block CommitToGenerationTransactionDiff puts the very same commitments there, in the same order.
    val committedGeneratorsWithAddr = snapshot.nextCommittedGenerators.map(_.toCommittedGenerator).toVector
    val committedGenerators = committedGeneratorsWithAddr.map { cg =>
      (addressIdWithFallback(cg.address, newAddressIds), cg.endorserPublicKey, cg.vrfPublicKey)
    }

    val conflictGenerators = for {
      v <- block.header.finalizationVoting.toSeq
      e <- v.conflict
    } yield e.endorserIndex

    val committedPeriod = this.generationPeriodOf(current.height) match {
      case None =>
        require(
          committedGenerators.isEmpty && conflictGenerators.isEmpty,
          s"Expected empty conflict and next committed generators, got: committedGenerators=$committedGenerators, conflictGenerators=$conflictGenerators"
        )
        None

      case Some(currPeriod) =>
        // Generators committed in the genesis block generate from the very first period, everyone else from the next one
        val committedPeriod = if (current.height == GenesisBlockHeight) currPeriod else currPeriod.next

        if (committedGenerators.nonEmpty)
          committedGeneratorsCache = committedGeneratorsCache.updatedWith(committedPeriod) { orig =>
            Some(orig.getOrElse(Vector.empty) ++ committedGeneratorsWithAddr)
          }

        if (conflictGenerators.nonEmpty)
          conflictGeneratorsCache = conflictGeneratorsCache.updatedWith(currPeriod) { orig =>
            Some(orig.getOrElse(ConflictGenerators.empty).appendAll(current.height, conflictGenerators*))
          }

        Some(committedPeriod)
    }

    val updatedBalanceNodes = for {
      case ((address, asset), amount) <- snapshot.balances
      key         = (address, asset)
      prevBalance = balancesCache.get(key) if prevBalance.balance != amount
    } yield key -> (
      CurrentBalance(amount, Height(height), prevBalance.height),
      BalanceNode(amount, prevBalance.height)
    )

    val orderFillsWithNodes = for {
      (orderId, VolumeAndFee(volume, fee)) <- snapshot.orderFills
    } yield {
      val prevData = volumeAndFeeCache.get(orderId)
      val current  = CurrentVolumeAndFee(volume, fee, Height(height), prevData.height)
      val node     = VolumeAndFeeNode(volume, fee, prevData.height)
      orderId -> (current, node)
    }

    val stateHash = new StateHashBuilder
    for (((address, asset), (amount, _)) <- updatedBalanceNodes) asset match {
      case Hearth             => stateHash.addHearthBalance(address, amount.balance)
      case asset: IssuedAsset => stateHash.addAssetBalance(address, asset, amount.balance)
    }
    for ((address, lease) <- leaseBalances) stateHash.addLeaseBalance(address, lease.in, lease.out)
    for (leaseId <- snapshot.newLeases.keys) if (!snapshot.cancelledLeases.contains(leaseId)) stateHash.addLeaseStatus(leaseId, isActive = true)
    for (leaseId <- snapshot.cancelledLeases.keys) stateHash.addLeaseStatus(leaseId, isActive = false)
    snapshot.nextCommittedGenerators.foreach(stateHash.addNextCommittedGenerator)
    stateHash.addCommittedGeneratorBalances(generatorSet.sortBy(_.index).map(_.balance))

    doAppend(
      newMeta,
      snapshot,
      computedBlockStateHash,
      newAddressIds,
      VectorMap() ++ updatedBalanceNodes.map { case ((address, asset), v) => (addressIdWithFallback(address, newAddressIds), asset) -> v },
      leaseBalancesWithNodes.map { case (address, balance) => addressIdWithFallback(address, newAddressIds) -> balance },
      orderFillsWithNodes,
      addressTransactions.asMap(),
      newFinalizedHeight,
      generatorSet,
      committedGenerators,
      committedPeriod,
      commitmentTransactionIds,
      conflictGenerators,
      stateHash.result()
    )

    val assetsToInvalidate =
      snapshot.assetStatics.keySet ++
        snapshot.assetVolumes.keySet ++
        snapshot.minAssetFees.keySet

    for ((address, id) <- newAddressIds) addressIdCache.put(address, Some(id))
    for ((orderId, (volumeAndFee, _)) <- orderFillsWithNodes) volumeAndFeeCache.put(orderId, volumeAndFee)
    for (((address, asset), (newBalance, _)) <- updatedBalanceNodes) balancesCache.put((address, asset), newBalance)
    for (id <- assetsToInvalidate) assetDescriptionCache.invalidate(id)
    leaseBalanceCache.putAll(leaseBalances.asJava)

    this.generationPeriodOf(current.height).foreach { currPeriod =>
      committedGeneratorsCache = committedGeneratorsCache.view.filterKeys(_ >= currPeriod).toMap
      conflictGeneratorsCache = conflictGeneratorsCache.view.filterKeys(_ >= currPeriod).toMap
    }
  }

  protected def doRollback(targetHeight: Height): DiscardedBlocks

  override def rollbackTo(height: Height): Either[String, DiscardedBlocks] = {
    for {
      _ <- Either
        .cond(
          height >= safeRollbackHeight,
          (),
          s"Rollback is possible only to the block at the height: $safeRollbackHeight"
        )
      discardedBlocks = doRollback(height)
    } yield {
      current = loadCurrentBlock()
      currentFinalizedHeight = loadFinalizedHeight()

      activatedFeaturesCache = loadActivatedFeatures()
      approvedFeaturesCache = loadApprovedFeatures()

      committedGeneratorsCache = Map.empty
      conflictGeneratorsCache = Map.empty

      discardedBlocks
    }
  }
}

object Caches {
  case class CurrentBlockInfo(height: Height, meta: Option[PBBlockMeta], transactions: Seq[Transaction]) {
    lazy val score: BigInt                           = meta.filterNot(_.totalScore.isEmpty).fold(BigInt(0))(m => BigInt(m.totalScore.toByteArray))
    lazy val block: Option[Block]                    = signedHeader.map(h => Block(h.header, h.signature, transactions))
    lazy val signedHeader: Option[SignedBlockHeader] = meta.map(toSignedHeader)
    lazy val id: Option[ByteStr]                     = meta.map(_.id)
    lazy val hitSource: Option[ByteStr]              = meta.map(toHitSource)
  }

  def toHitSource(m: PBBlockMeta): ByteStr = (if (m.vrf.isEmpty) m.getHeader.generationSignature else m.vrf).toByteStr

  def toSignedHeader(m: PBBlockMeta): SignedBlockHeader = SignedBlockHeader(PBBlocks.vanilla(m.getHeader), m.signature.toByteStr)

  def cache[K <: AnyRef, V <: AnyRef](maximumSize: Int, loader: K => V): LoadingCache[K, V] =
    CacheBuilder
      .newBuilder()
      .maximumSize(maximumSize)
      .recordStats()
      .build(new CacheLoader[K, V] {
        override def load(key: K): V                                      = loader(key)
        override def loadAll(keys: lang.Iterable[? <: K]): util.Map[K, V] = new util.HashMap[K, V]()
      })

  def cache[K <: AnyRef, V <: AnyRef](
      maximumSize: Int,
      loader: K => V,
      batchLoader: lang.Iterable[? <: K] => util.Map[K, V]
  ): LoadingCache[K, V] =
    CacheBuilder
      .newBuilder()
      .maximumSize(maximumSize)
      .recordStats()
      .build(new CacheLoader[K, V] {
        override def load(key: K): V                                      = loader(key)
        override def loadAll(keys: lang.Iterable[? <: K]): util.Map[K, V] = batchLoader(keys)
      })

  def observedCache[K <: AnyRef, V <: AnyRef](maximumSize: Int, changed: Observer[K], loader: K => V)(implicit ct: ClassTag[K]): LoadingCache[K, V] =
    new ObservedLoadingCache(cache(maximumSize, loader), changed)
}
