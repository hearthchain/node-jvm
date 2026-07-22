package com.wavesplatform.transaction

enum TransactionType {
  def id: Int                 = ordinal + 1
  def transactionName: String = s"${this.toString}Transaction"

  case Genesis, Transfer, Exchange, Lease, LeaseCancel, MassTransfer, CommitToGeneration

}

object TransactionType {
  def fromId(id: Byte): TransactionType = TransactionType.fromOrdinal(id - 1)
}
