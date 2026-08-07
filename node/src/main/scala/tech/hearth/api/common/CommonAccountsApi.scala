package tech.hearth.api.common

import tech.hearth.account.Address
import tech.hearth.api.common.AddressPortfolio.assetBalanceIterator
import tech.hearth.api.common.lease.AddressLeaseInfo
import tech.hearth.common.state.ByteStr
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
  final case class BalanceDetails(regular: Long, generating: Long, available: Long, effective: Long, leaseIn: Long, leaseOut: Long)

  def apply(
      compositeBlockchain: () => SnapshotBlockchain,
      rdb: RDB,
      blockchain: Blockchain
  ): CommonAccountsApi = new CommonAccountsApi {

    override def balance(address: Address, confirmations: Int = 0): Long =
      blockchain.regularBalance(address, blockchain.height, confirmations)

    override def effectiveBalance(address: Address, confirmations: Int = 0): Long = {
      blockchain.effectiveBalance(address, confirmations)
    }

    override def balanceDetails(address: Address): Either[String, BalanceDetails] = {
      val portfolio = blockchain.wavesPortfolio(address)
      val isBanned  = blockchain.hasBannedEffectiveBalance(address)
      portfolio
        .effectiveBalance(isBanned)
        .map(effectiveBalance =>
          BalanceDetails(
            portfolio.balance,
            blockchain.generatingBalance(address),
            portfolio.balance - portfolio.generationDeposit - portfolio.lease.out,
            effectiveBalance,
            portfolio.lease.in,
            portfolio.lease.out
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
