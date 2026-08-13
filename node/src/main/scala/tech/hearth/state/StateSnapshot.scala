package tech.hearth.state

import cats.implicits.{catsSyntaxEitherId, toBifunctorOps, toTraverseOps}
import cats.kernel.Monoid
import tech.hearth.account.Address
import tech.hearth.common.state.ByteStr
import tech.hearth.lang.ValidationError
import tech.hearth.transaction.Asset.{IssuedAsset, Hearth}
import tech.hearth.transaction.TxValidationError.GenericError
import tech.hearth.transaction.{Asset, Transaction}

import scala.collection.immutable.VectorMap

case class StateSnapshot(
    transactions: VectorMap[ByteStr, NewTransactionInfo] = VectorMap(),
    balances: VectorMap[(Address, Asset), Long] = VectorMap(), // VectorMap is used to preserve the order of NFTs for a given address
    leaseBalances: Map[Address, LeaseBalance] = Map(),
    assetStatics: Map[IssuedAsset, (AssetStaticInfo, Int)] = Map(),
    assetVolumes: Map[IssuedAsset, BigInt] = Map(),
    minAssetFees: Map[IssuedAsset, MinAssetFee] = Map(),
    newLeases: Map[ByteStr, LeaseStaticInfo] = Map(),
    cancelledLeases: Map[ByteStr, LeaseDetails.Status & LeaseDetails.Status.Inactive] = Map.empty,
    orderFills: Map[ByteStr, VolumeAndFee] = Map(),
    nextCommittedGenerators: Seq[GenerationCommitment] = Seq.empty,
    // DCAP collateral (see the StartBoost consensus plan): rootCaCrl/pckCrl/qeIdentity/tcbSigningIssuerChain are
    // each a single current value, tcbInfo is keyed per FMSPC (platform model) since only the FMSPCs actually
    // seen need an entry. "Last write wins" within a block, same as assetVolumes/minAssetFees above.
    dcapRootCaCrl: Option[ByteStr] = None,
    dcapPckCrl: Option[ByteStr] = None,
    dcapTcbInfo: Map[ByteStr, ByteStr] = Map.empty,
    dcapQeIdentity: Option[ByteStr] = None,
    dcapTcbSigningIssuerChain: Option[ByteStr] = None,
    dcapPckCaIssuerChain: Option[ByteStr] = None
) {

  // ignores lease balances from portfolios
  def addBalances(portfolios: Map[Address, Portfolio], blockchain: Blockchain): Either[String, StateSnapshot] =
    StateSnapshot
      .balances(portfolios, SnapshotBlockchain(blockchain, this))
      .map(b => copy(balances = balances ++ b))

  def withTransaction(tx: NewTransactionInfo): StateSnapshot =
    copy(transactions + (tx.transaction.id() -> tx))

  def bindElidedTransaction(blockchain: Blockchain, tx: Transaction): StateSnapshot =
    copy(
      transactions = transactions + (tx.id() -> NewTransactionInfo.create(tx, TxMeta.Status.Elided, StateSnapshot.empty, blockchain))
    )

  lazy val hashString: String =
    Integer.toHexString(hashCode())
}

object StateSnapshot {

  def build(
      blockchain: Blockchain,
      portfolios: Map[Address, Portfolio] = Map(),
      orderFills: Map[ByteStr, VolumeAndFee] = Map(),
      issuedAssets: Seq[(IssuedAsset, NewAssetInfo)] = Seq(),
      updatedMinAssetFees: Map[IssuedAsset, MinAssetFee] = Map(),
      newLeases: Map[ByteStr, LeaseStaticInfo] = Map(),
      cancelledLeases: Map[ByteStr, LeaseDetails.Status & LeaseDetails.Status.Inactive] = Map.empty,
      transactions: VectorMap[ByteStr, NewTransactionInfo] = VectorMap(),
      nextCommittedGenerators: Seq[GenerationCommitment] = Seq.empty,
      dcapRootCaCrl: Option[ByteStr] = None,
      dcapPckCrl: Option[ByteStr] = None,
      dcapTcbInfo: Map[ByteStr, ByteStr] = Map.empty,
      dcapQeIdentity: Option[ByteStr] = None,
      dcapTcbSigningIssuerChain: Option[ByteStr] = None,
      dcapPckCaIssuerChain: Option[ByteStr] = None
  ): Either[ValidationError, StateSnapshot] = {
    val r =
      for {
        b  <- balances(portfolios, blockchain)
        lb <- leaseBalances(portfolios, blockchain)
        of <- this.orderFills(orderFills, blockchain)
      } yield StateSnapshot(
        transactions,
        b,
        lb,
        assetStatics(issuedAssets),
        assetVolumes(issuedAssets),
        minAssetFees(issuedAssets, updatedMinAssetFees),
        newLeases,
        cancelledLeases,
        of,
        nextCommittedGenerators,
        dcapRootCaCrl,
        dcapPckCrl,
        dcapTcbInfo,
        dcapQeIdentity,
        dcapTcbSigningIssuerChain,
        dcapPckCaIssuerChain
      )
    r.leftMap(GenericError(_))
  }

