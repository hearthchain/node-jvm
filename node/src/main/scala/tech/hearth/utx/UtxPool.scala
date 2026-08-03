package tech.hearth.utx

import tech.hearth.common.state.ByteStr
import tech.hearth.lang.ValidationError
import tech.hearth.mining.{MiningConstraint, MultiDimensionalMiningConstraint}
import tech.hearth.state.StateSnapshot
import tech.hearth.transaction.*
import tech.hearth.transaction.smart.script.trace.TracedResult
import tech.hearth.utx.UtxPool.PackStrategy

import scala.concurrent.duration.FiniteDuration

trait UtxForAppender {
  def setPrioritySnapshots(snapshots: Seq[StateSnapshot]): Unit
}

trait UtxPool extends UtxForAppender with AutoCloseable {
  def putIfNew(tx: Transaction, forceValidate: Boolean = false): TracedResult[ValidationError, Boolean]
  def removeAll(txs: Iterable[Transaction]): Unit
  def all: Seq[Transaction]
  def size: Int
  def transactionById(transactionId: ByteStr): Option[Transaction]
  def addAndScheduleCleanup(transactions: Iterable[Transaction]): Unit
  def scheduleCleanup(): Unit
  def packUnconfirmed(
      rest: MultiDimensionalMiningConstraint,
      prevStateHash: Option[ByteStr],
      strategy: PackStrategy = PackStrategy.Unlimited,
      cancelled: () => Boolean = () => false
  ): (Option[Seq[Transaction]], MiningConstraint, Option[ByteStr])
  def resetPriorityPool(): Unit
  def cleanUnconfirmed(): Unit
  def getPriorityPool: Option[UtxPriorityPool]
}

object UtxPool {
  sealed trait PackStrategy
  object PackStrategy {
    case class Limit(time: FiniteDuration)    extends PackStrategy
    case class Estimate(time: FiniteDuration) extends PackStrategy
    case object Unlimited                     extends PackStrategy
  }
}
