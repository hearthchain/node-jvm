package tech.hearth.protobuf

import com.google.protobuf.ByteString
import tech.hearth.account.Address
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
      // NewAsset.issuer_public_key/is_nft are wire-compat only: AssetStaticInfo no longer carries an issuer or
      // an NFT flag (nothing checks either any more), so they're written as constants and never read back
      assetStatics = assetStatics
        .map { case (id, (st, idx)) =>
          (idx, NewAsset(id.id.toByteString, ByteString.EMPTY, st.decimals, nft = false))
        }
        .toSeq
        .sortBy(_._1)
        .map(_._2),
      // AssetVolume.reissuable is wire-compat only: nothing reissues an asset any more, so it's written as a
      // constant and never read back
      assetVolumes = assetVolumes.map { case (asset, volume) =>
        S.AssetVolume(asset.id.toByteString, reissuable = false, ByteString.copyFrom(volume.toByteArray))
      }.toSeq,
      assetNamesAndDescriptions = assetStatics
        .map { case (asset, (st, idx)) =>
          (idx, S.AssetNameAndDescription(asset.id.toByteString, st.name, st.description))
        }
        .toSeq
        .sortBy(_._1)
        .map(_._2),
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

    // AssetVolume.reissuable and NewAsset.issuer_public_key/is_nft are wire-compat only, never read back - see
    // toProtobuf
    val namesAndDescriptions: Map[ByteStr, (String, String)] =
      pbSnapshot.assetNamesAndDescriptions.map(i => i.assetId.toByteStr -> (i.name, i.description)).toMap

    val assetStatics: Map[IssuedAsset, (AssetStaticInfo, Int)] =
      pbSnapshot.assetStatics.zipWithIndex.map { case (info, idx) =>
        val (name, description) = namesAndDescriptions.getOrElse(info.assetId.toByteStr, ("", ""))
        info.assetId.toIssuedAssetId -> (
          AssetStaticInfo(info.assetId.toByteStr, info.decimals, name, description),
          idx + 1
        )
      }.toMap

    val assetVolumes: Map[IssuedAsset, BigInt] =
      pbSnapshot.assetVolumes
        .map(v => v.assetId.toIssuedAssetId -> BigInt(v.volume.toByteArray))
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
        Map.empty, // a per-transaction snapshot never sets minAssetFee - only PredefinedSnapshot does
        newLeases,
        cancelledLeases,
        orderFills,
        nextCommittedGenerators
      ),
      TxMeta.Status.fromProtobuf(pbSnapshot.transactionStatus)
    )
  }
}
