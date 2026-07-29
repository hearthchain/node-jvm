package com.wavesplatform.mining

import com.wavesplatform.TestValues
import com.wavesplatform.block.Block
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2.*
import com.wavesplatform.db.WithDomain
import com.wavesplatform.db.WithState.AddrWithBalance
import com.wavesplatform.events.UtxEvent
import com.wavesplatform.mining.microblocks.MicroBlockMinerImpl
import com.wavesplatform.settings.TestFunctionalitySettings
import com.wavesplatform.state.{Blockchain, EndorsementStorage, StateSnapshot}
import com.wavesplatform.test.DomainPresets.RideV6
import com.wavesplatform.test.FlatSpec
import com.wavesplatform.transaction.TxHelpers.{defaultAddress, defaultSigner, secondAddress, transfer}
import com.wavesplatform.transaction.Transaction
import com.wavesplatform.utils.Schedulers
import com.wavesplatform.utx.{UtxPool, UtxPoolImpl, UtxPriorityPool}
import monix.execution.Scheduler
import monix.reactive.Observable
import monix.reactive.subjects.ConcurrentSubject

import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.concurrent.duration.*

class MicroBlockMinerSpec extends FlatSpec with WithDomain {
  "Micro block miner" should "generate microblocks in flat interval" in {
    val scheduler = Schedulers.singleThread("test")
    val acc       = TestValues.keyPair
    val settings  = domainSettingsWithFS(TestFunctionalitySettings.Enabled)
    withDomain(settings, Seq(AddrWithBalance(acc.toAddress, TestValues.bigMoney))) { d =>
      val utxPool = new UtxPoolImpl(ntpTime, d.blockchainUpdater, settings.utxSettings, settings.maxTxErrorLogSize, settings.minerSettings.enable)
      val microBlockMiner = new MicroBlockMinerImpl(
        setDebugState = _ => (),
        allChannels = null,
        d.blockchainUpdater,
        utxPool,
        EndorsementStorage.Disabled,
        settings.minerSettings,
        scheduler,
        scheduler,
        Observable.empty
      )

      def generateBlocks(
          block: Block,
          constraint: MiningConstraint,
          lastMicroBlock: Long
      ): Block = {
        val task = microBlockMiner.generateOneMicroBlockTask(
          acc,
          block,
          constraint,
          lastMicroBlock
        )
        import Scheduler.Implicits.global
        val startTime = System.nanoTime()
        val tx = transfer()
        utxPool.putIfNew(tx).resultE.explicitGet()
        val result = task.runSyncUnsafe(scala.concurrent.duration.Duration(60, "s"))
        result match {
          case res @ MicroBlockMinerImpl.Success(b, totalConstraint) =>
            val isFirstBlock = block.transactionData.isEmpty
            val elapsed      = (res.nanoTime - startTime).nanos.toMillis

            if (isFirstBlock) elapsed should be < 1000L
            else elapsed shouldBe settings.minerSettings.microBlockInterval.toMillis +- 1000

            generateBlocks(b, totalConstraint, res.nanoTime)
          case MicroBlockMinerImpl.Stop =>
            d.blockchainUpdater.liquidBlock(d.blockchainUpdater.lastBlockId.get).get
          case MicroBlockMinerImpl.Retry =>
            throw new IllegalStateException()
        }
      }

      // Through the domain: a hand-built key block would carry the genesis block's generation signature, which is not
      // an Ecvrf proof for this height and fails verification inside sodium
      val baseBlock = d.appendBlock()

      val constraint = OneDimensionalMiningConstraint(5, TxEstimators.one, "limit")
      val lastBlock  = generateBlocks(baseBlock, constraint, 0)
      lastBlock.transactionData should have size constraint.rest.toInt
      utxPool.close()
    }
  }

