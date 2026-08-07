package tech.hearth.events

import cats.Monoid
import cats.implicits.catsSyntaxSemigroup
import com.google.protobuf.ByteString
import tech.hearth.account.{Address, PublicKey}
import tech.hearth.block.{Block, MicroBlock}
import tech.hearth.common.state.ByteStr
import tech.hearth.events.StateUpdate.LeaseUpdate.LeaseStatus
import tech.hearth.events.StateUpdate.{AssetStateUpdate, BalanceUpdate, LeaseUpdate, LeasingBalanceUpdate}
import tech.hearth.events.protobuf.TransactionMetadata
import tech.hearth.protobuf.*
import tech.hearth.protobuf.transaction.PBAmounts
import tech.hearth.state.*
import tech.hearth.transaction.Asset.IssuedAsset
import tech.hearth.transaction.assets.exchange.ExchangeTransaction
import tech.hearth.transaction.lease.LeaseTransaction
import tech.hearth.transaction.transfer.{MassTransferTransaction, TransferTransaction}
import tech.hearth.transaction.{Asset, Authorized}

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

final case class StateUpdate(
    balances: Seq[BalanceUpdate],
    leasingForAddress: Seq[LeasingBalanceUpdate],
    assets: Seq[AssetStateUpdate],
    leases: Seq[LeaseUpdate]
) {
  def isEmpty: Boolean = balances.isEmpty && leases.isEmpty && assets.isEmpty

  def reverse: StateUpdate = copy(
    balances.map(_.reverse).reverse,
    leasingForAddress.map(_.reverse).reverse,
    assets.map(_.reverse).reverse,
    leases.map(_.reverse).reverse
  )
}

object StateUpdate {
  case class BalanceUpdate(address: Address, asset: Asset, before: Long, after: Long) {
    def reverse: BalanceUpdate = copy(before = after, after = before)
  }

  object BalanceUpdate {
    import tech.hearth.events.protobuf.StateUpdate.BalanceUpdate as PBBalanceUpdate

    def fromPB(v: PBBalanceUpdate): BalanceUpdate = {
      val (asset, after) = PBAmounts.toAssetAndAmount(v.getAmountAfter)
      val before         = v.amountBefore
      BalanceUpdate(v.address.toAddress, asset, before, after)
    }

    def toPB(v: BalanceUpdate): PBBalanceUpdate = {
      val afterAmount = PBAmounts.fromAssetAndAmount(v.asset, v.after)
      PBBalanceUpdate(v.address.toByteString, Some(afterAmount), v.before)
    }
  }

  case class LeasingBalanceUpdate(address: Address, before: LeaseBalance, after: LeaseBalance) {
    def reverse: LeasingBalanceUpdate = copy(before = after, after = before)
  }

  object LeasingBalanceUpdate {
    import tech.hearth.events.protobuf.StateUpdate.LeasingUpdate as PBLeasingUpdate

    def fromPB(v: PBLeasingUpdate): LeasingBalanceUpdate = {
      LeasingBalanceUpdate(
        v.address.toAddress,
        LeaseBalance(v.inBefore, v.outBefore),
        LeaseBalance(v.inAfter, v.outAfter)
      )
    }

    def toPB(v: LeasingBalanceUpdate): PBLeasingUpdate = {
      PBLeasingUpdate(
        v.address.toByteString,
        v.after.in,
        v.after.out,
        v.before.in,
        v.before.out
      )
    }
  }

  case class LeaseUpdate(
      leaseId: ByteStr,
      statusAfter: LeaseUpdate.LeaseStatus,
      amount: Long,
      sender: PublicKey,
      recipient: Address,
      originTransactionId: ByteStr
  ) {
    def reverse: LeaseUpdate =
      copy(statusAfter = statusAfter match {
        case LeaseStatus.Active   => LeaseStatus.Inactive
        case LeaseStatus.Inactive => LeaseStatus.Active
      })
  }

  object LeaseUpdate {
    sealed trait LeaseStatus
    object LeaseStatus {
      case object Active   extends LeaseStatus
      case object Inactive extends LeaseStatus
    }

    import tech.hearth.events.protobuf.StateUpdate.LeaseUpdate as PBLeaseUpdate
    import tech.hearth.events.protobuf.StateUpdate.LeaseUpdate.LeaseStatus as PBLeaseStatus

