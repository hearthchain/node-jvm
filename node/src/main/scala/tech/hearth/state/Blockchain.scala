package tech.hearth.state

import tech.hearth.account.PublicKey
import tech.hearth.block.Block.*
import tech.hearth.block.{Block, BlockHeader, SignedBlockHeader}
import tech.hearth.common.state.ByteStr
import tech.hearth.consensus.GeneratingBalanceProvider
import tech.hearth.features.{BlockchainFeature, BlockchainFeatureStatus}
import tech.hearth.settings.BlockchainSettings
import tech.hearth.state.TxMeta.Status
import tech.hearth.transaction.Asset.{IssuedAsset, Hearth}
import tech.hearth.transaction.{Asset, CommitToGenerationTransaction, Transaction}
import tech.hearth.utils.Numbers
import tech.hearth.crypto.Address

trait Blockchain {
  def settings: BlockchainSettings

  def height: Int

  def finalizedHeight: Option[Height]
  def finalizedHeightAt(at: Height): Option[Height]

  def score: BigInt

  def blockHeader(height: Int): Option[SignedBlockHeader]
  def hitSource(height: Int): Option[ByteStr]

  def carryFee(refId: ByteStr): Either[String, BlockFee]

  def heightOf(blockId: ByteStr): Option[Int]

  /** Features related */
  def approvedFeatures: Map[Short, Height]
  def activatedFeatures: Map[Short, Height]
  def featureVotes(height: Height): Map[Short, Int]

  /** Block reward related */
  def blockReward(height: Int): Option[Long]

  def hearthAmount(height: Int): BigInt

  def transactionInfo(id: ByteStr): Option[(TxMeta, Transaction)]
  def transactionInfos(ids: Seq[ByteStr]): Seq[Option[(TxMeta, Transaction)]]
  def transactionMeta(id: ByteStr): Option[TxMeta]
  def transactionSnapshot(id: ByteStr): Option[(StateSnapshot, Status)]

  def containsTransaction(tx: Transaction): Boolean

  def assetDescription(id: IssuedAsset): Option[AssetDescription]

  def leaseDetails(leaseId: ByteStr): Option[LeaseDetails]

  // DCAP collateral (see the StartBoost consensus plan): the current value of each permissionless collateral
  // slot, resolved through the history mechanism so rollback can undo an update, same as leaseDetails above.
  def dcapRootCaCrl: Option[ByteStr]
  def dcapPckCrl: Option[ByteStr]
  def dcapTcbInfo(fmspc: ByteStr): Option[ByteStr]
  def dcapQeIdentity: Option[ByteStr]
  def dcapTcbSigningIssuerChain: Option[ByteStr]

  def filledVolumeAndFee(orderId: ByteStr): VolumeAndFee

  def balanceAtHeight(address: Address, height: Int, assetId: Asset = Hearth): Option[(Int, Long)]

  /** Retrieves Hearth balance snapshot in the [from, to] range (inclusive).
    * Used only for getting a regular balance with confirmations and effective balance calculations.
    * @return Balance snapshots from most recent to oldest. May contain consecutive duplicate values
    */
  def balanceSnapshots(address: Address, from: Int, to: Option[BlockId]): Seq[BalanceSnapshot]

  def leaseBalance(address: Address): LeaseBalance

  def leaseBalances(addresses: Seq[Address]): Map[Address, LeaseBalance]

  def balance(address: Address, mayBeAssetId: Asset = Hearth): Long

  def balances(req: Seq[(Address, Asset)]): Map[(Address, Asset), Long]

  def hearthBalances(addresses: Seq[Address]): Map[Address, Long]

  def effectiveBalanceBanHeights(address: Address): Seq[Int]

  // TODO: named?
  def committedGenerators(at: GenerationPeriod): IndexedSeq[CommittedGenerator]

  def conflictGenerators(at: GenerationPeriod): ConflictGenerators

  def lastStateHash(refId: Option[ByteStr]): ByteStr
}

