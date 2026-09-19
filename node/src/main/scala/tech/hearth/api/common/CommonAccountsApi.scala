package tech.hearth.api.common

import tech.hearth.account.Address
import tech.hearth.api.common.AddressPortfolio.assetBalanceIterator
import tech.hearth.api.common.lease.AddressLeaseInfo
import tech.hearth.common.state.ByteStr
import tech.hearth.consensus.GeneratingBalanceProvider
import tech.hearth.database.{DBExt, RDB}
import tech.hearth.state.{Blockchain, SnapshotBlockchain}
import tech.hearth.transaction.Asset.IssuedAsset
import monix.eval.Task
import monix.reactive.Observable

trait CommonAccountsApi {
  import CommonAccountsApi.*

  def balance(address: Address, confirmations: Int = 0): Long

  def effectiveBalance(address: Address, confirmations: Int = 0): Long

  def balanceDetails(address: Address): Either[String, BalanceDetails]

  def assetBalance(address: Address, asset: IssuedAsset): Long

  def portfolio(address: Address): Observable[Seq[(IssuedAsset, Long)]]

  def activeLeases(address: Address): Observable[LeaseInfo]

  def leaseInfo(leaseId: ByteStr): Option[LeaseInfo]
}

object CommonAccountsApi {
  final case class BalanceDetails(
      regular: Long,
      generating: Long,
      available: Long,
      effective: Long,
      leaseIn: Long,
      leaseOut: Long,
      staked: Long
  )

  def apply(
      compositeBlockchain: () => SnapshotBlockchain,
      rdb: RDB,
      blockchain: Blockchain
  ): CommonAccountsApi = new CommonAccountsApi {

    override def balance(address: Address, confirmations: Int = 0): Long =
      blockchain.regularBalance(address, blockchain.height, confirmations)

    // Deliberately not the same quantity as balanceDetails' `effective` below, and the two differ in two ways: this
    // one is the minimum over the generating-balance window and nets off only *this period's* stake, i.e. what the
    // node will actually let the address forge on, while `effective` is the unwindowed portfolio view and nets off
    // the whole lock. They coincide for an address that has never staked. blockId is passed explicitly because
    // unstakedEffectiveBalance defaults it to None, where this endpoint has always used the last block.
    override def effectiveBalance(address: Address, confirmations: Int = 0): Long =
      GeneratingBalanceProvider.unstakedEffectiveBalance(blockchain, address, confirmations, blockchain.lastBlockId)

    override def balanceDetails(address: Address): Either[String, BalanceDetails] = {
      val portfolio = blockchain.hearthPortfolio(address)
      val isBanned  = blockchain.hasBannedEffectiveBalance(address)
      portfolio
        .effectiveBalance(isBanned)
        .map(effectiveBalance =>
          BalanceDetails(
            portfolio.balance,
            blockchain.generatingBalance(address),
            // spendableBalance rather than the subtraction spelled out again: it is the one definition of what is
            // left after every lock, and it grew a third term (staked) alongside generationDeposit and lease.out.
            portfolio.spendableBalance,
            effectiveBalance,
            portfolio.lease.in,
            portfolio.lease.out,
            portfolio.staked
          )
        )
    }

    override def assetBalance(address: Address, asset: IssuedAsset): Long = blockchain.balance(address, asset)

    override def portfolio(address: Address): Observable[Seq[(IssuedAsset, Long)]] = {
      val compBlockchain = compositeBlockchain()
      rdb.db.resourceObservable.flatMap { resource =>
        Observable.fromIterator(Task(assetBalanceIterator(resource, address, compBlockchain.snapshot)))
      }
    }

    override def activeLeases(address: Address): Observable[LeaseInfo] =
      AddressLeaseInfo.activeLeases(rdb, compositeBlockchain().snapshot, address)

    def leaseInfo(leaseId: ByteStr): Option[LeaseInfo] =
      blockchain.leaseDetails(leaseId).map(LeaseInfo.fromLeaseDetails(leaseId, _))
  }
}