    def fromPB(v: PBLeaseUpdate): LeaseUpdate = {
      LeaseUpdate(
        v.leaseId.toByteStr,
        v.statusAfter match {
          case PBLeaseStatus.ACTIVE   => LeaseStatus.Active
          case PBLeaseStatus.INACTIVE => LeaseStatus.Inactive
          case _                      => ???
        },
        v.amount,
        v.sender.toPublicKey,
        v.recipient.toAddress,
        v.originTransactionId.toByteStr
      )
    }

    def toPB(v: LeaseUpdate): PBLeaseUpdate = {
      PBLeaseUpdate(
        v.leaseId.toByteString,
        v.statusAfter match {
          case LeaseStatus.Active   => PBLeaseStatus.ACTIVE
          case LeaseStatus.Inactive => PBLeaseStatus.INACTIVE
        },
        v.amount,
        v.sender.toByteString,
        v.recipient.toByteString,
        v.originTransactionId.toByteString
      )
    }
  }

  final case class AssetStateUpdate(
      assetId: ByteStr,
      before: Option[AssetDescription],
      after: Option[AssetDescription]
  ) {
    require(before.isDefined || after.isDefined)
    def reverse: AssetStateUpdate = copy(before = after, after = before)
  }

  object AssetStateUpdate {
    import tech.hearth.events.protobuf.StateUpdate.{AssetDetails as PBAssetDetails, AssetStateUpdate as PBAssetStateUpdate}

    def fromPB(self: PBAssetStateUpdate): AssetStateUpdate = {

      // script_info/issuer/reissuable/nft/last_updated are wire-compat only: AssetDescription no longer
      // carries scripted-asset, issuer, reissuable, nft or lastUpdatedAt data, so those fields are read here
      // for nobody and dropped. sponsorship is repurposed to carry minAssetFee, the one mandatory per-asset
      // fee floor left once sponsorship itself was removed.
      def detailsFromPB(v: PBAssetDetails): AssetDescription = {
        AssetDescription(
          ByteString.copyFromUtf8(v.name),
          ByteString.copyFromUtf8(v.description),
          v.decimals,
          BigInt(v.safeVolume.toByteArray),
          v.sequenceInBlock,
          Height(v.issueHeight),
          MinAssetFee.unsafeFrom(v.sponsorship)
        )
      }

      AssetStateUpdate(
        self.before.orElse(self.after).fold(ByteStr.empty)(_.assetId.toByteStr),
        self.before.map(detailsFromPB),
        self.after.map(detailsFromPB)
      )
    }

    def toPB(self: AssetStateUpdate): PBAssetStateUpdate = {
      def detailsToPB(v: AssetDescription): PBAssetDetails = {
        PBAssetDetails(
          assetId = self.assetId.toByteString,
          issuer = ByteString.EMPTY,
          decimals = v.decimals,
          name = v.name.toStringUtf8,
          description = v.description.toStringUtf8,
          reissuable = false,
          volume = v.totalVolume.longValue,
          nft = false,
          safeVolume = ByteString.copyFrom(v.totalVolume.toByteArray),
          lastUpdated = v.issueHeight.toInt,
          sequenceInBlock = v.sequenceInBlock,
          issueHeight = v.issueHeight.toInt,
          sponsorship = v.minAssetFee.value
        )
      }

      PBAssetStateUpdate(
        self.before.map(detailsToPB),
        self.after.map(detailsToPB)
      )
    }
  }

  final case class AssetInfo(id: ByteStr, decimals: Int, name: String)
  object AssetInfo {
    import tech.hearth.events.protobuf.StateUpdate.AssetInfo as PBAssetInfo

    def toPB(ai: AssetInfo): PBAssetInfo = PBAssetInfo(
      ai.id.toByteString,
      ai.decimals,
      ai.name
    )

    def fromPB(ai: PBAssetInfo): AssetInfo =
      AssetInfo(
        ai.id.toByteStr,
        ai.decimals,
        ai.name
      )
  }

  import tech.hearth.events.protobuf.StateUpdate as PBStateUpdate

  def fromPB(v: PBStateUpdate): StateUpdate = {
    StateUpdate(
      v.balances.map(BalanceUpdate.fromPB),
      v.leasingForAddress.map(LeasingBalanceUpdate.fromPB),
      v.assets.map(AssetStateUpdate.fromPB),
      v.individualLeases.map(LeaseUpdate.fromPB)
    )
  }

  def toPB(v: StateUpdate): PBStateUpdate = {
    PBStateUpdate(
      v.balances.map(BalanceUpdate.toPB),
      v.leasingForAddress.map(LeasingBalanceUpdate.toPB),
      v.assets.map(AssetStateUpdate.toPB),
      v.leases.map(LeaseUpdate.toPB)
    )
  }

