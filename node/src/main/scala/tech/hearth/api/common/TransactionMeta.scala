package tech.hearth.api.common

import tech.hearth.state.{Height, TxMeta}
import tech.hearth.transaction.Transaction

sealed trait TransactionMeta {
  def height: Height
  def transaction: Transaction
  def status: TxMeta.Status
  def spentComplexity: Long
}

object TransactionMeta {

  def unapply(tm: TransactionMeta): Option[(Height, Transaction, TxMeta.Status)] =
    Some((tm.height, tm.transaction, tm.status))

  def create(
      height: Height,
      transaction: Transaction,
      status: TxMeta.Status,
      spentComplexity: Long
  ): TransactionMeta =
    transaction match {

      case _ =>
        Default(height, transaction, status, spentComplexity)
    }

  final case class Default(height: Height, transaction: Transaction, status: TxMeta.Status, spentComplexity: Long) extends TransactionMeta
}
