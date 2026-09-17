package tech.hearth.state

import tech.hearth.account.Address

/** One address's HRTH stake for one generation period, as stored.
  *
  * The period is the key this is filed under, not a field: `Blockchain.stakes(period)` is the whole set staked for
  * that period, which is also the set [[StakingPayout]] walks to pay out. An amount of 0 is a real record - it is
  * how a release is expressed, and it is what stops an earlier period's stake being carried forward over it.
  */
case class Stake(address: Address, amount: Long)

object Stake {

  /** A period's stakes with `restated` applied over them, a later entry for an address replacing an earlier one.
    *
    * This is "the last Stake transaction of a period wins", and it has to hold identically in three places that
    * resolve a period independently: RocksDBWriter.loadStakes folding what is on disk, SnapshotBlockchain layering
    * a not-yet-persisted snapshot over it, and Caches keeping a warm period up to date. Several transactions in one
    * block all land in the same snapshot, so deduping only on the way to disk is not enough.
    *
    * A restated address keeps its first-seen position rather than moving to the end, so the resulting order depends
    * only on the entries themselves - it is consensus state, and it is what StakingPayout walks.
    */
  def applied(base: IndexedSeq[Stake], restated: Iterable[StakeCommitment]): IndexedSeq[Stake] =
    if (restated.isEmpty) base
    else {
      val latest = scala.collection.mutable.LinkedHashMap.empty[Address, Long]
      restated.foreach(stake => latest.put(stake.address, stake.amount))
      base.filterNot(stake => latest.contains(stake.address)) ++ latest.map(Stake(_, _))
    }
}

/** A stake as a transaction declares it: [[tech.hearth.transaction.StakeTransaction]]'s own two fields, plus who
  * sent it. This is what a [[StateSnapshot]] carries and what the state hash commits to, in the same way
  * [[GenerationCommitment]] carries a CommitToGeneration's fields rather than the deposit they imply.
  *
  * `periodStart` is kept rather than derived because it is what the sender signed: it says which period this stake
  * is for, independently of where in the chain the transaction happens to land, and it is the key the storage
  * layer files the record under. [[StakingPayout]]'s carry-forward emits these too, naming the period it is
  * carrying a stake into.
  */
case class StakeCommitment(address: Address, periodStart: Height, amount: Long)
