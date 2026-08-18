package tech.hearth.database

import com.google.common.primitives.{Ints, Longs}
import tech.hearth.common.state.ByteStr
import tech.hearth.crypto.bls.BlsPublicKey
import tech.hearth.database.protobuf.{EthereumTransactionMeta, StaticAssetInfo, TransactionMeta, BlockMeta as PBBlockMeta}
import tech.hearth.protobuf.snapshot.TransactionStateSnapshot
import tech.hearth.state.*
import tech.hearth.transaction.Asset
import tech.hearth.transaction.Asset.{AssetIdOps, IssuedAsset}
import tech.hearth.transaction.Transaction
import tech.hearth.crypto.Address

case class CurrentBalance(balance: Long, height: Height, prevHeight: Height)
object CurrentBalance {
  val Unavailable: CurrentBalance = CurrentBalance(0L, Height(0), Height(0))
}

case class BalanceNode(balance: Long, prevHeight: Height)
object BalanceNode {
  val Empty: BalanceNode = BalanceNode(0, Height(0))
  val SizeInBytes: Int   = 12
}

case class CurrentVolumeAndFee(volume: Long, fee: Long, height: Height, prevHeight: Height)
object CurrentVolumeAndFee {
  val Unavailable: CurrentVolumeAndFee = CurrentVolumeAndFee(0, 0, Height(0), Height(0))
}

case class VolumeAndFeeNode(volume: Long, fee: Long, prevHeight: Height)
object VolumeAndFeeNode {
  val Empty: VolumeAndFeeNode = VolumeAndFeeNode(0, 0, Height(0))
}

case class CurrentLeaseBalance(in: Long, out: Long, height: Height, prevHeight: Height)
object CurrentLeaseBalance {
  val Unavailable: CurrentLeaseBalance = CurrentLeaseBalance(0, 0, Height(0), Height(0))
}

case class LeaseBalanceNode(in: Long, out: Long, prevHeight: Height)
object LeaseBalanceNode {
  val Empty: LeaseBalanceNode = LeaseBalanceNode(0, 0, Height(0))
}

object Keys {
  import KeyHelpers.*
  import KeyTag.{AddressId as AddressIdTag, EthereumTransactionMeta as EthereumTransactionMetaTag, LeaseDetails as LeaseDetailsTag, *}

  val version: Key[Int]   = intKey(Version, default = 1)
  val height: Key[Height] = heightKey(Height)

  def heightOf(blockId: ByteStr): Key[Option[Int]] = Key.opt[Int](HeightOf, blockId.arr, Ints.fromByteArray, Ints.toByteArray)

  def hearthBalance(addressId: AddressId): Key[CurrentBalance] =
    Key(HearthBalance, addressId.toByteArray, readCurrentBalance, writeCurrentBalance)

  def hearthBalanceAt(addressId: AddressId, height: Height): Key[BalanceNode] =
    Key(HearthBalanceHistory, hBytes(addressId.toByteArray, height), readBalanceNode, writeBalanceNode)

  def assetBalance(addressId: AddressId, asset: IssuedAsset): Key[CurrentBalance] =
    Key(AssetBalance, addressId.toByteArray ++ asset.id.arr, readCurrentBalance, writeCurrentBalance)

  def assetBalanceAt(addressId: AddressId, asset: IssuedAsset, height: Height): Key[BalanceNode] =
    Key(AssetBalanceHistory, hBytes(asset.id.arr ++ addressId.toByteArray, height), readBalanceNode, writeBalanceNode)

  def assetVolumeDetailsHistory(asset: IssuedAsset): Key[Seq[Height]] = historyKey(AssetVolumeDetailsHistory, asset.id.arr)
  def assetVolumeDetails(asset: IssuedAsset)(height: Height): Key[BigInt] =
    Key(AssetVolumeDetails, hBytes(asset.id.arr, height), readAssetVolumeDetails, writeAssetVolumeDetails)

