package tech.hearth.state

import tech.hearth.account.Address
import tech.hearth.state.TxMeta.Status
import tech.hearth.transaction.*
import tech.hearth.transaction.assets.*
import tech.hearth.transaction.assets.exchange.ExchangeTransaction
import tech.hearth.transaction.lease.*
import tech.hearth.transaction.transfer.*

case class NewTransactionInfo(
    transaction: Transaction,
    snapshot: StateSnapshot,
    affected: Set[Address],
    status: Status,
    spentComplexity: Long
)

object NewTransactionInfo {
  def create(
      tx: Transaction,
      status: Status,
      snapshot: StateSnapshot,
      blockchain: Blockchain
  ): NewTransactionInfo = {
    val affectedAddresses =
      if (status == Status.Elided)
        elidedAffectedAddresses(tx, blockchain)
      else
        snapshot.balances.keySet.map(_._1) ++
          snapshot.leaseBalances.keySet
    NewTransactionInfo(tx, snapshot, affectedAddresses, status, 0)
  }

  private def elidedAffectedAddresses(tx: Transaction, blockchain: Blockchain): Set[Address] =
    tx match {
      case t: ExchangeTransaction => Set(t.sender.toAddress, t.order1.sender.toAddress, t.order2.sender.toAddress)
      case t: LeaseCancelTransaction =>
        Set(t.sender.toAddress) ++ blockchain
          .leaseDetails(t.leaseId)
          .map(_.recipientAddress)
          .toSet
      case t: LeaseTransaction => Set(t.sender.toAddress, t.recipient)
      case t: MassTransferTransaction =>
        Set(t.sender.toAddress) ++ t.transfers.map(_.address)
      case t: TransferTransaction =>
        Set(t.sender.toAddress, t.recipient)
      case t: CommitToGenerationTransaction => Set(t.sender.toAddress)
      case _                                => Set.empty
    }
}