  implicit val monoid: Monoid[StateUpdate] = new Monoid[StateUpdate] {
    override def empty: StateUpdate = StateUpdate(Seq.empty, Seq.empty, Seq.empty, Seq.empty)

    override def combine(x: StateUpdate, y: StateUpdate): StateUpdate = {
      // merge balance updates, preserving order
      val balancesMap = mutable.LinkedHashMap.empty[(Address, Asset), BalanceUpdate]
      (x.balances ++ y.balances).foreach { case balance @ BalanceUpdate(addr, asset, _, _) =>
        balancesMap(addr -> asset) = balancesMap.get(addr -> asset) match {
          case Some(value) => balance.copy(before = value.before)
          case None        => balance
        }
      }
      // merge leases, preserving order
      val addrLeasesMap = mutable.LinkedHashMap.empty[Address, LeasingBalanceUpdate]
      (x.leasingForAddress ++ y.leasingForAddress).foreach { case balance @ LeasingBalanceUpdate(addr, _, _) =>
        addrLeasesMap(addr) = addrLeasesMap.get(addr) match {
          case Some(prevLease) =>
            balance.copy(before = prevLease.before)
          case None =>
            balance
        }
      }
      // merge asset state updates, preserving order
      val assetsMap = mutable.LinkedHashMap.empty[ByteStr, AssetStateUpdate]
      (x.assets ++ y.assets).foreach { assetUpdate =>
        assetsMap(assetUpdate.assetId) = assetsMap.get(assetUpdate.assetId) match {
          case Some(prevUpdate) => assetUpdate.copy(before = prevUpdate.before)
          case None             => assetUpdate
        }
      }

      val leasesMap = mutable.LinkedHashMap.empty[ByteStr, LeaseUpdate]
      (x.leases ++ y.leases).foreach { lease =>
        leasesMap(lease.originTransactionId) = lease
      }

      StateUpdate(
        balances = balancesMap.values.toList,
        leasingForAddress = addrLeasesMap.values.toList,
        assets = assetsMap.values.toList,
        leases = leasesMap.values.toList
      )
    }
  }

  def atomic(blockchainBeforeWithMinerReward: Blockchain, snapshot: StateSnapshot): StateUpdate = {
    val blockchain      = blockchainBeforeWithMinerReward
    val blockchainAfter = SnapshotBlockchain(blockchain, snapshot)

    val balances = ArrayBuffer.empty[BalanceUpdate]
    for {
      ((address, asset), after) <- snapshot.balances
      before = blockchain.balance(address, asset) if before != after
    } balances += BalanceUpdate(address, asset, before, after)

    val leaseBalanceUpdates = snapshot.leaseBalances
      .map { case (address, after) =>
        val before = blockchain.leaseBalance(address)
        LeasingBalanceUpdate(address, before, after)
      }
      .filterNot(b => b.before == b.after)
      .toVector

    val assets: Seq[AssetStateUpdate] = for {
      asset <- (
        snapshot.assetStatics.keySet ++
          snapshot.assetVolumes.keySet ++
          snapshot.minAssetFees.keySet
      ).toSeq
      assetBefore = blockchainBeforeWithMinerReward.assetDescription(asset)
      assetAfter  = blockchainAfter.assetDescription(asset)
    } yield AssetStateUpdate(asset.id, assetBefore, assetAfter)

    val newLeaseUpdates = snapshot.newLeases.collect {
      case (newId, staticInfo) if !snapshot.cancelledLeases.contains(newId) =>
        LeaseUpdate(
          newId,
          LeaseStatus.Active,
          staticInfo.amount.value,
          staticInfo.sender,
          staticInfo.recipientAddress,
          staticInfo.sourceId.byteStr
        )
    }

    val cancelledLeaseUpdates = snapshot.cancelledLeases.map { case (id, _) =>
      val si = snapshot.newLeases.get(id).orElse(blockchain.leaseDetails(id).map(_.static))
      LeaseUpdate(
        id,
        LeaseStatus.Inactive,
        si.fold(0L)(_.amount.value),
        si.fold(PublicKey(new Array[Byte](32)))(_.sender),
        si.fold(PublicKey(new Array[Byte](32)).toAddress)(_.recipientAddress),
        si.fold(ByteStr.empty)(_.sourceId.byteStr)
      )
    }

    val updatedLeases = newLeaseUpdates ++ cancelledLeaseUpdates

    StateUpdate(balances.toVector, leaseBalanceUpdates, assets, updatedLeases.toSeq)
  }