  def assetMinFeeHistory(asset: IssuedAsset): Key[Seq[Height]] = historyKey(AssetMinFeeHistory, asset.id.arr)
  def assetMinFee(asset: IssuedAsset)(height: Height): Key[MinAssetFee] =
    Key(AssetMinFee, hBytes(asset.id.arr, height), readAssetMinFee, writeAssetMinFee)

  def issuedAssets(height: Height): Key[Seq[IssuedAsset]] =
    Key(IssuedAssets, h(height), d => readAssetIds(d).map(IssuedAsset(_)), ias => writeAssetIds(ias.map(_.id)))
  def assetsWithMinFee(height: Height): Key[Seq[IssuedAsset]] =
    Key(AssetsWithMinFee, h(height), d => readAssetIds(d).map(IssuedAsset(_)), ias => writeAssetIds(ias.map(_.id)))
  def leaseBalanceAt(addressId: AddressId, height: Height): Key[LeaseBalanceNode] =
    Key(LeaseBalanceHistory, hBytes(addressId.toByteArray, height), readLeaseBalanceNode, writeLeaseBalanceNode)

  def leaseBalance(addressId: AddressId): Key[CurrentLeaseBalance] =
    Key(LeaseBalance, addressId.toByteArray, readLeaseBalance, writeLeaseBalance)

  def leaseDetailsHistory(leaseId: ByteStr): Key[Seq[Height]] = historyKey(LeaseDetailsHistory, leaseId.arr)
  def leaseDetails(leaseId: ByteStr)(height: Height): Key[Option[LeaseDetails]] =
    Key.opt(LeaseDetailsTag, height.toByteArray ++ leaseId.arr, readLeaseDetails, writeLeaseDetails)

  def filledVolumeAndFeeAt(orderId: ByteStr, height: Height): Key[VolumeAndFeeNode] =
    Key(FilledVolumeAndFeeHistory, hBytes(orderId.arr, height), readVolumeAndFeeNode, writeVolumeAndFeeNode)

  def filledVolumeAndFee(orderId: ByteStr): Key[CurrentVolumeAndFee] =
    Key(FilledVolumeAndFee, orderId.arr, readVolumeAndFee, writeVolumeAndFee)

  def changedAddresses(height: Height): Key[Seq[AddressId]] = Key(ChangedAddresses, h(height), readAddressIds, writeAddressIds)

  def changedHearthBalances(height: Height): Key[Seq[AddressId]] =
    Key(ChangedHearthBalances, h(height), readAddressIds, writeAddressIds)

  def changedBalances(height: Height, asset: IssuedAsset): Key[Seq[AddressId]] =
    Key(ChangedAssetBalances, h(height) ++ asset.id.arr, readAddressIds, writeAddressIds)

  def changedBalancesAtPrefix(height: Height): Array[Byte] = KeyTag.ChangedAssetBalances.prefixBytes ++ h(height)

  val lastAddressId: Key[Option[Long]] = Key.opt(LastAddressId, Array.emptyByteArray, Longs.fromByteArray, _.toByteArray)

  def addressId(address: Address): Key[Option[AddressId]] = Key.opt(AddressIdTag, address.toBytes, AddressId.fromByteArray, _.toByteArray)
  def idToAddress(addressId: AddressId): Key[Address]     = Key(IdToAddress, addressId.toByteArray, Address.fromBytes(_).get(), _.toBytes)

  val approvedFeatures: Key[Map[Short, Height]]  = Key(ApprovedFeatures, Array.emptyByteArray, readFeatureMap, writeFeatureMap)
  val activatedFeatures: Key[Map[Short, Height]] = Key(ActivatedFeatures, Array.emptyByteArray, readFeatureMap, writeFeatureMap)

  val safeRollbackHeight: Key[Height] = heightKey(SafeRollbackHeight)
  val lastCleanupHeight: Key[Height]  = heightKey(LastCleanupHeight)

  def changedDataKeys(height: Height, addressId: AddressId): Key[Seq[String]] =
    Key(ChangedDataKeys, hBytes(addressId.toByteArray, height), readStrings, writeStrings)

