package tech.hearth.history

import tech.hearth.common.utils.EitherExt2.*
import tech.hearth.db.WithState.AddrWithBalance
import tech.hearth.settings.HearthSettings
import tech.hearth.state.diffs.*
import tech.hearth.test.*
import tech.hearth.transaction.*
import tech.hearth.transaction.transfer.*
import org.scalacheck.Gen
import tech.hearth.crypto.SigningKey

class BlockchainUpdaterBlockMicroblockSequencesSameTransactionsTest extends PropSpec with DomainScenarioDrivenPropertyCheck {

  import BlockchainUpdaterBlockMicroblockSequencesSameTransactionsTest.*

  /** The miner is a committed generator - nothing else may produce a block - funded with exactly its generation
    * deposit, so that what it holds beyond that is exactly what it has earned. The reward is zeroed for the same
    * reason: these properties are about fees.
    */
  private val settings: HearthSettings = {
    val bs = MicroblocksActivatedAt0HearthSettings.blockchainSettings
    MicroblocksActivatedAt0HearthSettings.copy(blockchainSettings = bs.copy(rewardsSettings = withFlatReward(bs.rewardsSettings, 0)))
  }

  private def minerDeposit(miner: SigningKey): AddrWithBalance =
    AddrWithBalance(miner.toAddress, CommitToGenerationTransaction.DepositInEmbers)

  /** Appends the sequence the sizes describe, as blocks and the micro blocks extending them. Built one at a time
    * through the domain, because each block's proof and state hash depend on the state the previous one left.
    */
  private def appendSequence(d: Domain, txs: Seq[Transaction], sizes: BlockAndMicroblockSizes, miner: SigningKey, ts: Long): Unit =
    sizes.foldLeft(txs) { case (rest, (blockAmt, microAmts)) =>
      val (blockTxs, afterBlock) = rest.splitAt(blockAmt)
      d.appendBlockAt(ts, miner)(blockTxs*)
      microAmts.foldLeft(afterBlock) { case (pool, amt) =>
        val (microTxs, next) = pool.splitAt(amt)
        d.appendMicroBlockBy(miner)(microTxs*)
        next
      }
    }

  property("resulting miner balance should not depend on tx distribution among blocks and microblocks") {
    // The accounts are credited by the genesis snapshot, so the sequences are chained onto the domain's genesis block
    forAll(g(100, 5)) { case (balances, miner, payments, intSeqs, ts) =>
      val finalMinerBalances = intSeqs.map { intSeq =>
        withDomain(settings, balances :+ minerDeposit(miner), generators = Seq(miner)) { d =>
          appendSequence(d, payments, intSeq, miner, ts)
          // One more block, so that the carry of the last one is credited too
          d.appendBlockAt(ts, miner)()
          d.balance(miner.toAddress)
        }
      }
      finalMinerBalances.toSet.size shouldBe 1
    }
  }

  property("Miner fee from microblock [Genesis] <- [Empty] <~ (Micro with tx) <- [Empty]") {
    val preconditionsAndPayments: Gen[(SigningKey, SigningKey, TransferTransaction, Int)] = for {
      master <- accountGen
      miner  <- accountGen
      ts     <- positiveIntGen
      fee    <- smallFeeGen
      amt    <- smallFeeGen
      payment: TransferTransaction = createHearthTransfer(master, master.toAddress, amt, fee, ts).explicitGet()
    } yield (master, miner, payment, ts)
    scenario(
      preconditionsAndPayments,
      settings,
      s => Seq(AddrWithBalance(s._1.toAddress, ENOUGH_AMT), minerDeposit(s._2)),
      s => Seq(s._2)
    ) { case (domain, (master, miner, payment, ts)) =>
      domain.appendBlockAt(ts, miner)()
      domain.appendMicroBlockBy(miner)(payment)
      domain.appendBlockAt(ts, miner)()

      // 40% of the fee credited in the block the micro extends, the 60% carry in the one after it
      domain.balance(miner.toAddress) shouldBe (CommitToGenerationTransaction.DepositInEmbers + payment.fee.value)
      domain.balance(master.toAddress) shouldBe (ENOUGH_AMT - payment.fee.value)
    }
  }