  private def transactionsMetadata(snapshot: StateSnapshot): Seq[TransactionMetadata] =
    snapshot.transactions.map { case (_, tx) =>
      TransactionMetadata(
        tx.transaction match {
          case a: Authorized => a.sender.toAddress.toByteString
          case _             => ByteString.EMPTY
        },
        tx.transaction match {
          case tt: TransferTransaction =>
            TransactionMetadata.Metadata.Transfer(TransactionMetadata.TransferMetadata(tt.recipient.toByteString))

          case mtt: MassTransferTransaction =>
            TransactionMetadata.Metadata.MassTransfer(TransactionMetadata.MassTransferMetadata(mtt.transfers.map(_.address.toByteString)))

          case lt: LeaseTransaction =>
            TransactionMetadata.Metadata.Lease(TransactionMetadata.LeaseMetadata(lt.recipient.toByteString))

          case ext: ExchangeTransaction =>
            TransactionMetadata.Metadata.Exchange(
              TransactionMetadata.ExchangeMetadata(
                Seq(ext.order1, ext.order2).map(_.id().toByteString),
                Seq(ext.order1, ext.order2).map(_.senderAddress.toByteString),
                Seq(ext.order1, ext.order2).map(_.senderPublicKey.toByteString)
              )
            )

          case _ =>
            TransactionMetadata.Metadata.Empty
        }
      )
    }.toSeq

  def referencedAssets(blockchain: Blockchain, txsStateUpdates: Seq[StateUpdate]): Seq[AssetInfo] =
    txsStateUpdates
      .flatMap(st => st.assets.map(_.assetId) ++ st.balances.flatMap(_.asset.compatId))
      .distinct
      .flatMap(id => blockchain.assetDescription(IssuedAsset(id)).map(ad => AssetInfo(id, ad.decimals, ad.name.toStringUtf8)))

  def container(
      blockchainBeforeWithReward: Blockchain,
      keyBlockSnapshot: StateSnapshot
  ): (StateUpdate, Seq[StateUpdate], Seq[TransactionMetadata], Seq[AssetInfo]) = {
    val (totalSnapshot, txsStateUpdates) =
      keyBlockSnapshot.transactions
        .foldLeft((keyBlockSnapshot, Seq.empty[StateUpdate])) { case ((accSnapshot, updates), (_, txInfo)) =>
          val accBlockchain = SnapshotBlockchain(blockchainBeforeWithReward, accSnapshot)
          (
            accSnapshot |+| txInfo.snapshot,
            updates :+ atomic(accBlockchain, txInfo.snapshot)
          )
        }
    val blockchainAfter = SnapshotBlockchain(blockchainBeforeWithReward, totalSnapshot)
    val metadata        = transactionsMetadata(totalSnapshot)
    val refAssets       = referencedAssets(blockchainAfter, txsStateUpdates)
    val keyBlockUpdate  = atomic(blockchainBeforeWithReward, keyBlockSnapshot)
    (keyBlockUpdate, txsStateUpdates, metadata, refAssets)
  }
}

sealed trait BlockchainUpdated {
  def id: ByteStr
  def height: Int
}

object BlockchainUpdated {
  implicit class BlockchainUpdatedExt(private val bu: BlockchainUpdated) extends AnyVal {
    def references(other: BlockchainUpdated): Boolean = bu match {
      case b: BlockAppended       => b.block.header.reference == other.id
      case mb: MicroBlockAppended => mb.microBlock.reference == other.id
      case _                      => false
    }

    def ref: String = {
      val eventType = bu match {
        case _: BlockAppended               => "block"
        case _: MicroBlockAppended          => "micro"
        case _: RollbackCompleted           => "rollback"
        case _: MicroBlockRollbackCompleted => "micro_rollback"
      }
      s"$eventType/${bu.height}/${bu.id}"
    }
  }
}

final case class BlockAppended(
    id: ByteStr,
    height: Int,
    block: Block,
    updatedWavesAmount: Long,
    vrf: Option[ByteStr],
    activatedFeatures: Seq[Int],
    rewardShares: Seq[(Address, Long)],
    blockStateUpdate: StateUpdate,
    transactionStateUpdates: Seq[StateUpdate],
    transactionMetadata: Seq[TransactionMetadata],
    referencedAssets: Seq[StateUpdate.AssetInfo]
) extends BlockchainUpdated {
  def reverseStateUpdate: StateUpdate =
    Monoid.combineAll((blockStateUpdate +: transactionStateUpdates).map(_.reverse).reverse)
}

