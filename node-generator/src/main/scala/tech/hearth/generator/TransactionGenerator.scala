package tech.hearth.generator

import tech.hearth.transaction.Transaction

trait TransactionGenerator extends Iterator[Iterator[Transaction]] {
  override val hasNext              = true
  def initial: Seq[Transaction]     = Seq.empty
  def tailInitial: Seq[Transaction] = Seq.empty
}
