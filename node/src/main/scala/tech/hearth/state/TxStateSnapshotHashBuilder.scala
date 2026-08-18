package tech.hearth.state

import cats.implicits.catsSyntaxSemigroup
import cats.syntax.either.*
import com.google.common.primitives.{Longs, UnsignedBytes}
import tech.hearth.common.state.ByteStr
import tech.hearth.common.utils.EitherExt2.*
import tech.hearth.crypto
import tech.hearth.lang.ValidationError
import tech.hearth.state.TxMeta.Status
import tech.hearth.state.diffs.BlockDiffer.CurrentBlockFeePart
import tech.hearth.state.diffs.TransactionDiffer
import tech.hearth.transaction.Asset.{IssuedAsset, Hearth}
import tech.hearth.transaction.smart.script.trace.TracedResult
import tech.hearth.transaction.Transaction
import org.bouncycastle.crypto.digests.Blake2bDigest
import tech.hearth.crypto.SigningKey

import java.nio.charset.StandardCharsets
import scala.collection.mutable

object TxStateSnapshotHashBuilder {
  private implicit val ByteArrayOrdering: Ordering[Array[Byte]] = (x, y) => UnsignedBytes.lexicographicalComparator().compare(x, y)

  val InitStateHash: ByteStr = ByteStr(crypto.fastHash(""))

  final case class Result(txStateSnapshotHash: ByteStr) {
    def createHash(prevHash: ByteStr): ByteStr =
      TxStateSnapshotHashBuilder.createHash(Seq(prevHash.arr, txStateSnapshotHash.arr))
  }

  case class TxStatusInfo(id: ByteStr, status: TxMeta.Status)

  def createHashFromSnapshot(snapshot: StateSnapshot, txStatusOpt: Option[TxStatusInfo]): Result = {
    val changedKeys = mutable.SortedSet.empty[Array[Byte]]

    snapshot.balances.foreach { case ((address, asset), balance) =>
      asset match {
        case Hearth             => changedKeys += address.toBytes ++ Longs.toByteArray(balance)
        case asset: IssuedAsset => changedKeys += address.toBytes ++ asset.id.arr ++ Longs.toByteArray(balance)
      }
    }

    snapshot.leaseBalances.foreach { case (address, balance) =>
      changedKeys += address.toBytes ++ Longs.toByteArray(balance.in) ++ Longs.toByteArray(balance.out)
    }

    snapshot.newLeases.foreach { case (leaseId, details) =>
      changedKeys += leaseId.arr ++ booleanToBytes(true)
      changedKeys += leaseId.arr ++ details.sender.arr ++ details.recipientAddress.toBytes ++ Longs.toByteArray(details.amount.value)
    }

    snapshot.cancelledLeases.keys.foreach { leaseId =>
      changedKeys += leaseId.arr ++ booleanToBytes(false)
    }

    snapshot.orderFills.foreach { case (orderId, fillInfo) =>
      changedKeys += orderId.arr ++ Longs.toByteArray(fillInfo.volume) ++ Longs.toByteArray(fillInfo.fee)
    }

    snapshot.assetStatics.foreach { case (asset, (assetInfo, _)) =>
      changedKeys += asset.id.arr ++
        Array(assetInfo.decimals.toByte) ++
        assetInfo.name.getBytes(StandardCharsets.UTF_8) ++
        assetInfo.description.getBytes(StandardCharsets.UTF_8)
    }

    snapshot.assetVolumes.foreach { case (asset, volume) =>
      changedKeys += asset.id.arr ++ volume.toByteArray
    }

    snapshot.minAssetFees.foreach { case (asset, minFee) =>
      changedKeys += asset.id.arr ++ Longs.toByteArray(minFee.value)
    }

    snapshot.nextCommittedGenerators.foreach { gc =>
      changedKeys += gc.sender.arr ++ gc.endorserPublicKey.arr ++ gc.vrfPublicKey.arr
    }

    snapshot.nextRegisteredEnclaves.foreach { re =>
      changedKeys += re.enclavePublicKey.arr ++ re.validator.toBytes ++ re.operator.toBytes
    }

    // DCAP collateral (see the StartBoost consensus plan): none of these six fields have a TransactionStateSnapshot
    // protobuf representation (light-node sync never carries them, same as minAssetFees below), but they are still
    // real consensus state a node can diverge on - PredefinedSnapshot/UpdateCollateralTransactionDiff set them
    // directly on the in-memory StateSnapshot this function hashes, so they need hashing here regardless.
    snapshot.dcapRootCaCrl.foreach(v => changedKeys += "dcapRootCaCrl".getBytes(StandardCharsets.UTF_8) ++ v.arr)
    snapshot.dcapPckCrl.foreach(v => changedKeys += "dcapPckCrl".getBytes(StandardCharsets.UTF_8) ++ v.arr)
    snapshot.dcapTcbInfo.foreach { case (fmspc, v) => changedKeys += "dcapTcbInfo".getBytes(StandardCharsets.UTF_8) ++ fmspc.arr ++ v.arr }
    snapshot.dcapQeIdentity.foreach(v => changedKeys += "dcapQeIdentity".getBytes(StandardCharsets.UTF_8) ++ v.arr)
    snapshot.dcapTcbSigningIssuerChain.foreach(v => changedKeys += "dcapTcbSigningIssuerChain".getBytes(StandardCharsets.UTF_8) ++ v.arr)
    snapshot.dcapPckCaIssuerChain.foreach(v => changedKeys += "dcapPckCaIssuerChain".getBytes(StandardCharsets.UTF_8) ++ v.arr)

    txStatusOpt.foreach(txInfo =>
      txInfo.status match {
        case Status.Failed    => changedKeys += txInfo.id.arr ++ Array(1: Byte)
        case Status.Elided    => changedKeys += txInfo.id.arr ++ Array(2: Byte)
        case Status.Succeeded =>
      }
    )

    Result(createHash(changedKeys))
  }