  def blockMetaAt(height: Height): Key[Option[PBBlockMeta]] =
    Key.opt(BlockInfoAtHeight, h(height), readBlockMeta, writeBlockMeta)

  def blockInfoBytesAt(height: Height): Key[Option[Array[Byte]]] =
    Key.opt(
      BlockInfoAtHeight,
      h(height),
      identity,
      unsupported("Can not explicitly write block bytes")
    )

  def transactionAt(height: Height, n: TxNum, cfHandle: RDB.TxHandle): Key[Option[(TxMeta, Transaction)]] =
    Key.opt[(TxMeta, Transaction)](
      NthTransactionInfoAtHeight,
      hNum(height, n),
      readTransaction(height),
      writeTransaction,
      Some(cfHandle.handle)
    )

  def transactionStateSnapshotAt(height: Height, n: TxNum, cfHandle: RDB.TxSnapshotHandle): Key[Option[TransactionStateSnapshot]] =
    Key.opt[TransactionStateSnapshot](
      NthTransactionStateSnapshotAtHeight,
      hNum(height, n),
      TransactionStateSnapshot.parseFrom,
      _.toByteArray,
      Some(cfHandle.handle)
    )

  def addressTransactionSeqNr(addressId: AddressId, cfh: RDB.ApiHandle): Key[Int] =
    bytesSeqNr(AddressTransactionSeqNr, addressId.toByteArray, cfh = Some(cfh.handle))

  def addressTransactionHN(addressId: AddressId, seqNr: Int, cfh: RDB.ApiHandle): Key[Option[(Height, Seq[(Byte, TxNum, Int)])]] =
    Key.opt(
      AddressTransactionHeightTypeAndNums,
      intBytes(addressId.toByteArray, seqNr),
      readTransactionHNSeqAndType,
      writeTransactionHNSeqAndType,
      Some(cfh.handle)
    )

  def addressLeaseSeqNr(addressId: AddressId, cfh: RDB.ApiHandle): Key[Int] =
    bytesSeqNr(AddressLeaseInfoSeqNr, addressId.toByteArray, cfh = Some(cfh.handle))

  def addressLeaseSeq(addressId: AddressId, seqNr: Int, cfh: RDB.ApiHandle): Key[Option[Seq[ByteStr]]] =
    Key.opt(
      AddressLeaseInfoSeq,
      intBytes(addressId.toByteArray, seqNr),
      readLeaseIdSeq,
      writeLeaseIdSeq,
      Some(cfh.handle)
    )

  def transactionMetaById(txId: TransactionId, cfh: RDB.TxMetaHandle): Key[Option[TransactionMeta]] =
    Key.opt(
      TransactionMetaById,
      txId.arr,
      TransactionMeta.parseFrom,
      _.toByteArray,
      Some(cfh.handle)
    )

  def assetStaticInfo(asset: IssuedAsset): Key[Option[StaticAssetInfo]] =
    Key.opt(AssetStaticInfo, asset.id.arr.take(20), StaticAssetInfo.parseFrom, _.toByteArray)

  def stateHash(height: Height): Key[Option[StateHash]] =
    Key.opt(StateHash, h(height), readStateHash, writeStateHash)

  def blockStateHash(height: Height): Key[ByteStr] =
    Key(BlockStateHash, h(height), Option(_).fold(TxStateSnapshotHashBuilder.InitStateHash)(ByteStr(_)), _.arr)

  def ethereumTransactionMeta(height: Height, txNum: TxNum, cfh: RDB.ApiHandle): Key[Option[EthereumTransactionMeta]] =
    Key.opt(EthereumTransactionMetaTag, hNum(height, txNum), EthereumTransactionMeta.parseFrom, _.toByteArray, Some(cfh.handle))

  def maliciousMinerBanHeights(addressBytes: Array[Byte]): Key[Seq[Height]] =
    historyKey(MaliciousMinerBanHeights, addressBytes)

