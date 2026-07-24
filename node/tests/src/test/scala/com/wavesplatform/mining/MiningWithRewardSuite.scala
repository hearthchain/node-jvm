package com.wavesplatform.mining

import cats.effect.Resource
import com.typesafe.config.ConfigFactory
import com.wavesplatform.block.Block
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2.*
import com.wavesplatform.consensus.PoSSelector
import com.wavesplatform.database.{RDB, TestStorageFactory}
import com.wavesplatform.db.DBCacheSettings
import com.wavesplatform.features.{BlockchainFeature, BlockchainFeatures}
import com.wavesplatform.lagonaki.mocks.TestBlock
import com.wavesplatform.settings.*
import com.wavesplatform.state.diffs.ENOUGH_AMT
import com.wavesplatform.state.{BlockEndorser, Blockchain, BlockchainUpdaterImpl, EndorsementStorage, NG}
import com.wavesplatform.transaction.Asset.Waves
import com.wavesplatform.state.utils.TestRocksDB
import com.wavesplatform.transaction.{BlockchainUpdater, Transaction, TxHelpers}
import com.wavesplatform.utx.UtxPoolImpl
import com.wavesplatform.wallet.Wallet
import com.wavesplatform.{TransactionGen, WithNewDBForEachTest}
import io.netty.channel.group.DefaultChannelGroup
import io.netty.util.concurrent.GlobalEventExecutor
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.compatible.Assertion
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import tech.hearth.crypto.SigningKey

import scala.concurrent.Future
import scala.concurrent.duration.*
import com.wavesplatform.test.DomainPresets.*
import com.wavesplatform.db.WithState.AddrWithBalance

class MiningWithRewardSuite extends AsyncFlatSpec with Matchers with WithNewDBForEachTest with TransactionGen with DBCacheSettings {
  import MiningWithRewardSuite.*

  behavior of "Miner with activated reward feature"

  it should "generate valid empty blocks of version 4" in {
    withEnv(Seq.empty) { case Env(_, account, miner, blockchain) =>
      val generateBlock = generateBlockTask(miner)(account)
      val oldBalance    = blockchain.balance(account.toAddress)
      val newBalance    = oldBalance + 2 * settings.blockchainSettings.rewardsSettings.initial
      for {
        _ <- generateBlock
        _ <- generateBlock
      } yield {
        blockchain.balance(account.toAddress) should be(newBalance)
        blockchain.height should be(3)
      }
    }
  }

  it should "generate valid empty block of version 4 after block of version 3" in {
    withEnv(Seq((ts, reference, _) => TestBlock.create(time = ts, ref = reference, txs = Nil).block)) {
      case Env(_, account, miner, blockchain) =>
        val generateBlock = generateBlockTask(miner)(account)
        val oldBalance    = blockchain.balance(account.toAddress)
        val newBalance    = oldBalance + settings.blockchainSettings.rewardsSettings.initial

        generateBlock.map { _ =>
          blockchain.balance(account.toAddress) should be(newBalance)
          blockchain.height should be(3)
        }
    }
  }

  it should "generate valid blocks with transactions of version 4" in {
    val bps: Seq[BlockProducer] = Seq((ts, reference, account) => {
      val recipient1 = createAccount.toAddress
      val recipient2 = createAccount.toAddress
      val tx1 = TxHelpers.transfer(from = account, to = recipient1, amount = 10 * Constants.UnitsInWave, asset = Waves, fee = 400000, feeAsset = Waves, attachment = ByteStr.empty, timestamp = ts)
      val tx2 = TxHelpers.transfer(from = account, to = recipient2, amount = 5 * Constants.UnitsInWave, asset = Waves, fee = 400000, feeAsset = Waves, attachment = ByteStr.empty, timestamp = ts)
      TestBlock.create(time = ts, ref = reference, txs = Seq(tx1, tx2)).block
    })

    val txs: Seq[TransactionProducer] = Seq((ts, account) => {
      val recipient1 = createAccount.toAddress
      TxHelpers.transfer(from = account, to = recipient1, amount = 10 * Constants.UnitsInWave, asset = Waves, fee = 400000, feeAsset = Waves, attachment = ByteStr.empty, timestamp = ts)
    })

    withEnv(bps, txs) { case Env(_, account, miner, blockchain) =>
      val generateBlock = generateBlockTask(miner)(account)
      val oldBalance    = blockchain.balance(account.toAddress)
      val newBalance    = oldBalance + settings.blockchainSettings.rewardsSettings.initial - 10 * Constants.UnitsInWave

      generateBlock.map { _ =>
        blockchain.balance(account.toAddress) should be(newBalance)
        blockchain.height should be(3)
      }
    }

    // Test for empty key block with NG
    withEnv(bps, txs, settingsWithFeatures()) { case Env(_, account, miner, _) =>
      val block = forgeBlock(miner)(account).explicitGet().newBlock
      Task(block.transactionData shouldBe empty)
    }
  }