object Blockchain {
  implicit class BlockchainExt(private val blockchain: Blockchain) extends AnyVal {
    def isEmpty: Boolean = blockchain.height == 0

    def isSponsorshipActive: Boolean = false
    def isNGActive: Boolean          = true // NG is active

    def parentHeader(block: BlockHeader, back: Int = 1): Option[BlockHeader] =
      blockchain
        .heightOf(block.reference)
        .map(_ - (back - 1).max(0))
        .flatMap(h => blockchain.blockHeader(h).map(_.header))

    def contains(block: Block): Boolean     = blockchain.contains(block.id())
    def contains(blockId: BlockId): Boolean = blockchain.heightOf(blockId).isDefined

    def finalizedHeightAtOrFallback(maxRollbackLength: Int, at: Height): Height = {
      val finalizedAt = blockchain.finalizedHeightAt(at)
      Blockchain.finalizedHeightOrFallback(at, finalizedAt, maxRollbackLength)
    }

    def finalizedHeightOrFallback(maxRollbackLength: Int): Height =
      Blockchain.finalizedHeightOrFallback(Height(blockchain.height), blockchain.finalizedHeight, maxRollbackLength)

    def blockId(atHeight: Int): Option[BlockId] = blockchain.blockHeader(atHeight).map(_.id())

    def lastBlockHeader: Option[SignedBlockHeader] = blockchain.blockHeader(blockchain.height)
    def lastBlockId: Option[BlockId]               = lastBlockHeader.map(_.id())
    def lastBlockTimestamp: Option[Long]           = lastBlockHeader.map(_.header.timestamp)
    def lastBlockIds(maxRollbackLength: Int): Seq[ByteStr] =
      (blockchain.height to blockchain.finalizedHeightOrFallback(maxRollbackLength).toInt by -1).flatMap(blockId)

    def effectiveBalance(address: Address, confirmations: Int, block: Option[BlockId] = blockchain.lastBlockId): Long = {
      val blockHeight = block.flatMap(b => blockchain.heightOf(b)).getOrElse(blockchain.height)
      val bottomLimit = (blockHeight - confirmations + 1).max(1).min(blockHeight)
      val balances    = blockchain.balanceSnapshots(address, bottomLimit, block)
      val isBanned    = blockchain.effectiveBalanceBanHeights(address).exists(h => h >= bottomLimit && h <= blockHeight)
      if (balances.isEmpty || isBanned) 0L else balances.view.map(_.effectiveBalance).min
    }

    def generatingBalance(account: Address, blockId: Option[BlockId] = None): Long =
      GeneratingBalanceProvider.balance(blockchain, account, blockId)

    def regularBalance(address: Address, atHeight: Int, confirmations: Int): Long = {
      val bottomLimit = (atHeight - confirmations + 1).max(1).min(atHeight)
      val blockId     = blockchain.blockHeader(atHeight).getOrElse(throw new IllegalArgumentException(s"Invalid block height: $atHeight")).id()
      val balances    = blockchain.balanceSnapshots(address, bottomLimit, Some(blockId))
      if (balances.isEmpty) 0L else balances.view.map(_.regularBalance).min
    }

    def unsafeHeightOf(id: ByteStr): Int =
      blockchain
        .heightOf(id)
        .getOrElse(throw new IllegalStateException(s"Can't find a block: $id"))

    def hearthPortfolio(address: Address): Portfolio = Portfolio(
      blockchain.balance(address),
      blockchain.leaseBalance(address),
      generationDeposit = blockchain.generationDeposit(address)
    )

    /** The VRF key a generator registered when it committed to generating for `at`'s period.
      *
      * A generator's VRF key is derived independently of its signing key, so it cannot be taken from a block header:
      * only the commitment says which VRF key that generator's blocks are verified against.
      */
    def vrfPublicKeyOf(generator: PublicKey, at: Height): Either[String, ByteStr] = {
      val generatorAddress = generator.toAddress
      for {
        period <- blockchain
          .generationPeriodOf(at)
          .toRight(s"No generation period at $at, so $generatorAddress has no registered VRF public key")
        committed <- blockchain
          .committedGenerators(period)
          .find(_.address == generatorAddress)
          .toRight(s"$generatorAddress is not a committed generator of period $period")
      } yield committed.vrfPublicKey
    }

    // TODO: lock?
    // TODO: not efficient? See RocksDBWriter.balanceSnapshots
    // TODO: optimize
    def generationDeposit(address: Address, at: Height = Height(blockchain.height)): Long = blockchain.generationPeriodOf(at).fold(0L) { period =>
      val committed = blockchain.committedGenerators(period)
      val conflict  = blockchain.conflictGenerators(period)
      val idxOnCurrent = committed.zipWithIndex
        .collectFirst { case (cg, i) if cg.address == address => GeneratorIndex(i) }
        .filterNot { idx => conflict.hasInUpTo(at.prev, idx) } // Prev, because punishment on next height

      val hasOnNext = blockchain.committedGenerators(period.next).exists(_.address == address)

      val committedTimes = idxOnCurrent.size + Numbers.when(hasOnNext)(1)
      committedTimes * CommitToGenerationTransaction.DepositInEmbers
    }

    def isGeneratingBalanceValid(height: Height, blockHeader: BlockHeader, balance: Long): Boolean =
      GeneratingBalanceProvider.isGeneratingBalanceValid(balance)

    def lastBlockReward: Option[Long] = blockchain.blockReward(blockchain.height)

    def vrf(atHeight: Int): Option[ByteStr] = blockchain.hitSource(atHeight)

    def isFeatureActivated(feature: BlockchainFeature, height: Int = blockchain.height): Boolean =
      blockchain.activatedFeatures.get(feature.id).exists(_ <= Height(height))

    def activatedFeaturesAt(height: Int): Set[Short] =
      blockchain.activatedFeatures.collect {
        case (featureId, activationHeight) if Height(height) >= activationHeight => featureId
      }.toSet

    def featureStatus(feature: Short, height: Int): BlockchainFeatureStatus =
      if (blockchain.activatedFeatures.get(feature).exists(_ <= Height(height))) BlockchainFeatureStatus.Activated
      else if (blockchain.approvedFeatures.get(feature).exists(_ <= Height(height))) BlockchainFeatureStatus.Approved
      else BlockchainFeatureStatus.Undefined

    def isConflict(height: Height, generator: Address): Boolean = {
      val maybeConflict = for {
        period <- blockchain.generationPeriodOf(height)
        idx <- GeneratorIndex.checked {
          blockchain.committedGenerators(period).indexWhere(_.address == generator)
        }
      } yield blockchain.conflictGenerators(period).hasInUpTo(height, idx)
      maybeConflict.getOrElse(false)
    }

    def featureActivationHeight(feature: BlockchainFeature): Option[Height] = featureActivationHeight(feature.id)
    def featureActivationHeight(feature: Short): Option[Height]             = blockchain.activatedFeatures.get(feature)
    def featureApprovalHeight(feature: Short): Option[Height]               = blockchain.approvedFeatures.get(feature)

    def transactionSucceeded(id: ByteStr): Boolean = blockchain.transactionMeta(id).exists(_.status == TxMeta.Status.Succeeded)

    def hasBannedEffectiveBalance(address: Address, height: Int = blockchain.height): Boolean =
      blockchain.effectiveBalanceBanHeights(address).contains(height)

    def supportsLightNodeBlockFields(height: Int = blockchain.height): Boolean = true

    // Block rewards are never boosted.
    def blockRewardBoost(height: Height): Int = 1

    /** @return None, if DeterministicFinality is not activated for provided height
      */
    def generationPeriodOf(h: Height): Option[GenerationPeriod] =
      Some(GenerationPeriod.from(h, blockchain.settings.functionalitySettings))

    def currentGenerationPeriod: Option[GenerationPeriod] = this.generationPeriodOf(Height(blockchain.height))

    def supportsFinalizationVoting(height: Int = blockchain.height): Boolean = true
  }

  def finalizedHeightOrFallback(at: Height, latestFinalized: Option[Height], maxRollbackLength: Int): Height = {
    val minFallbackHeight = at - maxRollbackLength
    latestFinalized.getOrElse(GenesisBlockHeight).max(minFallbackHeight) // Compare with fallback in the end
  }
}
