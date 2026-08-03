package tech.hearth

import tech.hearth.block.Block
import tech.hearth.lang.ValidationError
import tech.hearth.state.BlockchainUpdaterImpl.BlockApplyResult
import tech.hearth.state.{Blockchain, StateSnapshot}
import tech.hearth.transaction.Transaction

package object mining {
  private[mining] def createConstConstraint(maxSize: Long, transactionSize: => Long, description: String) = OneDimensionalMiningConstraint(
    maxSize,
    new tech.hearth.mining.TxEstimators.Fn {
      override def apply(b: Blockchain, t: Transaction, s: StateSnapshot): Long = transactionSize
      override val minEstimate                                                  = transactionSize
      override val toString: String                                             = s"const($transactionSize)"
    },
    description
  )

  type Appender = Block => Either[ValidationError, BlockApplyResult]
}
