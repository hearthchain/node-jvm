package tech.hearth.state

/** The HRTH a stake locks: the larger of the stake in force for the current period and the latest one set, which is
  * what the next period will have. A raise locks at once; a cut or a release unlocks only when the period ends.
  *
  * The one definition both Blockchain.lockedStake (the tip) and RocksDBWriter's balance snapshots (every height
  * before it) resolve through, so that forging weight cannot disagree across the liquid block.
  */
object StakeLock {
  def apply(inForce: Long, latestSet: Long): Long = math.max(inForce, latestSet)
}