  "Micro block miner" should "retry packing UTX regardless of when event has been sent" in {
    withDomain(RideV6, Seq(AddrWithBalance(defaultAddress, TestValues.bigMoney))) { d =>
      import Scheduler.Implicits.global
      val utxEvents        = ConcurrentSubject.publish[UtxEvent]
      val eventHasBeenSent = new CountDownLatch(1)
      val inner = new UtxPoolImpl(
        ntpTime,
        d.blockchainUpdater,
        RideV6.utxSettings,
        RideV6.maxTxErrorLogSize,
        RideV6.minerSettings.enable,
        { event =>
          utxEvents.onNext(event)
          eventHasBeenSent.countDown()
        }
      )

      val utxPool = new UtxPool {

        override def packUnconfirmed(
            rest: MultiDimensionalMiningConstraint,
            prevStateHash: Option[ByteStr],
            strategy: UtxPool.PackStrategy,
            cancelled: () => Boolean
        ): (Option[Seq[Transaction]], MiningConstraint, Option[ByteStr]) = {
          // Passed through: with it dropped the packed micro block carries no state hash, which is not valid any more,
          // so nothing was ever appended and the test waited for a micro block that could not exist
          val (txs, constraint, stateHash) = inner.packUnconfirmed(rest, prevStateHash, strategy, cancelled)
          val waitingConstraint = new MiningConstraint {
            def isFull: Boolean = { eventHasBeenSent.await(60, TimeUnit.SECONDS); constraint.isFull }
            def isOverfilled: Boolean                                                   = constraint.isOverfilled
            def put(b: Blockchain, tx: Transaction, s: StateSnapshot): MiningConstraint = constraint.put(b, tx, s)
          }
          (txs, waitingConstraint, stateHash)
        }

        override def putIfNew(tx: Transaction, forceValidate: Boolean)                = inner.putIfNew(tx, forceValidate)
        override def removeAll(txs: Iterable[Transaction]): Unit                      = inner.removeAll(txs)
        override def all                                                              = inner.all
        override def size                                                             = inner.size
        override def transactionById(transactionId: ByteStr)                          = inner.transactionById(transactionId)
        override def close(): Unit                                                    = inner.close()
        override def scheduleCleanup(): Unit                                          = inner.scheduleCleanup()
        override def setPrioritySnapshots(snapshots: Seq[StateSnapshot]): Unit        = inner.setPrioritySnapshots(snapshots)
        override def addAndScheduleCleanup(transactions: Iterable[Transaction]): Unit = inner.addAndScheduleCleanup(transactions)
        override def resetPriorityPool(): Unit                                        = inner.resetPriorityPool()
        override def cleanUnconfirmed(): Unit                                         = inner.cleanUnconfirmed()
        override def getPriorityPool: Option[UtxPriorityPool]                         = inner.getPriorityPool
      }

      val miner    = Schedulers.singleThread("miner")
      val appender = Schedulers.singleThread("appender")
      val mbminer  = Schedulers.singleThread("micro-block-miner")

      val microBlockMiner = new MicroBlockMinerImpl(
        _ => (),
        null,
        d.blockchainUpdater,
        utxPool,
        EndorsementStorage.Disabled,
        RideV6.minerSettings,
        miner,
        appender,
        utxEvents.collect { case _: UtxEvent.TxAdded => () }
      )

      val block      = d.appendBlock()
      val constraint = OneDimensionalMiningConstraint(5, TxEstimators.one, "limit")
      microBlockMiner
        .generateMicroBlockSequence(defaultSigner, block, constraint, 0)
        .runToFuture(using mbminer)

      // Checked: a rejected transaction used to leave the UTX empty and the test waiting for a micro block that could
      // never be packed, failing 30s later with nothing to point at the cause
      utxPool.putIfNew(transfer(amount = 123)).resultE.explicitGet()

      // Bounded: if the micro block never gets appended this has to fail the test rather than hang the whole suite
      val deadline = System.nanoTime() + 30.seconds.toNanos
      while (d.lastBlockId == block.id() && System.nanoTime() < deadline) Thread.sleep(100)
      withClue("micro block was not appended within 30s: ") {
        d.lastBlockId should not be block.id()
      }
      d.balance(secondAddress) shouldBe 123

      miner.shutdown()
      appender.shutdown()
      mbminer.shutdown()
      inner.close()
    }
  }
}
