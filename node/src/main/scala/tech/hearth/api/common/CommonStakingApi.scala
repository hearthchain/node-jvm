package tech.hearth.api.common

import com.google.common.primitives.Ints
import tech.hearth.account.Address
import tech.hearth.database.{DBExt, Keys, RDB}
import tech.hearth.state.{AddressPayout, Blockchain, GenerationPeriod, Height, NG, PeriodPayout}

/** Staking payouts as they were paid, read back from what the node recorded at each period boundary rather than
  * recomputed: the candidate index a payout enumerates stakers from is pruned at the next boundary, so a past period's
  * total stake cannot be rebuilt from current state.
  */
trait CommonStakingApi {

  /** What `period` paid its stakers; `None` when it paid nothing or has not ended yet. */
  def payout(period: GenerationPeriod): Option[PeriodPayout]

  /** What `address` was paid for every period starting within `[from, to]`, oldest first. */
  def payouts(address: Address, from: Height, to: Height): Seq[AddressPayout]
}

object CommonStakingApi {
  def apply(rdb: RDB, blockchain: Blockchain & NG): CommonStakingApi = new CommonStakingApi {
    // The block that pays a period stays liquid until the next key block, and only then reaches storage
    private def liquidPayout: Option[PeriodPayout] = blockchain.bestLiquidSnapshot.flatMap(_.stakingPayout)

    override def payout(period: GenerationPeriod): Option[PeriodPayout] =
      liquidPayout.filter(_.period == period).orElse(rdb.db.get(Keys.stakingPayout(period.start, rdb.apiHandle)))

    override def payouts(address: Address, from: Height, to: Height): Seq[AddressPayout] = {
      // The liquid block pays the latest period, so its line, if any, goes last
      val liquid = for {
        payout <- liquidPayout.toSeq if payout.period.start >= from && payout.period.start <= to
        p      <- payout.payouts if p.address == address
      } yield AddressPayout(payout.period, payout.asset, p.staked, p.credited)

      stored(address, from, to) ++ liquid
    }

    private def stored(address: Address, from: Height, to: Height): Seq[AddressPayout] =
      rdb.db.readOnly { ro =>
        ro.get(Keys.addressId(address)).toSeq.flatMap { addressId =>
          val key    = Keys.addressStakingPayout(addressId, from, rdb.apiHandle)
          val prefix = key.keyBytes.dropRight(Ints.BYTES) // the period start
          val stored = Seq.newBuilder[AddressPayout]
          ro.iterateOverWithSeek(prefix, key.keyBytes, Some(rdb.apiHandle.handle)) { entry =>
            val payout = key.parse(entry.getValue)
            payout.filter(_.period.start <= to).foreach(stored += _)
            payout.exists(_.period.start < to)
          }
          stored.result()
        }
      }
  }
}
