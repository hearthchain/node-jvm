package tech.hearth.transaction.validation.impl

import tech.hearth.state.GenesisBlockHeight
import tech.hearth.transaction.StakeTransaction
import tech.hearth.transaction.TxValidationError.GenericError
import tech.hearth.transaction.validation.*

object StakeTxValidator extends TxValidator[StakeTransaction] {
  override def validate(tx: StakeTransaction): ValidatedV[StakeTransaction] =
    // The real check is StakeTransactionDiff's - periodStart has to be the *next* period's start, which needs chain
    // state. All that can be said without it is that no period starts below genesis, which rejects the one value
    // (0, the protobuf default for an omitted period_start) that a client can send by simply forgetting the field.
    V.seq(tx)(
      V.cond(tx.periodStart >= GenesisBlockHeight, GenericError(s"periodStart must be at least $GenesisBlockHeight"))
    )
}
