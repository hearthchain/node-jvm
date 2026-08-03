package tech.hearth.protobuf

import com.google.protobuf.ByteString
import tech.hearth.account.{Address, PublicKey}
import tech.hearth.common.state.ByteStr
import tech.hearth.common.utils.EitherExt2.*
import tech.hearth.crypto.bls.BlsPublicKey
import tech.hearth.protobuf.snapshot.TransactionStateSnapshot
import tech.hearth.protobuf.snapshot.TransactionStateSnapshot.NewAsset
import tech.hearth.protobuf.transaction.PBAmounts
import tech.hearth.state.*
import tech.hearth.transaction.Asset.IssuedAsset
import tech.hearth.transaction.{Asset, TxPositiveAmount}

import scala.collection.immutable.VectorMap

object PBSnapshots {

  import tech.hearth.protobuf.snapshot.TransactionStateSnapshot as S

  def toProtobuf(snapshot: StateSnapshot, txStatus: TxMeta.Status): TransactionStateSnapshot = {
    import snapshot.*
    TransactionStateSnapshot(
      balances = balances.map { case ((address, asset), balance) =>
        S.Balance(address.toByteString, Some(PBAmounts.fromAssetAndAmount(asset, balance)))
      }.toSeq,
      leaseBalances = leaseBalances.map { case (address, balance) =>
        S.LeaseBalance(address.toByteString, balance.in, balance.out)
      }.toSeq,
      newLeases = snapshot.newLeases.view.map { case (id, ld) =>
        S.NewLease(id.toByteString, ld.sender.toByteString, ld.recipientAddress.toByteString, ld.amount.value)
      }.toSeq,
      cancelledLeases = snapshot.cancelledLeases.view.map { case (id, _) =>
        S.CancelledLease(id.toByteString)
      }.toSeq,
      assetStatics = assetStatics
        .map { case (id, (st, idx)) =>
          (idx, NewAsset(id.id.toByteString, st.issuer.toByteString, st.decimals, st.nft))
        }
        .toSeq
        .sortBy(_._1)
        .map(_._2),
      assetVolumes = assetVolumes.map { case (asset, info) =>
        S.AssetVolume(asset.id.toByteString, info.isReissuable, ByteString.copyFrom(info.volume.toByteArray))
      }.toSeq,
      assetNamesAndDescriptions = assetNamesAndDescriptions.map { case (asset, info) =>
        S.AssetNameAndDescription(asset.id.toByteString, info.name.toStringUtf8, info.description.toStringUtf8)
      }.toSeq,
      orderFills = orderFills.map { case (orderId, VolumeAndFee(volume, fee)) =>
        S.OrderFill(orderId.toByteString, volume, fee)
      }.toSeq,
      transactionStatus = txStatus.protobuf,
      generationCommitment = nextCommittedGenerators.map { gc =>
        S.GenerationCommitment(gc.sender.toByteString, gc.endorserPublicKey.byteStr.toByteString, gc.vrfPublicKey.toByteString)
      }.headOption
    )
  }

  def fromProtobuf(pbSnapshot: TransactionStateSnapshot, txId: ByteStr, height: Height): (StateSnapshot, TxMeta.Status) = {
    val balances: VectorMap[(Address, Asset), Long] =
      VectorMap() ++ pbSnapshot.balances.map(b => (b.address.toAddress, b.getAmount.assetId.toAssetId) -> b.getAmount.amount)

    val leaseBalances: Map[Address, LeaseBalance] =
      pbSnapshot.leaseBalances
        .map(b => b.address.toAddress -> LeaseBalance(b.in, b.out))
        .toMap

    val assetStatics: Map[IssuedAsset, (AssetStaticInfo, Int)] =
      pbSnapshot.assetStatics.zipWithIndex.map { case (info, idx) =>
        info.assetId.toIssuedAssetId -> (
          AssetStaticInfo(
            info.assetId.toByteStr,
            TransactionId(txId),
            PublicKey(info.issuerPublicKey.toByteStr),
            info.decimals,
            info.nft
          ),
          idx + 1
        )
      }.toMap

    val assetVolumes: Map[IssuedAsset, AssetVolumeInfo] =
      pbSnapshot.assetVolumes
        .map(v => v.assetId.toIssuedAssetId -> AssetVolumeInfo(v.reissuable, BigInt(v.volume.toByteArray)))
        .toMap

    val assetNamesAndDescriptions: Map[IssuedAsset, AssetInfo] =
      pbSnapshot.assetNamesAndDescriptions
        .map(i => i.assetId.toIssuedAssetId -> AssetInfo(i.name, i.description, height))
        .toMap

    val newLeases = pbSnapshot.newLeases.map { l =>
      l.leaseId.toByteStr ->
        LeaseStaticInfo(
          l.senderPublicKey.toPublicKey,
          l.recipientAddress.toAddress,
          TxPositiveAmount.unsafeFrom(l.amount),
          TransactionId(txId),
          height
        )
    }.toMap

    val cancelledLeases = pbSnapshot.cancelledLeases.map { cl =>
      cl.leaseId.toByteStr -> LeaseDetails.Status.Cancelled(height, Some(TransactionId(txId)))
    }.toMap

    val orderFills: Map[ByteStr, VolumeAndFee] =
      pbSnapshot.orderFills
        .map(of => of.orderId.toByteStr -> VolumeAndFee(of.volume, of.fee))
        .toMap

    val nextCommittedGenerators = pbSnapshot.generationCommitment.map { x =>
      GenerationCommitment(x.senderPublicKey.toPublicKey, BlsPublicKey(x.endorserPublicKey.toByteArray).explicitGet(), x.vrfPublicKey.toByteStr)
    }.toSeq

    (
      StateSnapshot(
        VectorMap(),
        balances,
        leaseBalances,
        assetStatics,
        assetVolumes,
        assetNamesAndDescriptions,
        newLeases,
        cancelledLeases,
        orderFills,
        nextCommittedGenerators
      ),
      TxMeta.Status.fromProtobuf(pbSnapshot.transactionStatus)
    )
  }
}
