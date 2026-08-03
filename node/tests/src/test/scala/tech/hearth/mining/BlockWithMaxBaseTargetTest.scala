package tech.hearth.mining

import com.typesafe.config.ConfigFactory
import tech.hearth.WithNewDBForEachTest
import tech.hearth.block.Block
import tech.hearth.common.utils.EitherExt2.*
import tech.hearth.consensus.PoSSelector
import tech.hearth.db.{DBCacheSettings, WithState}
import tech.hearth.lagonaki.mocks.TestBlock
import tech.hearth.mining.BlockWithMaxBaseTargetTest.Env
import tech.hearth.settings.*
import tech.hearth.transaction.TxHelpers
import tech.hearth.state.*
import tech.hearth.state.appender.BlockAppender
import tech.hearth.state.diffs.ENOUGH_AMT
import tech.hearth.test.{FreeSpec, HasFatalStopProbe, TestTime}
import tech.hearth.transaction.BlockchainUpdater
import tech.hearth.utils.BaseTargetReachedMaximum
import tech.hearth.utx.UtxPoolImpl
import io.netty.channel.group.DefaultChannelGroup
import io.netty.util.concurrent.GlobalEventExecutor
import monix.execution.Scheduler
import monix.execution.schedulers.SchedulerService
import monix.reactive.Observable
import org.scalacheck.{Arbitrary, Gen}
import tech.hearth.crypto.SigningKey

import scala.concurrent.Await
import scala.concurrent.duration.*
import tech.hearth.test.DomainPresets.*
import tech.hearth.database.TestStorageFactory
import tech.hearth.utils.SystemTime
import tech.hearth.events.BlockchainUpdateTriggers
import tech.hearth.db.WithState.AddrWithBalance

class BlockWithMaxBaseTargetTest extends FreeSpec with WithNewDBForEachTest with DBCacheSettings with HasFatalStopProbe {
  "base target limit" - {
    "node should stop if base target greater than maximum in block creation " in {
      withEnv { case (Env(settings, pos, bcu, utxPoolStub, scheduler, account, lastBlock), stopProbe) =>
        val allChannels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE)
        // The miner checks the forged block's time against the clock before it computes consensus data, and with
        // max-base-target pinned to 1 the PoS delay puts that time minutes ahead. Drive it from a clock set to the
        // block's own time so the attempt reaches the base target limit, which is what this test is about.
        val minerTime = TestTime()
        val miner = new MinerImpl(
          allChannels,
          bcu,
          settings.minerSettings,
          minerTime,
          utxPoolStub,
          BlockEndorser.Disabled,
          EndorsementStorage.Disabled,
          pos,
          scheduler,
          scheduler,
          Observable.empty
        )

        val vrfKey     = TxHelpers.vrfKeyOf(account)
        val refHeader  = bcu.lastBlockHeader.get.header
        val blockDelay = pos.getValidBlockDelay(bcu.height, vrfKey, refHeader.baseTarget, bcu.generatingBalance(account.toAddress)).explicitGet()
        minerTime.setTime(refHeader.timestamp + blockDelay)

        miner.forgeBlock(account, vrfKey)

        withClue("the node was stopped: ") {
          stopProbe.awaitStop() shouldBe true
          stopProbe.stopReason shouldBe Some(BaseTargetReachedMaximum)
        }
      }
    }

    "node should stop if base target greater than maximum in block append" in {
      withEnv { case (Env(settings, pos, bcu, utxPoolStub, scheduler, _, lastBlock), stopProbe) =>
        val blockAppendTask = BlockAppender(bcu, ntpTime, utxPoolStub, pos, BlockEndorser.Disabled, scheduler)(lastBlock, None)
        Await.result(blockAppendTask.runToFuture(using scheduler), 1.minute)

        withClue("the node was stopped: ") {
          stopProbe.awaitStop() shouldBe true
          stopProbe.stopReason shouldBe Some(BaseTargetReachedMaximum)
        }
      }
    }
  }

  def withEnv(f: (Env, FatalStopProbe) => Unit): Unit = {
    // The account has to exist before the state does: it is credited by the genesis snapshot, which is built from
    // the settings the state is created with
    val account = Gen
      .containerOfN[Array, Byte](32, Arbitrary.arbitrary[Byte])
      .map(bs => SigningKey.fromSeed(bs))
      .sample
      .get

    val writerSettings = TestSettings.Default
      .withFunctionalitySettings(TestFunctionalitySettings.Stub)
      .withGenesisBalances(AddrWithBalance(account.toAddress, ENOUGH_AMT))

    val defaultWriter = TestStorageFactory(
      writerSettings,
      db,
      SystemTime,
      BlockchainUpdateTriggers.noop
    )._2

    val settings0                = WavesSettings.fromRootConfig(loadConfig(ConfigFactory.load()))
    val minerSettings            = settings0.minerSettings.copy(quorum = 0)
    val synchronizationSettings0 = settings0.synchronizationSettings.copy(maxBaseTarget = Some(1L))
    val settings = settings0.copy(
      // The updater builds the genesis snapshot from its own settings, so it has to see the same genesis as the writer -
      // otherwise it applies the packaged config's balances, whose base58 addresses no longer parse.
      blockchainSettings = writerSettings.blockchainSettings,
      minerSettings = minerSettings,
      synchronizationSettings = synchronizationSettings0,
      autoShutdownOnUnsupportedFeature = false
    )

    val bcu =
      new BlockchainUpdaterImpl(defaultWriter, settings, ntpTime, ignoreBlockchainUpdateTriggers)
    val stopProbe = fatalStopProbe(BaseTargetReachedMaximum)
    val pos       = PoSSelector(bcu, settings.synchronizationSettings.maxBaseTarget, stopProbe.onFatalStop)

    val utxPoolStub = new UtxPoolImpl(ntpTime, bcu, settings0.utxSettings, settings.maxTxErrorLogSize, settings0.minerSettings.enable)
    val schedulerService: SchedulerService = Scheduler.singleThread("appender")

    try {

      val ts = ntpTime.correctedTime() - 60000
      // The block at height 1 is empty: it carries the genesis snapshot that credits the account. TestBlock cannot give
      // it a state hash - that has to be computed against the state it is applied to - so it is rebuilt here.
      val firstBlock = WithState
        .blockOnTopOf(TestBlock.create(ts + 2, Seq.empty).block, TestBlock.defaultSigner, bcu)
        .resultE
        .explicitGet()

      bcu.processBlock(firstBlock, firstBlock.header.generationSignature, snapshot = None, generatorSet = Seq.empty).explicitGet()

      // Built after the first block is applied: carrying a state hash changes that block's id, so this has to reference
      // the rebuilt one
      val secondBlock = TestBlock
        .create(
          ts + 3,
          firstBlock.id(),
          Seq.empty,
          account
        )
        .block

      f(Env(settings, pos, bcu, utxPoolStub, schedulerService, account, secondBlock), stopProbe)
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
