package com.wavesplatform.state

import com.wavesplatform.settings.Constants

package object diffs {

  /** Enough for any one test account, while leaving room for plenty of them in the same genesis snapshot.
    *
    * It used to be Long.MaxValue / 3, which overflows the total as soon as four accounts are funded with it - a chain
    * only ever holds Constants.TotalWaves, and GenesisSnapshot rejects a total that doesn't fit in a Long. This is
    * 100 000 waves: two orders of magnitude above the minimal generating balance, and a thousandth of the supply.
    */
  val ENOUGH_AMT: Long = Constants.UnitsInWave * Constants.TotalWaves / 1000

  def produceRejectOrFailedDiff(errorMessage: String, requireFailed: Boolean = false): SnapshotProduceError =
    new SnapshotProduceError(errorMessage, requireFailed)
}
