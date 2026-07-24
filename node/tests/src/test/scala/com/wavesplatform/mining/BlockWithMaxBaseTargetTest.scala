package com.wavesplatform.mining

import com.typesafe.config.ConfigFactory
import com.wavesplatform.WithNewDBForEachTest
import com.wavesplatform.block.Block
import com.wavesplatform.common.utils.EitherExt2.*
import com.wavesplatform.consensus.PoSSelector
import com.wavesplatform.db.DBCacheSettings
import com.wavesplatform.features.BlockchainFeatures
import com.wavesplatform.lagonaki.mocks.TestBlock
import com.wavesplatform.mining.BlockWithMaxBaseTargetTest.Env
import com.wavesplatform.settings.*
import com.wavesplatform.transaction.TxHelpers
import com.wavesplatform.state.*
import com.wavesplatform.state.appender.BlockAppender
import com.wavesplatform.state.diffs.ENOUGH_AMT
import com.wavesplatform.state.utils.TestRocksDB
import com.wavesplatform.test.{FreeSpec, HasSecurityManager}
import com.wavesplatform.transaction.BlockchainUpdater
import com.wavesplatform.utils.BaseTargetReachedMaximum
import com.wavesplatform.utx.UtxPoolImpl
import com.wavesplatform.wallet.Wallet
import io.netty.channel.group.DefaultChannelGroup
import io.netty.util.concurrent.GlobalEventExecutor
import monix.eval.Task
import monix.execution.Scheduler
import monix.execution.schedulers.SchedulerService
import monix.reactive.Observable
import org.scalacheck.{Arbitrary, Gen}
import tech.hearth.crypto.SigningKey

import java.util.concurrent.TimeUnit
import scala.concurrent.Await
import scala.concurrent.duration.*
import com.wavesplatform.test.DomainPresets.*
import com.wavesplatform.database.TestStorageFactory
import com.wavesplatform.utils.SystemTime
import com.wavesplatform.events.BlockchainUpdateTriggers
import com.wavesplatform.db.WithState.AddrWithBalance

class BlockWithMaxBaseTargetTest extends FreeSpec with WithNewDBForEachTest with DBCacheSettings with HasSecurityManager {
  "base target limit" - {
    "node should stop if base target greater than maximum in block creation " in {
      withEnv { case Env(settings, pos, bcu, utxPoolStub, scheduler, account, lastBlock) =>
        val allChannels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE)
        val wallet      = Wallet(WalletSettings(None, Some("123"), None))
        val miner = new MinerImpl(
          allChannels,
          bcu,
          settings,
          ntpTime,
          utxPoolStub,
          BlockEndorser.Disabled,
          EndorsementStorage.Disabled,
          Seq.empty,
          pos,
          scheduler,
          scheduler,
          Observable.empty
        )

        withSecurityManager(BaseTargetReachedMaximum) { signal =>
          try {
            miner.forgeBlock(account, TxHelpers.vrfKeyOf(account))
          } catch {
            case _: SecurityException => // NOP
          }

          signal.tryAcquire(10, TimeUnit.SECONDS)
        }
      }
    }

    "node should stop if base target greater than maximum in block append" in {
      withEnv { case Env(settings, pos, bcu, utxPoolStub, scheduler, _, lastBlock) =>
        withSecurityManager(BaseTargetReachedMaximum) { signal =>
          val blockAppendTask = BlockAppender(bcu, ntpTime, utxPoolStub, pos, BlockEndorser.Disabled, scheduler)(lastBlock, None)
            .onErrorRecoverWith[Any] { case _: SecurityException => Task.unit }
          Await.result(blockAppendTask.runToFuture(using scheduler), 1.minute)

          signal.tryAcquire(10, TimeUnit.SECONDS)
        }
      }
    }
  }

  def withEnv(f: Env => Unit): Unit = {
    // The account has to exist before the state does: it is credited by the genesis snapshot, which is built from
    // the settings the state is created with
    val account = Gen
      .containerOfN[Array, Byte](32, Arbitrary.arbitrary[Byte])
      .map(bs => SigningKey.fromSeed(bs))
      .sample
      .get

    val defaultWriter = TestStorageFactory(
      TestSettings.Default
        .withFunctionalitySettings(TestFunctionalitySettings.Stub)
        .withGenesisBalances(AddrWithBalance(account.toAddress, ENOUGH_AMT)),
      db,
      SystemTime,
      BlockchainUpdateTriggers.noop
    )._2

    val settings0     = WavesSettings.fromRootConfig(loadConfig(ConfigFactory.load()))
    val minerSettings = settings0.minerSettings.copy(quorum = 0)
    val blockchainSettings0 = settings0.blockchainSettings.copy(
      functionalitySettings = settings0.blockchainSettings.functionalitySettings
    )
    val synchronizationSettings0 = settings0.synchronizationSettings.copy(maxBaseTarget = Some(1L))
    val settings = settings0.copy(
      blockchainSettings = blockchainSettings0,
      minerSettings = minerSettings,
      synchronizationSettings = synchronizationSettings0,
      featuresSettings = settings0.featuresSettings.copy(autoShutdownOnUnsupportedFeature = false)
    )

    val bcu =
      new BlockchainUpdaterImpl(defaultWriter, settings, ntpTime, ignoreBlockchainUpdateTriggers, (_, _) => Map.empty)
    val pos = PoSSelector(bcu, settings.synchronizationSettings.maxBaseTarget)

    val utxPoolStub = new UtxPoolImpl(ntpTime, bcu, settings0.utxSettings, settings.maxTxErrorLogSize, settings0.minerSettings.enable)
    val schedulerService: SchedulerService = Scheduler.singleThread("appender")

    try {

      val ts = ntpTime.correctedTime() - 60000
      // The block at height 1 is empty: it carries the genesis snapshot that credits the account
      val firstBlock = TestBlock.create(ts + 2, Seq.empty).block
      val secondBlock = TestBlock
        .create(
          ts + 3,
          firstBlock.id(),
          Seq.empty,
          account
        )
        .block

      bcu.processBlock(firstBlock, firstBlock.header.generationSignature, snapshot = None, generatorSet = Seq.empty).explicitGet()

      f(Env(settings, pos, bcu, utxPoolStub, schedulerService, account, secondBlock))
    } finally {
      schedulerService.shutdown()
      utxPoolStub.close()
      bcu.shutdown()
      defaultWriter.close()
    }
  }
}

object BlockWithMaxBaseTargetTest {

  final case class Env(
      settings: WavesSettings,
      pos: PoSSelector,
      bcu: Blockchain & BlockchainUpdater & NG,
      utxPool: UtxPoolImpl,
      schedulerService: SchedulerService,
      miner: SigningKey,
      lastBlock: Block
  )
}
