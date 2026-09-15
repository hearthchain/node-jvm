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
    nextRegisteredEnclaves: Seq[RegisteredEnclave] = Seq.empty,
    // DCAP collateral (see the StartBoost consensus plan): rootCaCrl/pckCrl/qeIdentity/tcbSigningIssuerChain are
    // each a single current value, tcbInfo is keyed per FMSPC (platform model) since only the FMSPCs actually
    // seen need an entry. "Last write wins" within a block, same as assetVolumes/minAssetFees above.
    dcapRootCaCrl: Option[ByteStr] = None,
    dcapPckCrl: Option[ByteStr] = None,
    dcapTcbInfo: Map[ByteStr, ByteStr] = Map.empty,
    dcapQeIdentity: Option[ByteStr] = None,
    dcapTcbSigningIssuerChain: Option[ByteStr] = None,
    dcapPckCaIssuerChain: Option[ByteStr] = None,
    // ReserveTransaction's new accumulated total per (sender, miner, asset) - the Diff reads the current total via
    // Blockchain.reservedAmount and adds tx.amount, so this already carries the final value, same "last write wins
    // within a block" convention as dcapTcbInfo above (harmless in practice: two Reserve txs to the same triple in
    // one block each read-then-write through SnapshotBlockchain in order, so the second sees the first's result).
    reservedAmounts: Map[(Address, Address, IssuedAsset), Long] = Map.empty,
    // BindApiKeyTransaction's HPKE-sealed API key envelope, keyed by (enclavePublicKey, sender); upsert, same
    // "last write wins" convention.
    apiKeyBindings: Map[(ByteStr, Address), ByteStr] = Map.empty,
    // SettleTransaction's new cumulative-settled counter per (client, miner, asset) - like reservedAmounts above,
    // the Diff reads the current value (via Blockchain.settledAmount) and writes the batch's final cumulative
    // value, so this already carries the final value, not a delta.
    settledAmounts: Map[(Address, Address, IssuedAsset), Long] = Map.empty,
    // Work attributed to a validator within the current generation period (epoch), fed by SettleTransaction's
    // burned share - see Keys.workDoneSuffix. Same "Diff reads current value, writes the final accumulated total"
    // convention as reservedAmounts/settledAmounts above, not a delta.
    workDone: Map[(Address, GenerationPeriod), Long] = Map.empty,
    // StakeTransaction's (active, pending) pair per address, and the period-boundary payout's normalisation of it -
    // see StakeRecord. Like reservedAmounts/workDone above the Diff reads the current record and writes the final
    // one, not a delta; an emptied stake is carried as StakeRecord.empty rather than an absent entry, so that
    // clearing one is a write the state hash and the storage layer both see.
    stakes: Map[Address, StakeRecord] = Map.empty,
    // The whole staker set, when this snapshot changes it - Option rather than Seq so that "left unchanged" is
    // distinguishable from "changed to empty", which a plain Seq could not express and the monoid could not merge.
    stakers: Option[Seq[Address]] = None
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
      updatedAssetVolumes: Map[IssuedAsset, BigInt] = Map(),
      updatedMinAssetFees: Map[IssuedAsset, MinAssetFee] = Map(),
      newLeases: Map[ByteStr, LeaseStaticInfo] = Map(),
      cancelledLeases: Map[ByteStr, LeaseDetails.Status & LeaseDetails.Status.Inactive] = Map.empty,
      transactions: VectorMap[ByteStr, NewTransactionInfo] = VectorMap(),
      nextCommittedGenerators: Seq[GenerationCommitment] = Seq.empty,
      nextRegisteredEnclaves: Seq[RegisteredEnclave] = Seq.empty,
      dcapRootCaCrl: Option[ByteStr] = None,
      dcapPckCrl: Option[ByteStr] = None,
      dcapTcbInfo: Map[ByteStr, ByteStr] = Map.empty,
      dcapQeIdentity: Option[ByteStr] = None,
      dcapTcbSigningIssuerChain: Option[ByteStr] = None,
      dcapPckCaIssuerChain: Option[ByteStr] = None,
      reservedAmounts: Map[(Address, Address, IssuedAsset), Long] = Map.empty,
      apiKeyBindings: Map[(ByteStr, Address), ByteStr] = Map.empty,
      settledAmounts: Map[(Address, Address, IssuedAsset), Long] = Map.empty,
      workDone: Map[(Address, GenerationPeriod), Long] = Map.empty,
      stakes: Map[Address, StakeRecord] = Map.empty,
      stakers: Option[Seq[Address]] = None
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
        assetVolumes(issuedAssets) ++ updatedAssetVolumes,
        minAssetFees(issuedAssets, updatedMinAssetFees),
        newLeases,
        cancelledLeases,
        of,
        nextCommittedGenerators,
        nextRegisteredEnclaves,
        dcapRootCaCrl,
        dcapPckCrl,
        dcapTcbInfo,
        dcapQeIdentity,
        dcapTcbSigningIssuerChain,
        dcapPckCaIssuerChain,
        reservedAmounts,
        apiKeyBindings,
        settledAmounts,
        workDone,
        stakes,
        stakers
      )
    r.leftMap(GenericError(_))
  }

  // ignores lease balances from portfolios
  private def balances(portfolios: Map[Address, Portfolio], blockchain: Blockchain): Either[String, VectorMap[(Address, Asset), Long]] =
    flatTraverse(portfolios) { case (address, portfolio) =>
      val hearthAmount = portfolio.balance
      val assetBalancesE = flatTraverse(portfolio.assets) {
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
        case (address, portfolio) if portfolio.lease.out != 0 || portfolio.lease.in != 0 =>
          val lease  = portfolio.lease
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

  // The volume an issuance starts at. A predefined snapshot can also re-issue an existing asset, which arrives
  // through build's updatedAssetVolumes instead and carries the new total, not a delta.
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
        s1.nextRegisteredEnclaves ++ s2.nextRegisteredEnclaves,
        s2.dcapRootCaCrl.orElse(s1.dcapRootCaCrl),
        s2.dcapPckCrl.orElse(s1.dcapPckCrl),
        s1.dcapTcbInfo ++ s2.dcapTcbInfo,
        s2.dcapQeIdentity.orElse(s1.dcapQeIdentity),
        s2.dcapTcbSigningIssuerChain.orElse(s1.dcapTcbSigningIssuerChain),
        s2.dcapPckCaIssuerChain.orElse(s1.dcapPckCaIssuerChain),
        s1.reservedAmounts ++ s2.reservedAmounts,
        s1.apiKeyBindings ++ s2.apiKeyBindings,
        s1.settledAmounts ++ s2.settledAmounts,
        s1.workDone ++ s2.workDone,
        s1.stakes ++ s2.stakes,
        s2.stakers.orElse(s1.stakers)
      )

  }

  val empty: StateSnapshot = StateSnapshot()
}
