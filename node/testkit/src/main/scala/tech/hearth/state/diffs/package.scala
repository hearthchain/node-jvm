package tech.hearth.state

import tech.hearth.settings.Constants

package object diffs {

  /** Enough for any one test account, while leaving room for plenty of them in the same genesis snapshot.
    *
    * It used to be Long.MaxValue / 3, which overflows the total as soon as four accounts are funded with it - a chain
    * only ever holds Constants.TotalHearth, and PredefinedSnapshot rejects a total that doesn't fit in a Long. This is
    * 100 000 hearth: two orders of magnitude above the minimal generating balance, and a thousandth of the supply.
    */
  val ENOUGH_AMT: Long = Constants.UnitsInHearth * Constants.TotalHearth / 1000

  def produceRejectOrFailedDiff(errorMessage: String, requireFailed: Boolean = false): SnapshotProduceError =
    new SnapshotProduceError(errorMessage, requireFailed)
}