  // Writes only after DeterministicFinality activation
  val finalizedHeight: Key[Option[Height]] = Key(
    FinalizedBlockHeight,
    keySuffix = Array.emptyByteArray,
    readFinalizedHeight,
    _.fold(Array.emptyByteArray)(_.toByteArray)
  )

  def finalizedHeightAt(at: Height): Key[Option[Height]] = Key(
    FinalizedBlockHeightAt,
    keySuffix = h(at),
    readFinalizedHeight,
    _.fold(Array.emptyByteArray)(_.toByteArray)
  )

  private def readFinalizedHeight(bytes: Array[Byte]): Option[Height] =
    Option(bytes).collect { case bs if bs.length == Ints.BYTES => tech.hearth.state.Height(Ints.fromByteArray(bytes)) }

  /** Key: Int(committedPeriod.start) ++ Int(commitmentHeight)
    * @note
    *   committedPeriod.start >= commitmentHeight, because a generator can commit only for a next period
    */
  def committedGenerators(committedPeriod: GenerationPeriod, commitmentHeight: Height): Key[Option[Seq[(AddressId, BlsPublicKey, ByteStr)]]] =
    Key.opt(
      CommittedGenerators,
      h(committedPeriod.start) ++ h(commitmentHeight),
      readCommittedGenerators,
      writeCommittedGenerators
    )

  /** Key: Int(registeredPeriod.start) ++ Int(registrationHeight), same shape as committedGenerators - a StartBoost
    * registers an enclave for a period exactly like CommitToGeneration commits a generator for one.
    */
  def registeredEnclaves(registeredPeriod: GenerationPeriod, registrationHeight: Height): Key[Option[Seq[RegisteredEnclave]]] =
    Key.opt(
      RegisteredEnclaves,
      h(registeredPeriod.start) ++ h(registrationHeight),
      readRegisteredEnclaves,
      writeRegisteredEnclaves
    )

  def conflictGenerators(committedPeriod: GenerationPeriod, conflictEndorsementHeight: Height): Key[Seq[GeneratorIndex]] =
    Key(
      ConflictGenerators,
      h(committedPeriod.start) ++ h(conflictEndorsementHeight),
      readConflictGenerators,
      writeConflictGenerators
    )

  def commitmentTransactions(committedPeriod: GenerationPeriod, commitmentHeight: Height): Key[Seq[TransactionId]] =
    Key(
      CommitmentTransactions,
      h(committedPeriod.start) ++ h(commitmentHeight),
      readCommitmentTransactions,
      writeCommitmentTransactions
    )

  def generatorBalances(at: Height, cfh: RDB.ApiHandle): Key[Option[Seq[(GeneratorIndex, Long)]]] =
    Key.opt(GeneratorBalances, h(at), readGeneratorBalances, writeGeneratorBalances, Some(cfh.handle))

  // DCAP collateral (see the StartBoost consensus plan): each slot is a single "current" value that gets
  // wholesale-replaced at arbitrary heights, so it needs history the same way an asset's minted volume does
  // (assetVolumeDetailsHistory/assetVolumeDetails above), not a plain overwritable key that rollback can't undo.
  private def dcapCollateralValue(keyTag: KeyTag, suffix: Array[Byte], height: Height): Key[ByteStr] =
    Key(keyTag, hBytes(suffix, height), ByteStr(_), _.arr)

  def dcapRootCaCrlHistory: Key[Seq[Height]]      = historyKey(DcapRootCaCrlHistory, Array.emptyByteArray)
  def dcapRootCaCrl(height: Height): Key[ByteStr] = dcapCollateralValue(DcapRootCaCrl, Array.emptyByteArray, height)

  def dcapPckCrlHistory: Key[Seq[Height]]      = historyKey(DcapPckCrlHistory, Array.emptyByteArray)
  def dcapPckCrl(height: Height): Key[ByteStr] = dcapCollateralValue(DcapPckCrl, Array.emptyByteArray, height)

  def dcapQeIdentityHistory: Key[Seq[Height]]      = historyKey(DcapQeIdentityHistory, Array.emptyByteArray)
  def dcapQeIdentity(height: Height): Key[ByteStr] = dcapCollateralValue(DcapQeIdentity, Array.emptyByteArray, height)