  // ignores lease balances from portfolios
  private def balances(portfolios: Map[Address, Portfolio], blockchain: Blockchain): Either[String, VectorMap[(Address, Asset), Long]] =
    flatTraverse(portfolios) { case (address, Portfolio(hearthAmount, _, assets, _)) =>
      val assetBalancesE = flatTraverse(assets) {
        case (_, 0) =>
          Right(VectorMap[(Address, Asset), Long]())
        case (assetId, balance) =>
          safeSum(blockchain.balance(address, assetId), balance, s"$address -> Asset balance")
            .map(newBalance => VectorMap((address, assetId: Asset) -> newBalance))
      }
      if (hearthAmount != 0)
        for {
          assetBalances    <- assetBalancesE
          newHearthBalance <- safeSum(blockchain.balance(address), hearthAmount, s"$address -> Hearth balance")
        } yield assetBalances + ((address, Hearth) -> newHearthBalance)
      else
        assetBalancesE
    }

  private def flatTraverse[E, K1, V1, K2, V2](m: Map[K1, V1])(f: (K1, V1) => Either[E, VectorMap[K2, V2]]): Either[E, VectorMap[K2, V2]] =
    m.foldLeft(VectorMap[K2, V2]().asRight[E]) {
      case (e @ Left(_), _) =>
        e
      case (Right(acc), (k, v)) =>
        f(k, v).map(acc ++ _)
    }

  def ofLeaseBalances(balances: Map[Address, LeaseBalance], blockchain: Blockchain): Either[String, StateSnapshot] =
    balances.toSeq
      .traverse { case (address, leaseBalance) =>
        leaseBalance.combineF[[X] =>> Either[String, X]](blockchain.leaseBalance(address)).map(address -> _)
      }
      .map(newBalances => StateSnapshot(leaseBalances = newBalances.toMap))

  private def leaseBalances(portfolios: Map[Address, Portfolio], blockchain: Blockchain): Either[String, Map[Address, LeaseBalance]] =
    portfolios.toSeq
      .flatTraverse {
        case (address, Portfolio(_, lease, _, _)) if lease.out != 0 || lease.in != 0 =>
          val bLease = blockchain.leaseBalance(address)
          for {
            newIn  <- safeSum(bLease.in, lease.in, s"$address -> Lease")
            newOut <- safeSum(bLease.out, lease.out, s"$address -> Lease")
          } yield Seq(address -> LeaseBalance(newIn, newOut))
        case _ =>
          Seq().asRight[String]
      }
      .map(_.toMap)

  def assetStatics(issuedAssets: Seq[(IssuedAsset, NewAssetInfo)]): Map[IssuedAsset, (AssetStaticInfo, Int)] =
    issuedAssets.view.zipWithIndex.map { case ((asset, info), idx) =>
      asset -> (info.static, idx + 1)
    }.toMap

  // an asset's volume is fixed forever at issuance - a predefined snapshot can only mint a brand-new asset id,
  // never touch an existing one - so there is no "merge with an existing volume" case to handle here
  private def assetVolumes(issuedAssets: Seq[(IssuedAsset, NewAssetInfo)]): Map[IssuedAsset, BigInt] =
    issuedAssets.view.map { case (id, nai) => id -> nai.volume }.toMap

  private def minAssetFees(
      issuedAssets: Seq[(IssuedAsset, NewAssetInfo)],
      updatedMinAssetFees: Map[IssuedAsset, MinAssetFee]
  ): Map[IssuedAsset, MinAssetFee] =
    issuedAssets.view.map { case (id, nai) => id -> nai.minAssetFee }.toMap ++ updatedMinAssetFees

  private def orderFills(volumeAndFees: Map[ByteStr, VolumeAndFee], blockchain: Blockchain): Either[String, Map[ByteStr, VolumeAndFee]] =
    volumeAndFees.toSeq
      .traverse { case (orderId, value) =>
        value.combineE(blockchain.filledVolumeAndFee(orderId)).map(orderId -> _)
      }
      .map(_.toMap)

  implicit val monoid: Monoid[StateSnapshot] = new Monoid[StateSnapshot] {
    override val empty: StateSnapshot =
      StateSnapshot()

    override def combine(s1: StateSnapshot, s2: StateSnapshot): StateSnapshot =
      StateSnapshot(
        s1.transactions ++ s2.transactions,
        s1.balances ++ s2.balances,
        s1.leaseBalances ++ s2.leaseBalances,
        s1.assetStatics ++ s2.assetStatics.map { case (id, (asi, idx)) => (id, (asi, idx + s1.assetStatics.size)) },
        s1.assetVolumes ++ s2.assetVolumes,
        s1.minAssetFees ++ s2.minAssetFees,
        s1.newLeases ++ s2.newLeases,
        s1.cancelledLeases ++ s2.cancelledLeases,
        s1.orderFills ++ s2.orderFills,
        s1.nextCommittedGenerators ++ s2.nextCommittedGenerators,
        s2.dcapRootCaCrl.orElse(s1.dcapRootCaCrl),
        s2.dcapPckCrl.orElse(s1.dcapPckCrl),
        s1.dcapTcbInfo ++ s2.dcapTcbInfo,
        s2.dcapQeIdentity.orElse(s1.dcapQeIdentity),
        s2.dcapTcbSigningIssuerChain.orElse(s1.dcapTcbSigningIssuerChain),
        s2.dcapPckCaIssuerChain.orElse(s1.dcapPckCaIssuerChain)
      )

  }

  val empty: StateSnapshot = StateSnapshot()
}