object BlockAppended {
  def from(
      block: Block,
      snapshot: StateSnapshot,
      blockchainBeforeWithReward: Blockchain,
      reward: Option[Long],
      hitSource: ByteStr
  ): BlockAppended = {
    val height = blockchainBeforeWithReward.height
    val (blockStateUpdate, txsStateUpdates, txsMetadata, refAssets) =
      StateUpdate.container(blockchainBeforeWithReward, snapshot)

    // updatedWavesAmount can change as a result of either genesis transactions or miner rewards
    val wavesAmount = blockchainBeforeWithReward.wavesAmount(height).toLong
    val updatedWavesAmount =
      wavesAmount + reward.filter(_ => height > 0).getOrElse(0L) * blockchainBeforeWithReward.blockRewardBoost(Height(height + 1))
    val activatedFeatures = blockchainBeforeWithReward.activatedFeatures.collect {
      case (id, activationHeight) if activationHeight == Height(height + 1) => id.toInt
    }.toSeq

    val rewardShares =
      BlockRewardCalculator.getSortedBlockRewardShares(height + 1, reward.getOrElse(0L), block.header.generator.toAddress, blockchainBeforeWithReward)

    BlockAppended(
      block.id(),
      height + 1,
      block,
      updatedWavesAmount,
      Some(hitSource),
      activatedFeatures,
      rewardShares,
      blockStateUpdate,
      txsStateUpdates,
      txsMetadata,
      refAssets
    )
  }

}

final case class MicroBlockAppended(
    id: ByteStr,
    height: Int,
    microBlock: MicroBlock,
    microBlockStateUpdate: StateUpdate,
    transactionStateUpdates: Seq[StateUpdate],
    transactionMetadata: Seq[TransactionMetadata],
    totalTransactionsRoot: ByteStr,
    referencedAssets: Seq[StateUpdate.AssetInfo]
) extends BlockchainUpdated {
  def reverseStateUpdate: StateUpdate =
    Monoid.combineAll((microBlockStateUpdate +: transactionStateUpdates).map(_.reverse).reverse)
}

object MicroBlockAppended {
  def from(
      microBlock: MicroBlock,
      snapshot: StateSnapshot,
      blockchainBeforeWithReward: Blockchain,
      totalBlockId: ByteStr,
      totalTransactionsRoot: ByteStr
  ): MicroBlockAppended = {
    val (microBlockStateUpdate, txsStateUpdates, txsMetadata, refAssets) =
      StateUpdate.container(blockchainBeforeWithReward, snapshot)

    MicroBlockAppended(
      totalBlockId,
      blockchainBeforeWithReward.height,
      microBlock,
      microBlockStateUpdate,
      txsStateUpdates,
      txsMetadata,
      totalTransactionsRoot,
      refAssets
    )
  }
}

final case class RollbackResult(
    removedBlocks: Seq[Block],
    removedTransactionIds: Seq[ByteStr],
    stateUpdate: StateUpdate,
    deactivatedFeatures: Seq[Int]
)

object RollbackResult {
  def micro(removedTransactionIds: Seq[ByteStr], stateUpdate: StateUpdate): RollbackResult =
    RollbackResult(Nil, removedTransactionIds, stateUpdate, Nil)

  implicit val monoid: Monoid[RollbackResult] = new Monoid[RollbackResult] {
    override def empty: RollbackResult = RollbackResult(Nil, Nil, Monoid.empty[StateUpdate], Nil)

    override def combine(x: RollbackResult, y: RollbackResult): RollbackResult = {
      RollbackResult(
        x.removedBlocks ++ y.removedBlocks,
        x.removedTransactionIds ++ y.removedTransactionIds,
        Monoid.combine(x.stateUpdate, y.stateUpdate),
        x.deactivatedFeatures ++ y.deactivatedFeatures
      )
    }
  }
}
final case class RollbackCompleted(id: ByteStr, height: Int, rollbackResult: RollbackResult, referencedAssets: Seq[StateUpdate.AssetInfo])
    extends BlockchainUpdated
final case class MicroBlockRollbackCompleted(id: ByteStr, height: Int, rollbackResult: RollbackResult, referencedAssets: Seq[StateUpdate.AssetInfo])
    extends BlockchainUpdated
