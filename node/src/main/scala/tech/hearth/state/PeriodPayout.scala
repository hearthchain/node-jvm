package tech.hearth.state

import tech.hearth.account.Address
import tech.hearth.transaction.Asset.IssuedAsset

/** What one finished period paid its stakers, as [[StakingPayout]] computed it. See "Reading payouts back" in
  * docs/notes/cred-economy.md.
  *
  * @param issued
  *   the period's total tracked work, which is what was split.
  * @param payouts
  *   one entry per staker, including those whose share floored to zero.
  */
case class PeriodPayout(period: GenerationPeriod, asset: IssuedAsset, issued: Long, totalStaked: BigInt, payouts: Seq[StakerPayout]) {
  def credited: Long = payouts.map(_.credited).sum
}

case class StakerPayout(address: Address, staked: Long, credited: Long)

/** One address's line of a [[PeriodPayout]]. */
case class AddressPayout(period: GenerationPeriod, asset: IssuedAsset, staked: Long, credited: Long)