  private def withEnv(bps: Seq[BlockProducer], txs: Seq[TransactionProducer] = Seq(), settings: WavesSettings = MiningWithRewardSuite.settings)(
      f: Env => Task[Assertion]
  ): Task[Assertion] = {
    // The account has to exist before the state does: it is credited by the genesis snapshot, which is built from
    // the settings the state is created with
    val account             = createAccount
    val settingsWithGenesis = settings.withGenesisBalances(AddrWithBalance(account.toAddress, ENOUGH_AMT))

    resources(settingsWithGenesis).use { case (blockchainUpdater, _) =>
      for {
        _ <- Task.unit
        pos     = PoSSelector(blockchainUpdater, settingsWithGenesis.synchronizationSettings.maxBaseTarget)
        utxPool = new UtxPoolImpl(
          ntpTime,
          blockchainUpdater,
          settingsWithGenesis.utxSettings,
          settingsWithGenesis.maxTxErrorLogSize,
          settingsWithGenesis.minerSettings.enable
        )
        scheduler   = Scheduler.singleThread("appender")
        allChannels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE)
        wallet      = Wallet(WalletSettings(None, Some("123"), None))
        miner = new MinerImpl(
          allChannels,
          blockchainUpdater,
          settingsWithGenesis,
          ntpTime,
          utxPool,
          BlockEndorser.Disabled,
          EndorsementStorage.Disabled,
          Seq.empty,
          pos,
          scheduler,
          scheduler,
          Observable.empty
        )
        ts = ntpTime.correctedTime() - 60000
        // The block at height 1 is empty: it carries the genesis snapshot that credits the account
        genesisBlock = TestBlock.create(ts + 2, Seq.empty).block
        _ <- Task {
          blockchainUpdater.processBlock(genesisBlock, genesisBlock.header.generationSignature, snapshot = None, generatorSet = Seq.empty)
        }
        blocks = bps.foldLeft {
          (ts + 1, Seq[Block](genesisBlock))
        } { case ((ts, chain), bp) =>
          (ts + 3, bp(ts + 3, chain.head.id(), account) +: chain)
        }._2
        added <- Task.traverse(blocks.reverse) { b =>
          Task(blockchainUpdater.processBlock(b, b.header.generationSignature, snapshot = None, generatorSet = Seq.empty))
        }
        _   = added.foreach(_.explicitGet())
        _   = txs.foreach(tx => utxPool.putIfNew(tx(ts + 6, account)).resultE.explicitGet())
        env = Env(blocks, account, miner, blockchainUpdater)
        r <- f(env)
        _ = scheduler.shutdown()
        _ = utxPool.close()
      } yield r
    }
  }

  private def generateBlockTask(miner: MinerImpl)(account: SigningKey): Task[Unit] = miner.generateBlockTask(account, TxHelpers.vrfKeyOf(account), None)

  private def forgeBlock(miner: MinerImpl)(account: SigningKey): Either[String, ForgeAttemptResult.Success] = miner.forgeBlock(account, TxHelpers.vrfKeyOf(account)).toEither

  private def resources(settings: WavesSettings): Resource[Task, (BlockchainUpdaterImpl, RDB)] =
    Resource
      .make {
        val (bcu, rdbWriter) = TestStorageFactory(settings, db, ntpTime, ignoreBlockchainUpdateTriggers)
        Task.now((bcu, rdbWriter, db))
      } { case (blockchainUpdater, rdbWriter, _) =>
        Task {
          blockchainUpdater.shutdown()
          rdbWriter.close()
        }
      }
      .map { case (blockchainUpdater, _, db) => (blockchainUpdater, db) }
}

object MiningWithRewardSuite {
  import TestFunctionalitySettings.Enabled
  import monix.execution.Scheduler.Implicits.global

  type BlockProducer       = (Long, ByteStr, SigningKey) => Block
  type TransactionProducer = (Long, SigningKey) => Transaction

  case class Env(blocks: Seq[Block], account: SigningKey, miner: MinerImpl, blockchain: Blockchain & BlockchainUpdater & NG)

  val settings: WavesSettings = {
    val commonSettings: WavesSettings = WavesSettings.fromRootConfig(loadConfig(ConfigFactory.load()))
    val minerSettings: MinerSettings =
      commonSettings.minerSettings.copy(quorum = 0, intervalAfterLastBlockThenGenerationIsAllowed = 1 hour)

    val functionalitySettings: FunctionalitySettings = Enabled
    val blockchainSettings: BlockchainSettings =
      commonSettings.blockchainSettings
        .copy(functionalitySettings = functionalitySettings)
        .copy(rewardsSettings = RewardsSettings.TESTNET)
    commonSettings.copy(minerSettings = minerSettings, blockchainSettings = blockchainSettings)
  }

  def settingsWithFeatures(features: BlockchainFeature*): WavesSettings = {
    val blockchainSettings = settings.blockchainSettings

    settings.copy(
      blockchainSettings = blockchainSettings.copy(
        functionalitySettings = blockchainSettings.functionalitySettings.copy(preActivatedFeatures = features.map(_.id -> 0).toMap)
      )
    )
  }

  def createAccount: SigningKey =
    Gen
      .containerOfN[Array, Byte](32, Arbitrary.arbitrary[Byte])
      .map(bs => SigningKey.fromSeed(bs))
      .sample
      .get

  // Bounded: a task that never completes (e.g. mining that can never succeed) has to fail its own test rather than
  // hang the whole suite, which an async spec would otherwise wait on forever
  private implicit def taskToFuture(task: Task[Assertion]): Future[Assertion] = task.timeout(60.seconds).runToFuture
}
