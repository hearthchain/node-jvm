package tech.hearth.state

import tech.hearth.account.Address

/** One address's HRTH stake, as it stands for some generation period.
  *
  * A stake is a balance: a per-address amount that changes at a height and stays put until it changes again. The
  * period it applies to is not stored, it is *derived* from the height the change landed at - a StakeTransaction
  * always names the period after the one it is applied in, so an amount written at height `h` is in force for
  * every period starting above `h`. See `Blockchain.stakeAt`.
  *
  * That is why nothing carries a stake forward: it was never filed under a period to begin with.
  */
case class Stake(address: Address, amount: Long)

/** A stake as a transaction declares it: [[tech.hearth.transaction.StakeTransaction]]'s own two fields, plus who
  * sent it. This is what a [[StateSnapshot]] carries and what the state hash commits to, in the same way
  * [[GenerationCommitment]] carries a CommitToGeneration's fields rather than the deposit they imply.
  *
  * `periodStart` is redundant with the height this lands at - the storage layer records only the amount and the
  * height - but it is what the sender signed, so it is kept in the preimage and validated rather than inferred.
  */
case class StakeCommitment(address: Address, periodStart: Height, amount: Long)
