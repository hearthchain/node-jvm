package tech.hearth.state

import cats.syntax.option.*
import tech.hearth.account.{Address, PublicKey}
import tech.hearth.block.Block.BlockId
import tech.hearth.block.{Block, SignedBlockHeader}
import tech.hearth.common.state.ByteStr
import tech.hearth.settings.BlockchainSettings
import tech.hearth.state.TxMeta.Status
import tech.hearth.transaction.Asset.{IssuedAsset, Waves}
import tech.hearth.transaction.{Asset, CommitToGenerationTransaction, Transaction}

case class SnapshotBlockchain(
    inner: Blockchain,
    maybeSnapshot: Option[StateSnapshot] = None,
    blockMeta: Option[(SignedBlockHeader, ByteStr)] = None,
    carry: BlockFee = BlockFee.empty,
    reward: Option[Long] = None,
    stateHash: Option[ByteStr] = None,
    latestGeneratorSet: Option[GeneratorSet] = None
) extends Blockchain {
  override val settings: BlockchainSettings = inner.settings
  lazy val snapshot: StateSnapshot          = maybeSnapshot.orEmpty

  override def balance(address: Address, assetId: Asset): Long =
    snapshot.balances.getOrElse((address, assetId), inner.balance(address, assetId))

  override def balances(req: Seq[(Address, Asset)]): Map[(Address, Asset), Long] = {
    val (innerBalances, snapshotBalances) = req
      .foldLeft((Seq[(Address, Asset)](), Map[(Address, Asset), Long]())) { case ((innerBalances, snapshotBalances), key) =>
        snapshot.balances
          .get(key)
          .fold(
            (innerBalances :+ key, snapshotBalances)
          )(balance => (innerBalances, snapshotBalances + (key -> balance)))
      }
    inner.balances(innerBalances) ++ snapshotBalances
  }

  override def wavesBalances(addresses: Seq[Address]): Map[Address, Long] = {
    val (innerBalances, snapshotBalances) = addresses
      .foldLeft((Seq[Address](), Map[Address, Long]())) { case ((innerBalances, snapshotBalances), address) =>
        snapshot.balances
          .get((address, Waves))
          .fold(
            (innerBalances :+ address, snapshotBalances)
          )(balance => (innerBalances, snapshotBalances + (address -> balance)))
      }
    inner.wavesBalances(innerBalances) ++ snapshotBalances
  }

  override def effectiveBalanceBanHeights(address: Address): Seq[Int] = {
    val maybeLastBlockBan = blockMeta.flatMap(_._1.header.challengedHeader).map(_.generator.toAddress) match {
      case Some(generator) if address == generator => Seq(height)
      case _                                       => Seq.empty
    }
    maybeLastBlockBan ++ inner.effectiveBalanceBanHeights(address)
  }

  override def leaseBalance(address: Address): LeaseBalance =
    snapshot.leaseBalances.getOrElse(address, inner.leaseBalance(address))

  override def leaseBalances(addresses: Seq[Address]): Map[Address, LeaseBalance] = {
    val (innerBalances, snapshotBalances) = addresses
      .foldLeft((Seq[Address](), Map[Address, LeaseBalance]())) { case ((innerBalances, snapshotBalances), address) =>
        snapshot.leaseBalances
          .get(address)
          .fold(
            (innerBalances :+ address, snapshotBalances)
          )(balance => (innerBalances, snapshotBalances + (address -> balance)))
      }
    inner.leaseBalances(innerBalances) ++ snapshotBalances
  }

  override def assetDescription(asset: IssuedAsset): Option[AssetDescription] =
    SnapshotBlockchain.assetDescription(asset, snapshot, height, inner)

  override def leaseDetails(leaseId: ByteStr): Option[LeaseDetails] = {
    val newer = snapshot.newLeases.get(leaseId).map(n => LeaseDetails(n, LeaseDetails.Status.Active)).orElse(inner.leaseDetails(leaseId))
    snapshot.cancelledLeases.get(leaseId) match {
      case Some(newStatus) => newer.map(_.copy(status = newStatus))
      case None            => newer
    }
  }

  override def transactionInfo(id: ByteStr): Option[(TxMeta, Transaction)] =
    snapshot.transactions
      .get(id)
      .map(t => (TxMeta(Height(this.height), t.status, t.spentComplexity), t.transaction))
      .orElse(inner.transactionInfo(id))

  override def transactionInfos(ids: Seq[ByteStr]): Seq[Option[(TxMeta, Transaction)]] = {
    inner.transactionInfos(ids).zip(ids).map { case (info, id) =>
      snapshot.transactions
        .get(id)
        .map(t => (TxMeta(Height(this.height), t.status, t.spentComplexity), t.transaction))
        .orElse(info)
    }
  }

  override def transactionMeta(id: ByteStr): Option[TxMeta] =
    snapshot.transactions
      .get(id)
      .map(t => TxMeta(Height(this.height), t.status, t.spentComplexity))
      .orElse(inner.transactionMeta(id))

  override def transactionSnapshot(id: ByteStr): Option[(StateSnapshot, Status)] =
    snapshot.transactions
      .get(id)
      .map(tx => (tx.snapshot, tx.status))
      .orElse(inner.transactionSnapshot(id))

  override def height: Int = inner.height + blockMeta.size

  override def finalizedHeight: Option[Height] = inner.finalizedHeight

  override def finalizedHeightAt(at: Height): Option[Height] = inner.finalizedHeightAt(at)

  override def containsTransaction(tx: Transaction): Boolean =
    snapshot.transactions.contains(tx.id()) || inner.containsTransaction(tx)

  override def filledVolumeAndFee(orderId: ByteStr): VolumeAndFee =
    snapshot.orderFills.getOrElse(orderId, inner.filledVolumeAndFee(orderId))

  override def balanceAtHeight(address: Address, h: Int, assetId: Asset = Waves): Option[(Int, Long)] =
    if (maybeSnapshot.forall(!_.balances.contains(address -> assetId)) || h < this.height) {
      inner.balanceAtHeight(address, h, assetId)
    } else {
      val balance = this.balance(address, assetId)
      val bs      = height -> balance
      Some(bs)
    }

  override def balanceSnapshots(address: Address, from: Int, to: Option[BlockId]): Seq[BalanceSnapshot] = {
    val from1 = math.max(from, 1)

    if (maybeSnapshot.isEmpty || to.exists(id => inner.heightOf(id).isDefined)) {
      inner.balanceSnapshots(address, from1, to)
    } else {
      val h       = Height(height)
      val balance = this.balance(address)
      val lease   = this.leaseBalance(address)
      val deposit = this.generationDeposit(address, h)

      val bs = BalanceSnapshot(h, Portfolio(balance, lease, generationDeposit = deposit))
      // `from == h - 1` yields the liquid snapshot alone: the inner blockchain is only consulted from `h - 2` down.
      // Height 2 is the one exception, so that a generating balance at that height accounts for the genesis snapshot -
      // it used to be gated on RideV6 and applies unconditionally now.
      val height2Fix = h.toInt == 2 && from1 < 2
      if (inner.height > 0 && (from1 < h.toInt - 1 || height2Fix))
        bs +: inner.balanceSnapshots(address, from1, to)
      else
        Seq(bs)
    }
  }

  override def carryFee(refId: ByteStr): Either[String, BlockFee] =
    if (blockMeta.exists(_._1.id() == refId)) Right(carry) else inner.carryFee(refId)

  override def score: BigInt = blockMeta.fold(BigInt(0))(_._1.header.score()) + inner.score

  override def blockHeader(height: Int): Option[SignedBlockHeader] =
    blockMeta match {
      case Some((header, _)) if this.height == height => Some(header)
      case _                                          => inner.blockHeader(height)
    }

  override def heightOf(blockId: ByteStr): Option[Int] = blockMeta.filter(_._1.id() == blockId).map(_ => height) orElse inner.heightOf(blockId)

  /** Features related */
  override def approvedFeatures: Map[Short, Height] = inner.approvedFeatures

  override def activatedFeatures: Map[Short, Height] = inner.activatedFeatures

  override def featureVotes(height: Height): Map[Short, Int] = inner.featureVotes(height)

  /** Block reward related */
  override def blockReward(height: Int): Option[Long] = reward.filter(_ => this.height == height) orElse inner.blockReward(height)

  override def wavesAmount(height: Int): BigInt = {
    val parentBlockHeader = blockMeta match {
      case None => inner.blockHeader(height - 1)
      case _    => inner.lastBlockHeader
    }

    val parentConflictEndorsements = for {
      parentBlockHeader <- parentBlockHeader
      voting            <- parentBlockHeader.header.finalizationVoting
    } yield voting.conflict.size

    inner.wavesAmount(height) +
      BigInt(reward.getOrElse(0L)) -
      parentConflictEndorsements.getOrElse(0) * CommitToGenerationTransaction.DepositInWavelets
  }

  override def hitSource(height: Int): Option[ByteStr] =
    blockMeta
      .collect { case (_, hitSource) if this.height == height => hitSource }
      .orElse(inner.hitSource(height))

  override def lastStateHash(refId: Option[ByteStr]): BlockId =
    stateHash.orElse(blockMeta.flatMap(_._1.header.stateHash)).getOrElse(inner.lastStateHash(refId))

  override def committedGenerators(at: GenerationPeriod): IndexedSeq[CommittedGenerator] = {
    val base = inner.committedGenerators(at)
    // Generators committed in the genesis block generate from the very first period, everyone else from the next one.
    // Mirrors how Caches.append persists them once the block is no longer liquid.
    val committedHere =
      if (Height(this.height) == GenesisBlockHeight) this.currentGenerationPeriod.contains(at)
      else this.currentGenerationPeriod.exists(_.next == at)
    if (committedHere) base ++ snapshot.nextCommittedGenerators.map(_.toCommittedGenerator) else base
  }

  override def conflictGenerators(at: GenerationPeriod): ConflictGenerators = {
    lazy val base = inner.conflictGenerators(at)
    this.currentGenerationPeriod.fold(ConflictGenerators.empty) { currPeriod =>
      if (at < currPeriod) base
      else if (at > currPeriod) ConflictGenerators.empty
      else {
        val extraConflictIndexes = for {
          (blockMeta, _) <- blockMeta.toSeq
          v              <- blockMeta.header.finalizationVoting.toSeq
          c              <- v.conflict
        } yield c.endorserIndex

        base.appendAll(Height(height), extraConflictIndexes*)
      }
    }
  }
}

