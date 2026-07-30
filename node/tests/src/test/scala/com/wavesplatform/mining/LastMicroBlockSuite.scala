package com.wavesplatform.mining

import cats.syntax.option.*
import com.wavesplatform.db.WithDomain
import com.wavesplatform.db.WithState.AddrWithBalance
import com.wavesplatform.history.Domain
import com.wavesplatform.state.*
import com.wavesplatform.test.DomainPresets.*
import com.wavesplatform.test.{CatchLogs, FreeSpec, NumericExt, TestSchedulerOps, TestTime, WithResourceManager}
import com.wavesplatform.TestValues
import com.wavesplatform.transaction.TxHelpers
import com.wavesplatform.wallet.Wallet
import io.netty.channel.group.DefaultChannelGroup
import io.netty.util.concurrent.GlobalEventExecutor
import monix.execution.schedulers.TestScheduler
import monix.reactive.Observable
import monix.reactive.subjects.ConcurrentSubject
import org.scalatest.EitherValues
import org.scalatest.time.SpanSugar.convertLongToGrainOfTime

import scala.util.Using

class LastMicroBlockSuite extends FreeSpec with WithDomain with TestSchedulerOps with WithResourceManager with EitherValues {
  private val thisNodeAcc1 = Wallet.generateNewAccount(Domain.DefaultWalletSeed, nonce = 0)
  private val thisNodeAcc2 = Wallet.generateNewAccount(Domain.DefaultWalletSeed, nonce = 1)
  private val otherNodeAcc = TxHelpers.defaultSigner

  private val baseSettings       = DomainPresets.TransactionStateSnapshot
  private val microBlockInterval = 5.seconds
  private val minMicroBlockAge   = 3.seconds
  private val defaultSettings = baseSettings.copy(
    minerSettings = baseSettings.minerSettings.copy(
      quorum = 0,
      microBlockInterval = microBlockInterval,
      minMicroBlockAge = minMicroBlockAge,
      // This node's own accounts: the miner mines with what the settings name, not with what the wallet holds
      accounts = Seq(Domain.walletMiningAccount(0), Domain.walletMiningAccount(1))
    )
  )

  "Same node accounts - next account mining with minMicroblockAge" in Using.Manager { manager =>
    val channels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE)
    manager.acquire(channels)(using _.close())

