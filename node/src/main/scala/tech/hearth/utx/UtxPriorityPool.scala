package tech.hearth.utx

import tech.hearth.common.state.ByteStr
import tech.hearth.state.StateSnapshot
import tech.hearth.transaction.Transaction

final class UtxPriorityPool {

  @volatile private var priorityTxIds = Seq.empty[ByteStr]

  def priorityTransactionIds: Seq[ByteStr] = priorityTxIds

  private[utx] def setPriorityDiffs(discDiffs: Seq[StateSnapshot]): Set[Transaction] = {
    priorityTxIds = discDiffs.flatMap(_.transactions.keys)
    discDiffs.flatMap(_.transactions.values.map(_.transaction)).toSet
  }
}