  def dcapTcbSigningIssuerChainHistory: Key[Seq[Height]] = historyKey(DcapTcbSigningIssuerChainHistory, Array.emptyByteArray)
  def dcapTcbSigningIssuerChain(height: Height): Key[ByteStr] =
    dcapCollateralValue(DcapTcbSigningIssuerChain, Array.emptyByteArray, height)

  // Keyed per FMSPC (platform model): only the FMSPCs actually seen need an entry, not a global Intel-wide table.
  def dcapTcbInfoHistory(fmspc: ByteStr): Key[Seq[Height]]      = historyKey(DcapTcbInfoHistory, fmspc.arr)
  def dcapTcbInfo(fmspc: ByteStr)(height: Height): Key[ByteStr] = dcapCollateralValue(DcapTcbInfo, fmspc.arr, height)

  // Which FMSPCs got a TCB Info update at this height, so rollback knows which per-FMSPC histories to unwind
  // without an unbounded scan - the same role assetsWithMinFee plays for assetMinFeeHistory above.
  def dcapTcbInfoFmspcsAt(height: Height): Key[Seq[ByteStr]] =
    Key(DcapTcbInfoFmspcsAtHeight, h(height), readByteStrSeq, writeByteStrSeq)

  def dcapPckCaIssuerChainHistory: Key[Seq[Height]] = historyKey(DcapPckCaIssuerChainHistory, Array.emptyByteArray)
  def dcapPckCaIssuerChain(height: Height): Key[ByteStr] =
    dcapCollateralValue(DcapPckCaIssuerChain, Array.emptyByteArray, height)

  // ReserveTransaction's accumulated total, keyed by (sender, miner, asset) - not tied to a generation period, so
  // it reuses the DCAP-collateral-style "single current value, resolved through history" mechanism (see
  // dcapCollateralValue above) rather than committedGenerators/registeredEnclaves' period-keyed one.
  def reservedAmountSuffix(sender: Address, miner: Address, asset: Asset): ByteStr =
    ByteStr(sender.toBytes ++ miner.toBytes ++ asset.compatId.fold(Array.emptyByteArray)(_.arr))

  def reservedAmountHistory(suffix: ByteStr): Key[Seq[Height]] = historyKey(ReservedAmountHistory, suffix.arr)
  def reservedAmount(suffix: ByteStr)(height: Height): Key[Long] =
    Key(ReservedAmount, hBytes(suffix.arr, height), Longs.fromByteArray, Longs.toByteArray)

  // Which (sender, miner, asset) suffixes changed at this height, so rollback knows which histories to unwind
  // without an unbounded scan - the same role dcapTcbInfoFmspcsAt plays for dcapTcbInfoHistory.
  def reservedAmountKeysAt(height: Height): Key[Seq[ByteStr]] =
    Key(ReservedAmountKeysAtHeight, h(height), readByteStrSeq, writeByteStrSeq)

  // BindApiKeyTransaction's HPKE-sealed API key envelope, keyed by (enclavePublicKey, sender) so an enclave can
  // eventually enumerate the bindings addressed to it. Same history mechanism as reservedAmount above.
  def apiKeyBindingSuffix(enclavePublicKey: ByteStr, sender: Address): ByteStr =
    ByteStr(enclavePublicKey.arr ++ sender.toBytes)

  def apiKeyBindingHistory(suffix: ByteStr): Key[Seq[Height]] = historyKey(ApiKeyBindingHistory, suffix.arr)
  def apiKeyBinding(suffix: ByteStr)(height: Height): Key[ByteStr] =
    Key(ApiKeyBinding, hBytes(suffix.arr, height), ByteStr(_), _.arr)

  def apiKeyBindingKeysAt(height: Height): Key[Seq[ByteStr]] =
    Key(ApiKeyBindingKeysAtHeight, h(height), readByteStrSeq, writeByteStrSeq)
}