  property("Microblock tx sequence") {
    val txCount         = 10
    val microBlockCount = 10
    val preconditionsAndPayments: Gen[(SigningKey, SigningKey, Seq[Seq[TransferTransaction]], Int)] =
      for {
        master <- accountGen
        miner  <- accountGen
        ts     <- positiveIntGen
        fee    <- smallFeeGen
        amt    <- smallFeeGen
        microBlockTxs = (1 to txCount * microBlockCount)
          .map(step => createHearthTransfer(master, master.toAddress, amt, fee, ts + step).explicitGet())
          .grouped(microBlockCount)
          .toSeq
      } yield (master, miner, microBlockTxs, ts)
    scenario(
      preconditionsAndPayments,
      settings,
      s => Seq(AddrWithBalance(s._1.toAddress, ENOUGH_AMT), minerDeposit(s._2)),
      s => Seq(s._2)
    ) { case (domain, (_, miner, microBlockTxs, ts)) =>
      domain.appendBlockAt(ts, miner)()
      microBlockTxs.foreach(txs => domain.appendMicroBlockBy(miner)(txs*))
      domain.appendBlockAt(ts, miner)()

      domain.rocksDBWriter.lastBlock.get.transactionData shouldBe microBlockTxs.flatten
    }
  }

  def randomPayment(accs: Seq[SigningKey], ts: Long): Gen[TransferTransaction] =
    for {
      from <- Gen.oneOf(accs)
      to   <- Gen.oneOf(accs)
      fee  <- smallFeeGen
      amt  <- smallFeeGen
    } yield createHearthTransfer(from, to.toAddress, amt, fee, ts).explicitGet()

  def randomPayments(accs: Seq[SigningKey], ts: Long, amt: Int): Gen[Seq[TransferTransaction]] =
    if (amt == 0)
      Gen.const(Seq.empty)
    else
      for {
        h <- randomPayment(accs, ts)
        t <- randomPayments(accs, ts + 1, amt - 1)
      } yield h +: t

  val TOTAL_HRTH = ENOUGH_AMT

  def accsAndGenesis(): Gen[(Seq[SigningKey], SigningKey, Seq[AddrWithBalance], Int)] =
    for {
      alice   <- accountGen
      bob     <- accountGen
      charlie <- accountGen
      dave    <- accountGen
      miner   <- accountGen
      ts      <- positiveIntGen
    } yield {
      val accs = Seq(alice, bob, charlie, dave)
      (accs, miner, accs.map(acc => AddrWithBalance(acc.toAddress, TOTAL_HRTH / 4)), ts)
    }

  def g(totalTxs: Int, totalScenarios: Int): Gen[(Seq[AddrWithBalance], SigningKey, Seq[TransferTransaction], Seq[BlockAndMicroblockSizes], Int)] =
    for {
      (accs, miner, balances, ts)           <- accsAndGenesis()
      payments: Seq[TransferTransaction]    <- randomPayments(accs, ts, totalTxs)
      intSeqs: Seq[BlockAndMicroblockSizes] <- randomSequences(totalTxs, totalScenarios)
    } yield (balances, miner, payments, intSeqs, ts)
}

object BlockchainUpdaterBlockMicroblockSequencesSameTransactionsTest {

  def genSizes(total: Int): Gen[Seq[Int]] =
    for {
      h <- Gen.choose(1, total)
      t <- if (h < total) genSizes(total - h) else Gen.const(Seq.empty)
    } yield h +: t

  def genSplitSizes(total: Int): Gen[(Int, Seq[Int])] = genSizes(total).map(s => s.head -> s.tail)

  type BlockAndMicroblockSize  = (Int, Seq[Int])
  type BlockAndMicroblockSizes = Seq[BlockAndMicroblockSize]

  def randomSizeSequence(total: Int): Gen[BlockAndMicroblockSizes] =
    for {
      totalStep <- Gen.choose(1, Math.min(Math.min(total / 3 + 2, total), 250))
      h         <- genSplitSizes(totalStep)
      t         <- if (totalStep < total) randomSizeSequence(total - totalStep) else Gen.const(Seq.empty)
    } yield h +: t

  def randomSequences(total: Int, sequences: Int): Gen[Seq[BlockAndMicroblockSizes]] =
    if (sequences == 0)
      Gen.const(Seq.empty)
    else
      for {
        h <- randomSizeSequence(total)
        t <- randomSequences(total, sequences - 1)
      } yield h +: t
}
