package com.wavesplatform.mining

import com.wavesplatform.block.Block
import com.wavesplatform.consensus.PoSSelector
import com.wavesplatform.db.WithDomain
import com.wavesplatform.db.WithState.AddrWithBalance
import com.wavesplatform.history.Domain
import com.wavesplatform.settings.WavesSettings
import com.wavesplatform.state.appender.BlockAppender
import com.wavesplatform.state.{BlockEndorser, Blockchain, EndorsementStorage, NG, appender}
import com.wavesplatform.transaction.{BlockchainUpdater, TxHelpers}
import com.wavesplatform.utils.Time
import com.wavesplatform.utx.UtxPoolImpl
import io.netty.channel.group.DefaultChannelGroup
import io.netty.util.concurrent.GlobalEventExecutor
import monix.execution.Scheduler
import monix.reactive.Observable
import org.scalatest.Suite

import scala.concurrent.Await
import scala.concurrent.duration.*

trait WithMiner extends WithDomain { suite: Suite =>
  def withMiner(
      blockchain: Blockchain & BlockchainUpdater & NG,
      time: Time,
      settings: WavesSettings,
      verify: Boolean = true,
      timeDrift: Long = appender.MaxTimeDrift
  )(
      f: (MinerImpl, Appender) => Unit
  ): Unit = {
    val pos               = PoSSelector(blockchain, settings.synchronizationSettings.maxBaseTarget)
    val channels          = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE)
    val utxPool           = new UtxPoolImpl(time, blockchain, settings.utxSettings, settings.maxTxErrorLogSize, settings.minerSettings.enable)
    val minerScheduler    = Scheduler.singleThread("miner")
    val appenderScheduler = Scheduler.singleThread("appender")
    val miner = new MinerImpl(
      channels,
      blockchain,
      settings.minerSettings,
      time,
      utxPool,
      BlockEndorser.Disabled,
      EndorsementStorage.Disabled,
      pos,
      minerScheduler,
      appenderScheduler,
      Observable(),
      timeDrift
    )
    def appendBlock(b: Block) = {
      val appendTask = BlockAppender(blockchain, time, utxPool, pos, BlockEndorser.Disabled, appenderScheduler, verify)(b, None)
      // Bounded, so a block that never gets appended fails the test instead of hanging the suite
      Await.result(appendTask.runToFuture(using appenderScheduler), 60.seconds)
    }
    f(miner, appendBlock)
    appenderScheduler.shutdown()
    minerScheduler.shutdown()
    utxPool.close()
  }

  /** @param minerAccounts
    *   Indices into `TxHelpers.signer`, not keys. `MinerImpl` builds its mining accounts from `MinerSettings.accounts`,
    *   which carries hex-encoded *seeds* - and a seed cannot be recovered from a `SigningKey` - so an account is named
    *   here the same way `TxHelpers` derives it. Passing keys instead left `MinerImpl.accounts` empty, and the miner
    *   then reported `No delay` for every address.
    */
  def withDomainAndMiner(
      settings: WavesSettings,
      balances: Seq[AddrWithBalance] = Seq(),
      minerAccounts: Seq[Int] = Seq.empty,
      verify: Boolean = true,
      timeDrift: Long = appender.MaxTimeDrift
  )(
      assert: (Domain, MinerImpl, Appender) => Unit
  ): Unit = {
    // The VRF seed is the one TxHelpers.vrfKeyOf derives, so the miner uses the very key the genesis snapshot commits
    // for this generator - a block signed with any other VRF key fails with `Invalid VRF proof`.
    val configuredAccounts = minerAccounts.map(TxHelpers.miningAccountSettings)
    val settingsWithMiners = settings.copy(minerSettings = settings.minerSettings.copy(accounts = configuredAccounts))

    // The mining accounts have to be committed generators for their blocks to be valid
    withDomain(settingsWithMiners, balances, generators = minerAccounts.map(TxHelpers.signer)) { d =>
      withMiner(d.blockchain, d.testTime, d.settings, verify, timeDrift)(assert(d, _, _))
    }
  }
}