object SnapshotBlockchain {
  def apply(inner: Blockchain, ngState: NgState): SnapshotBlockchain =
    new SnapshotBlockchain(
      inner,
      Some(ngState.bestLiquidSnapshot),
      Some(SignedBlockHeader(ngState.bestLiquidBlock.header, ngState.bestLiquidBlock.signature) -> ngState.hitSource),
      ngState.carryFee,
      ngState.reward,
      Some(ngState.bestLiquidComputedStateHash),
      Some(ngState.finalizationState.generatorSet)
    )

  // no new block on top of `inner`, so there's no carry of its own: carryFee always falls through to `inner`
  def apply(inner: Blockchain, reward: Option[Long]): SnapshotBlockchain =
    new SnapshotBlockchain(inner, reward = reward)

  def apply(inner: Blockchain, snapshot: StateSnapshot): SnapshotBlockchain =
    new SnapshotBlockchain(inner, Some(snapshot))

  def apply(
      inner: Blockchain,
      snapshot: StateSnapshot,
      newBlock: Block,
      hitSource: ByteStr,
      carry: BlockFee,
      reward: Option[Long],
      stateHash: Option[ByteStr]
  ): SnapshotBlockchain =
    new SnapshotBlockchain(inner, Some(snapshot), Some(SignedBlockHeader(newBlock.header, newBlock.signature) -> hitSource), carry, reward, stateHash)

  private def assetDescription(
      asset: IssuedAsset,
      snapshot: StateSnapshot,
      height: Int,
      inner: Blockchain
  ): Option[AssetDescription] = {
    lazy val volume = snapshot.assetVolumes.get(asset)
    lazy val minFee = snapshot.minAssetFees.get(asset)
    snapshot.assetStatics
      .get(asset)
      .map { case (static, assetNum) =>
        AssetDescription(
          static.name,
          static.description,
          static.decimals,
          volume.get,
          assetNum,
          Height(height),
          minFee.get
        )
      }
      .orElse(
        inner
          .assetDescription(asset)
          .map(d => minFee.fold(d)(mf => d.copy(minAssetFee = mf)))
      )
  }
}
