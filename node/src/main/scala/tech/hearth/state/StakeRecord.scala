package tech.hearth.state

/** One address's HRTH stake, as two amounts rather than one.
  *
  * `active` is what the address has staked for the generation period the chain is currently in - the amount its
  * share of that period's Cred issuance is computed from at the period's end. `pending` is what
  * [[tech.hearth.transaction.StakeTransaction]] set for the *next* period; it becomes `active` when that period
  * starts (see [[activated]]), and stays in place for every later period until another StakeTransaction replaces
  * it. Several StakeTransactions within one period simply overwrite `pending`, which is what makes "the last one
  * wins" fall out with no extra bookkeeping.
  *
  * Keeping both is what lets one stored value express the two halves of the locking rule: HRTH is reserved as soon
  * as a stake is raised, but a reduction only frees funds when the next period starts. [[locked]] is the maximum of
  * the two, so raising the stake locks the new, larger amount immediately, while lowering it keeps the old, larger
  * amount locked until [[activated]] runs at the boundary and the two converge.
  */
case class StakeRecord(active: Long, pending: Long) {

  /** The HRTH this address cannot spend, and which does not count toward its generating balance. */
  def locked: Long = math.max(active, pending)

  def isEmpty: Boolean = active == 0 && pending == 0

  /** The record as of the start of the next generation period: what was pending is now active, and stays pending so
    * that a stake nobody has changed keeps earning in every later period.
    */
  def activated: StakeRecord = StakeRecord(pending, pending)
}

object StakeRecord {
  val empty: StakeRecord = StakeRecord(0L, 0L)
}