    var miner = Miner.StrictDisabledMiner
    val time  = TestTime()
    withDomain(
      defaultSettings,
      AddrWithBalance.enoughBalances(thisNodeAcc1, thisNodeAcc2, otherNodeAcc),
      generators = Seq(thisNodeAcc1, thisNodeAcc2, otherNodeAcc),
      miner = Miner.forwardTo(miner),
      time = time
    ) { d =>
      val minerScheduler    = TestScheduler()
      val appenderScheduler = TestScheduler()

      d.wallet.generateNewAccounts(2)

      miner = new MinerImpl(
        channels,
        d.blockchain,
        d.settings.minerSettings,
        time,
        d.utxPool,
        BlockEndorser.Disabled,
        EndorsementStorage.Disabled,
        d.posSelector,
        minerScheduler,
        appenderScheduler,
        Observable.empty
      )

      log.debug("Append block2")
      val block2 = d.createBlock(generator = thisNodeAcc1, strictTime = true)
      d.appender.appendBlock(block2)
      time.setTime(block2.header.timestamp)
      appenderScheduler.tickNext("this-appender-1", failIfNoTasks = false)

      // The miner waits until this before forging on top of block2, and the node clock has to reach it: ticking a
      // scheduler moves its own virtual clock only, and a block timestamped ahead of `time` is one from the future.
      val block3Ts = d.nextBlockTime(d.generatorKeys.accounts.head)

      log.debug("Append microBlock1")
      time.advance(microBlockInterval)
      val microBlock1 = d.createMicroBlock(signer = thisNodeAcc1.some)(TxHelpers.transfer(from = otherNodeAcc, to = thisNodeAcc2.toAddress))
      d.appendMicroBlock(microBlock1)
      val refLiquidBlockId = d.lastBlockId
      appenderScheduler.tickNext("this-appender-2", failIfNoTasks = false)

      log.debug("Append microBlock2")
      time.setTimeIfGreater(block3Ts - 1) // Younger than min-micro-block-age when the block below is forged, so it is left out of it
      d.appendMicroBlock(d.createMicroBlock(signer = thisNodeAcc1.some)(TxHelpers.transfer(from = otherNodeAcc, to = thisNodeAcc2.toAddress)))

      log.debug("Trigger thisNode forging")
      time.setTimeIfGreater(block3Ts)
      appenderScheduler.tickNext("this-appender-3")
      minerScheduler.tickNext("this-miner-1")
      appenderScheduler.tickNext("this-appender-4")

      val lastBlock = d.blockchain.lastBlockHeader.value
      lastBlock.header.reference shouldBe refLiquidBlockId
    }
  }.get

  "Different node accounts - next account mining without minMicroblockAge" in Using.Manager { manager =>
    val channels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE)
    manager.acquire(channels)(using _.close())

    var miner = Miner.StrictDisabledMiner
    val time  = TestTime()
    withDomain(
      defaultSettings,
      AddrWithBalance.enoughBalances(thisNodeAcc1, otherNodeAcc),
      generators = Seq(thisNodeAcc1, otherNodeAcc),
      miner = Miner.forwardTo(miner),
      time = time
    ) { d =>
      val minerScheduler    = TestScheduler()
      val appenderScheduler = TestScheduler()

      d.wallet.generateNewAccounts(1)

      miner = new MinerImpl(
        channels,
        d.blockchain,
        d.settings.minerSettings,
        time,
        d.utxPool,
        BlockEndorser.Disabled,
        EndorsementStorage.Disabled,
        d.posSelector,
        minerScheduler,
        appenderScheduler,
        Observable.empty
      )

      log.debug("Append block2")
      val block2 = d.createBlock(generator = otherNodeAcc, strictTime = true)
      d.appender.appendBlock(block2)
      time.setTime(block2.header.timestamp)
      appenderScheduler.tickNext("this-appender-1", failIfNoTasks = false)

      // See the same computation in the test above
      val block3Ts = d.nextBlockTime(d.generatorKeys.accounts.head)

      log.debug("Append microBlock1")
      time.advance(microBlockInterval)
      val microBlock1 = d.createMicroBlock(signer = otherNodeAcc.some)(TxHelpers.transfer(to = otherNodeAcc.toAddress))
      d.appendMicroBlock(microBlock1)
      appenderScheduler.tickNext("this-appender-2", failIfNoTasks = false)

      log.debug("Append microBlock2")
      time.advance(microBlockInterval)
      d.appendMicroBlock(d.createMicroBlock(signer = otherNodeAcc.some)(TxHelpers.transfer(to = otherNodeAcc.toAddress)))
      val refLiquidBlockId = d.lastBlockId

      log.debug("Trigger thisNode forging")
      // block2 was generated by another node, so min-micro-block-age does not hold anything back: both micro blocks are in
      time.setTimeIfGreater(block3Ts)
      appenderScheduler.tickNext("this-appender-3")
      minerScheduler.tickNext("this-miner-1")
      appenderScheduler.tickNext("this-appender-4")

      val lastBlock = d.blockchain.lastBlockHeader.value
      lastBlock.header.reference shouldBe refLiquidBlockId
    }
  }.get

  // The last micro block below spends both generators down, so the block after it is the first block of a period with an
  // empty generator set. Generating in such a period without a commitment - with an arbitrary VRF key, fixed for the
  // period by the first block that uses it - is the intended rule, but it needs a new block field and is not implemented,
  // so no one can extend the chain here and the miner has nothing left to schedule. Everything else about this test is
  // fixed: enable it once the rule exists.
  "transfer in the last microblock of period, but it removed" ignore withManager { manager =>
    val channels     = manager(new DefaultChannelGroup(GlobalEventExecutor.INSTANCE))
    val thisNodeAcc2 = Wallet.generateNewAccount(Domain.DefaultWalletSeed, nonce = 1)
    val thisNodeAcc3 = Wallet.generateNewAccount(Domain.DefaultWalletSeed, nonce = 2)
    var miner        = Miner.StrictDisabledMiner

    withDomain(
      defaultSettings
        .configure(_.copy(generationPeriodLength = 3))
        .copy(
          minerSettings = defaultSettings.minerSettings.copy(
            maxTransactionsInMicroBlock = 2, // Fixes 5 seconds delay
            // The assertion below is about what the miner says of each of this node's accounts, and it only ever
            // considers the ones the settings name - thisNodeAcc3 among them, poor as it is.
            accounts = Seq(Domain.walletMiningAccount(0), Domain.walletMiningAccount(1), Domain.walletMiningAccount(2))
          )
        ),
      AddrWithBalance.enoughBalances(otherNodeAcc, thisNodeAcc1, thisNodeAcc2) :+ AddrWithBalance(thisNodeAcc3.toAddress, 1000.waves - 1),
      generators = Seq(thisNodeAcc1, thisNodeAcc2, otherNodeAcc),
      miner = Miner.forwardTo(miner)
    ) { d =>
      val minerScheduler    = TestScheduler()
      val appenderScheduler = TestScheduler()
      d.wallet.generateNewAccounts(3)

      val utxEvents = ConcurrentSubject.publish[Unit](using minerScheduler)
      val minerImpl = new MinerImpl(
        channels,
        d.blockchain,
        d.settings.minerSettings,
        d.testTime,
        d.utxPool,
        BlockEndorser.Disabled,
        EndorsementStorage.Disabled,
        d.posSelector,
        minerScheduler,
        appenderScheduler,
        utxEvents
      ) with CatchLogs
      miner = minerImpl

      d.appender.appendBlock(d.createBlock(strictTime = true, generator = otherNodeAcc))
      // A scheduled attempt first waits for the block it was scheduled on to appear, polling once a second
      appenderScheduler.tickNext("appender-0", failIfNoTasks = false)

      // The accounts this node generates with, thisNodeAcc3 aside - it is too poor to ever be the earliest
      val generatingAccounts = d.generatorKeys.accounts.take(2)
      def nextBlockTs        = generatingAccounts.map(d.nextBlockTime).min

      log.debug("Trigger forging of block 3")
      d.testTime.setTimeIfGreater(nextBlockTs)
      appenderScheduler.tickNext("appender-1")
      minerScheduler.tickNext("miner-1")
      appenderScheduler.tickNext("appender-2")

      // Block 4 is the one the micro blocks below race against, so its time is only known once block 3 is the head
      val block4Ts = nextBlockTs

      log.debug("Forge first microblock")
      d.utxPool.putIfNew(TxHelpers.transfer(thisNodeAcc3))
      utxEvents.onNext(())
      minerScheduler.tickNext("miner-2")
      appenderScheduler.tickNext("appender-3")

      log.debug("Forge last microblock")
      d.testTime.setTimeIfGreater(block4Ts - 1) // To exclude the latest microblock, see min-micro-block-age
      Seq(thisNodeAcc1, thisNodeAcc2).foreach { kp =>
        // Everything the account can spend - its generation deposit is locked, so `balance` is not it - less the fee and
        // a waves, leaving it far below what generating takes
        val spendable = d.blockchain.balance(kp.toAddress) - d.blockchain.generationDeposit(kp.toAddress)
        d.utxPool.putIfNew(TxHelpers.transfer(kp, amount = spendable - TestValues.fee - 1.waves)).resultE.value
      }
      utxEvents.onNext(())
      minerScheduler.tickNext("miner-3")
      minerScheduler.tickNext("miner-3-interval") // Micro blocks are a micro-block-interval apart, and that is a sleep of its own
      appenderScheduler.tickNext("appender-4")

      log.debug("Trigger thisNode forging")
      d.testTime.setTimeIfGreater(block4Ts + 1)
      (4 to 5).foreach { i =>
        minerScheduler.tickNext(s"miner-$i")
        appenderScheduler.tickNext(s"appender-${i + 1}")
      }

      val messages = minerImpl.inMemoryLog.getMessages
      Seq(thisNodeAcc1, thisNodeAcc2, thisNodeAcc3).foreach { kp =>
        messages.find(_.contains(s"${kp.toAddress} is lower than required for generation")) shouldBe defined
      }
    }
  }
}
