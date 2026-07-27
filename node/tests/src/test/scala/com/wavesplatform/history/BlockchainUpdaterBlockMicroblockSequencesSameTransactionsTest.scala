package com.wavesplatform.history

import com.wavesplatform.block.Block
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2.*
import com.wavesplatform.db.WithState.AddrWithBalance
import com.wavesplatform.history.Domain.BlockchainUpdaterExt
import com.wavesplatform.state.diffs.*
import com.wavesplatform.test.*
import com.wavesplatform.transaction.*
import com.wavesplatform.transaction.transfer.*
import org.scalacheck.Gen
import tech.hearth.crypto.SigningKey

class BlockchainUpdaterBlockMicroblockSequencesSameTransactionsTest extends PropSpec with DomainScenarioDrivenPropertyCheck {

  import BlockchainUpdaterBlockMicroblockSequencesSameTransactionsTest.*

  property("resulting miner balance should not depend on tx distribution among blocks and microblocks") {
    // The accounts are credited by the genesis snapshot, so the sequences are chained onto the domain's genesis block
    forAll(g(100, 5)) { case (balances, miner, payments, intSeqs, ts) =>
      val finalMinerBalances = intSeqs.map { intSeq =>
        withDomain(MicroblocksActivatedAt0WavesSettings, balances) { d =>
          val bmb  = r(payments, intSeq, d.lastBlockId, miner, ts)
          val last = customBuildBlockOfTxs(bestRef(bmb.last), Seq.empty, miner, ts)
          bmb.foreach { case (b, mbs) =>
            d.blockchainUpdater.processBlock(b) should beRight
            mbs.foreach(mb => d.blockchainUpdater.processMicroBlock(mb, None) should beRight)
          }
          d.blockchainUpdater.processBlock(last)
          d.balance(last.header.generator.toAddress)
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
      payment: TransferTransaction = createWavesTransfer(master, master.toAddress, amt, fee, ts).explicitGet()
    } yield (master, miner, payment, ts)
    scenario(
      preconditionsAndPayments,
      MicroblocksActivatedAt0WavesSettings,
      s => Seq(AddrWithBalance(s._1.toAddress, ENOUGH_AMT))
    ) { case (domain, (master, miner, payment, ts)) =>
      val (base, micros) = chainBaseAndMicro(domain.lastBlockId, Seq.empty, Seq(Seq(payment)), miner, ts)
      val emptyBlock     = customBuildBlockOfTxs(micros.last.totalResBlockSig, Seq.empty, miner, ts)
      domain.blockchainUpdater.processBlock(base) should beRight
      domain.blockchainUpdater.processMicroBlock(micros.head, None) should beRight
      domain.blockchainUpdater.processBlock(emptyBlock) should beRight

      domain.balance(miner.toAddress) shouldBe payment.fee.value
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
          .map(step => createWavesTransfer(master, master.toAddress, amt, fee, ts + step).explicitGet())
          .grouped(microBlockCount)
          .toSeq
      } yield (master, miner, microBlockTxs, ts)
    scenario(
      preconditionsAndPayments,
      MicroblocksActivatedAt0WavesSettings,
      s => Seq(AddrWithBalance(s._1.toAddress, ENOUGH_AMT))
    ) { case (domain, (_, miner, microBlockTxs, ts)) =>
      val (base, micros) = chainBaseAndMicro(domain.lastBlockId, Seq.empty, microBlockTxs, miner, ts)
      val emptyBlock     = customBuildBlockOfTxs(micros.last.totalResBlockSig, Seq.empty, miner, ts)
      domain.blockchainUpdater.processBlock(base) should beRight
      micros.foreach(domain.blockchainUpdater.processMicroBlock(_, None) should beRight)
      domain.blockchainUpdater.processBlock(emptyBlock) should beRight

      domain.rocksDBWriter.lastBlock.get.transactionData shouldBe microBlockTxs.flatten
    }
  }

  def randomPayment(accs: Seq[SigningKey], ts: Long): Gen[TransferTransaction] =
    for {
      from <- Gen.oneOf(accs)
      to   <- Gen.oneOf(accs)
      fee  <- smallFeeGen
      amt  <- smallFeeGen
    } yield createWavesTransfer(from, to.toAddress, amt, fee, ts).explicitGet()

  def randomPayments(accs: Seq[SigningKey], ts: Long, amt: Int): Gen[Seq[TransferTransaction]] =
    if (amt == 0)
      Gen.const(Seq.empty)
    else
      for {
        h <- randomPayment(accs, ts)
        t <- randomPayments(accs, ts + 1, amt - 1)
      } yield h +: t

  val TOTAL_WAVES = ENOUGH_AMT

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
      (accs, miner, accs.map(acc => AddrWithBalance(acc.toAddress, TOTAL_WAVES / 4)), ts)
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

  type BlockAndMicroblockSize     = (Int, Seq[Int])
  type BlockAndMicroblockSizes    = Seq[BlockAndMicroblockSize]
  type BlockAndMicroblocks        = (Block, Seq[MicroBlockWithTotalId])
  type BlockAndMicroblockSequence = Seq[BlockAndMicroblocks]

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

  def take(txs: Seq[Transaction], sizes: BlockAndMicroblockSize): ((Seq[Transaction], Seq[Seq[Transaction]]), Seq[Transaction]) = {
    val (blockAmt, microsAmts) = sizes
    val (blockTxs, rest)       = txs.splitAt(blockAmt)
    val (reversedMicroblockTxs, res) = microsAmts.foldLeft((Seq.empty[Seq[Transaction]], rest)) { case ((acc, pool), amt) =>
      val (step, next) = pool.splitAt(amt)
      (step +: acc, next)
    }
    ((blockTxs, reversedMicroblockTxs.reverse), res)
  }

  def stepR(
      txs: Seq[Transaction],
      sizes: BlockAndMicroblockSize,
      prev: ByteStr,
      signer: SigningKey,
      timestamp: Long
  ): (BlockAndMicroblocks, Seq[Transaction]) = {
    val ((blockTxs, microblockTxs), rest) = take(txs, sizes)
    (chainBaseAndMicro(prev, blockTxs, microblockTxs, signer, timestamp), rest)
  }

  def bestRef(r: BlockAndMicroblocks): ByteStr = r._2.lastOption match {
    case Some(mb) => mb.totalBlockId
    case None     => r._1.id()
  }

  def r(
      txs: Seq[Transaction],
      sizes: BlockAndMicroblockSizes,
      initial: ByteStr,
      signer: SigningKey,
      timestamp: Long
  ): BlockAndMicroblockSequence = {
    sizes
      .foldLeft((Seq.empty[BlockAndMicroblocks], txs)) { case ((acc, rest), s) =>
        val prev         = acc.headOption.map(bestRef).getOrElse(initial)
        val (step, next) = stepR(rest, s, prev, signer, timestamp)
        (step +: acc, next)
      }
      ._1
      .reverse
  }
}
