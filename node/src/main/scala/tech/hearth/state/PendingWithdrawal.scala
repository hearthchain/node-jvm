package tech.hearth.state

/** A WithdrawTransaction waiting for its payout: `amount` of a reservation to return to the client at `payoutAt`,
  * the first block of the period after the one following the period the Withdraw landed in.
  *
  * The delay is what lets a miner settle work it accepted before it saw the Withdraw: until `payoutAt` the reservation
  * stays open to Settle in full, and the payout only returns what is still unsettled then (see WithdrawalPayout).
  */
case class PendingWithdrawal(amount: Long, payoutAt: Height)