  /** The genesis block carries no transactions, so its state hash covers the predefined snapshot as a whole. */
  def createGenesisStateHash(snapshot: StateSnapshot): ByteStr =
    createHashFromSnapshot(snapshot, None).createHash(InitStateHash)

  def computeStateHash(
      txs: Seq[Transaction],
      initStateHash: ByteStr,
      initSnapshot: StateSnapshot,
      signer: SigningKey,
      prevBlockTimestamp: Option[Long],
      currentBlockTimestamp: Long,
      isChallenging: Boolean,
      blockchain: Blockchain
  ): TracedResult[ValidationError, ByteStr] = {
    val txDiffer = TransactionDiffer(prevBlockTimestamp, currentBlockTimestamp)

    txs
      .foldLeft[TracedResult[ValidationError, (ByteStr, StateSnapshot)]](TracedResult.wrapValue(initStateHash -> initSnapshot)) {
        case (TracedResult(Right((prevStateHash, accSnapshot)), _, _), tx) =>
          val accBlockchain  = SnapshotBlockchain(blockchain, accSnapshot)
          val txDifferResult = txDiffer(accBlockchain, tx)
          txDifferResult.resultE match {
            case Right(txSnapshot) =>
              val (feeAsset, feeAmount) = tx.assetFee
              val minerPortfolio        = Map(signer.toAddress -> Portfolio.build(feeAsset, feeAmount).multiply(CurrentBlockFeePart))

              val txSnapshotWithBalances = txSnapshot.addBalances(minerPortfolio, accBlockchain).explicitGet()
              val txInfo                 = txSnapshot.transactions.head._2
              val stateHash =
                TxStateSnapshotHashBuilder
                  .createHashFromSnapshot(txSnapshotWithBalances, Some(TxStatusInfo(txInfo.transaction.id(), txInfo.status)))
                  .createHash(prevStateHash)

              txDifferResult.copy(resultE = Right((stateHash, accSnapshot |+| txSnapshotWithBalances)))
            case Left(_) if isChallenging =>
              txDifferResult.copy(resultE =
                Right(
                  (
                    TxStateSnapshotHashBuilder
                      .createHashFromSnapshot(StateSnapshot.empty, Some(TxStatusInfo(tx.id(), TxMeta.Status.Elided)))
                      .createHash(prevStateHash),
                    accSnapshot.bindElidedTransaction(accBlockchain, tx)
                  )
                )
              )

            case Left(err) => txDifferResult.copy(resultE = err.asLeft[(ByteStr, StateSnapshot)])
          }
        case (err @ TracedResult(Left(_), _, _), _) => err
      }
      .map(_._1)
  }

  private def booleanToBytes(flag: Boolean): Array[Byte] =
    if (flag) Array(1: Byte) else Array(0: Byte)

  private def createHash(bs: Iterable[Array[Byte]]): ByteStr = {
    val digestFn: Blake2bDigest = new Blake2bDigest(crypto.DigestLength * 8)
    bs.foreach(bs => digestFn.update(bs, 0, bs.length))
    val result = new Array[Byte](crypto.DigestLength)
    digestFn.doFinal(result, 0)
    ByteStr(result)
  }

}
