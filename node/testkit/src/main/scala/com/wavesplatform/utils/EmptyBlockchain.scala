package com.wavesplatform.utils

import com.typesafe.config.ConfigFactory
import com.wavesplatform.account.Address
import com.wavesplatform.block.SignedBlockHeader
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.crypto.bls.BlsPublicKey
import com.wavesplatform.settings.BlockchainSettings
import com.wavesplatform.state.*
import com.wavesplatform.state.TxMeta.Status
import com.wavesplatform.transaction.Asset.{IssuedAsset, Waves}
import com.wavesplatform.transaction.{Asset, Transaction}

trait EmptyBlockchain extends Blockchain {
  override lazy val settings: BlockchainSettings = BlockchainSettings.fromRootConfig(ConfigFactory.load())

  override def height: Int = GenesisBlockHeight.toInt

  override def finalizedHeight: Option[Height] = None

  override def finalizedHeightAt(at: Height): Option[Height] = None

  override def score: BigInt = 0

  override def blockHeader(height: Int): Option[SignedBlockHeader] = None

  override def hitSource(height: Int): Option[ByteStr] = None

  override def carryFee(refId: ByteStr): Either[String, BlockFee] = Right(BlockFee.empty)

  override def heightOf(blockId: ByteStr): Option[Int] = None

  /** Features related */
  override def approvedFeatures: Map[Short, Height] = Map.empty

  override def activatedFeatures: Map[Short, Height] = Map.empty

  override def featureVotes(height: Height): Map[Short, Int] = Map.empty

  /** Block reward related */
  override def blockReward(height: Int): Option[Long] = None

  override def wavesAmount(height: Int): BigInt = 0

  override def transactionInfo(id: ByteStr): Option[(TxMeta, Transaction)] = None

  override def transactionInfos(ids: Seq[ByteStr]): Seq[Option[(TxMeta, Transaction)]] = Seq.empty

  override def transactionMeta(id: ByteStr): Option[TxMeta] = None

  override def transactionSnapshot(id: ByteStr): Option[(StateSnapshot, Status)] = None

  override def containsTransaction(tx: Transaction): Boolean = false

  override def assetDescription(id: IssuedAsset): Option[AssetDescription] = None

  override def leaseDetails(leaseId: ByteStr): Option[LeaseDetails] = None

  override def filledVolumeAndFee(orderId: ByteStr): VolumeAndFee = VolumeAndFee(0, 0)

  /** Retrieves Waves balance snapshot in the [from, to] range (inclusive) */
  override def balanceAtHeight(address: Address, height: Int, assetId: Asset = Waves): Option[(Int, Long)] = Option.empty
  override def balanceSnapshots(address: Address, from: Int, to: Option[ByteStr]): Seq[BalanceSnapshot]    = Seq.empty

  override def balance(address: Address, mayBeAssetId: Asset): Long = 0

  override def balances(req: Seq[(Address, Asset)]): Map[(Address, Asset), Long] = Map.empty

  override def wavesBalances(addresses: Seq[Address]): Map[Address, Long] = Map.empty

  override def effectiveBalanceBanHeights(address: Address): Seq[Int] = Seq.empty

  override def leaseBalance(address: Address): LeaseBalance = LeaseBalance.empty

  override def leaseBalances(addresses: Seq[Address]): Map[Address, LeaseBalance] = Map.empty

  override def lastStateHash(refId: Option[ByteStr]): ByteStr = TxStateSnapshotHashBuilder.InitStateHash

  override def committedGenerators(at: GenerationPeriod): IndexedSeq[CommittedGenerator] = IndexedSeq.empty

  override def conflictGenerators(at: GenerationPeriod): ConflictGenerators = ConflictGenerators.empty
}

object EmptyBlockchain extends EmptyBlockchain
