package tech.hearth.transaction

enum TransactionType {
  def id: Int                 = ordinal + 1
  def transactionName: String = s"${this.toString}Transaction"

  case Genesis, Transfer, Exchange, Lease, LeaseCancel, CommitToGeneration, StartBoost, Reserve, BindApiKey, Settle, Withdraw,
    UpdateCollateral

}

object TransactionType {
  def fromId(id: Byte): TransactionType = TransactionType.fromOrdinal(id - 1)
}
