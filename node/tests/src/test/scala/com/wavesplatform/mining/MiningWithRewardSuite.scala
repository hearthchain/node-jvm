package com.wavesplatform.mining

import cats.effect.Resource
import com.wavesplatform.block.Block
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2.*
import com.wavesplatform.consensus.PoSSelector
import com.wavesplatform.database.{RDB, TestStorageFactory}
import com.wavesplatform.db.DBCacheSettings
import com.wavesplatform.lagonaki.mocks.TestBlock
import com.wavesplatform.settings.*
import com.wavesplatform.state.diffs.ENOUGH_AMT
import com.wavesplatform.state.{BlockEndorser, Blockchain, BlockchainUpdaterImpl, EndorsementStorage, NG}
import com.wavesplatform.transaction.Asset.Waves
import com.wavesplatform.transaction.{BlockchainUpdater, Transaction, TxHelpers}
import com.wavesplatform.utx.UtxPoolImpl
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
import com.wavesplatform.db.WithState
import com.wavesplatform.db.WithState.AddrWithBalance
import com.wavesplatform.test.{BaseSuite, DomainPresets}

class MiningWithRewardSuite extends AsyncFlatSpec with Matchers with WithNewDBForEachTest with TransactionGen with DBCacheSettings {
  import MiningWithRewardSuite.*
  BaseSuite.configureDefaultNetwork()

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
    withEnv(Seq((ts, reference, account) => TestBlock.create(time = ts, ref = reference, txs = Nil, signer = account).block)) {
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
      val tx1 = TxHelpers.transfer(
        from = account,
        to = recipient1,
        amount = 10 * Constants.UnitsInWave,
        asset = Waves,
        fee = 400000,
        feeAsset = Waves,
        attachment = ByteStr.empty,
        timestamp = ts
      )
      val tx2 = TxHelpers.transfer(
        from = account,
        to = recipient2,
        amount = 5 * Constants.UnitsInWave,
        asset = Waves,
        fee = 400000,
        feeAsset = Waves,
        attachment = ByteStr.empty,
        timestamp = ts
      )
      TestBlock.create(time = ts, ref = reference, txs = Seq(tx1, tx2), signer = account).block
    })

    val txs: Seq[TransactionProducer] = Seq((ts, account) => {
      val recipient1 = createAccount.toAddress
      TxHelpers.transfer(
        from = account,
        to = recipient1,
        amount = 10 * Constants.UnitsInWave,
        asset = Waves,
        fee = 400000,
        feeAsset = Waves,
        attachment = ByteStr.empty,
        timestamp = ts
      )
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

    // Transactions go into micro blocks, so the key block the miner forges is empty
    withEnv(bps, txs) { case Env(_, account, miner, _) =>
      val block = forgeBlock(miner)(account).explicitGet().newBlock
      Task(block.transactionData shouldBe empty)
    }
  }

  private def withEnv(bps: Seq[BlockProducer], txs: Seq[TransactionProducer] = Seq(), settings: WavesSettings = MiningWithRewardSuite.settings)(
      f: Env => Task[Assertion]
  ): Task[Assertion] = {
    // The account has to exist before the state does: it is credited by the genesis snapshot, which is built from
    // the settings the state is created with
    val account = createAccount
    // Committed as well as credited: it generates every block below, and the appender the miner goes through only
    // accepts a block from a committed generator, verifying its VRF proof against the key committed here.
    val settingsWithGenesis = settings
      .withGenesisBalances(AddrWithBalance(account.toAddress, ENOUGH_AMT))
      .withGenesisGenerators(account)

    resources(settingsWithGenesis).use { case (blockchainUpdater, _) =>
      for {
        _ <- Task.unit
        pos = PoSSelector(blockchainUpdater, settingsWithGenesis.synchronizationSettings.maxBaseTarget)
        utxPool = new UtxPoolImpl(
          ntpTime,
          blockchainUpdater,
          settingsWithGenesis.utxSettings,
          settingsWithGenesis.maxTxErrorLogSize,
          settingsWithGenesis.minerSettings.enable
        )
        scheduler   = Scheduler.singleThread("appender")
        allChannels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE)
        miner = new MinerImpl(
          allChannels,
          blockchainUpdater,
          settingsWithGenesis.minerSettings,
          ntpTime,
          utxPool,
          BlockEndorser.Disabled,
          EndorsementStorage.Disabled,
          pos,
          scheduler,
          scheduler,
          Observable.empty
        )
        ts = ntpTime.correctedTime() - 60000
        // The block at height 1 carries the genesis snapshot that credits and commits the account
        genesisBlock = WithState.createGenesisBlock(settingsWithGenesis)
        _ <- Task(
          blockchainUpdater
            .processBlock(genesisBlock, genesisBlock.header.generationSignature, snapshot = None, generatorSet = Seq.empty)
            .explicitGet()
        )
        // `TestBlock.create` leaves the state hash empty and the differ requires one, so each block a test asks for is
        // retargeted onto the head and hashed before it is applied
        extra <- Task.traverse(bps.zipWithIndex.toList) { case (bp, i) =>
          Task {
            val raw = bp(ts + 3 * (i + 1), blockchainUpdater.lastBlockId.get, account)
            // TestBlock defaults to a base target of 2, and the miner's delay for the block after this one is derived
            // from it - leaving it would put that block minutes into the future. Keep the chain's own.
            val onChain = raw.copy(header = raw.header.copy(baseTarget = blockchainUpdater.lastBlockHeader.get.header.baseTarget))
            val block   = WithState.blockOnTopOf(onChain, account, blockchainUpdater).resultE.explicitGet()
            blockchainUpdater.processBlock(block, block.header.generationSignature, snapshot = None, generatorSet = Seq.empty).explicitGet()
            block
          }
        }
        _   = txs.foreach(tx => utxPool.putIfNew(tx(ts + 6, account)).resultE.explicitGet())
        env = Env(genesisBlock +: extra, account, miner, blockchainUpdater)
        r <- f(env)
        _ = scheduler.shutdown()
        _ = utxPool.close()
      } yield r
    }
  }

  private def generateBlockTask(miner: MinerImpl)(account: SigningKey): Task[Unit] =
    miner.generateBlockTask(account, TxHelpers.vrfKeyOf(account), None)

  private def forgeBlock(miner: MinerImpl)(account: SigningKey): Either[String, ForgeAttemptResult.Success] =
    miner.forgeBlock(account, TxHelpers.vrfKeyOf(account)).toEither

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
  import monix.execution.Scheduler.Implicits.global

  type BlockProducer       = (Long, ByteStr, SigningKey) => Block
  type TransactionProducer = (Long, SigningKey) => Transaction

  case class Env(blocks: Seq[Block], account: SigningKey, miner: MinerImpl, blockchain: Blockchain & BlockchainUpdater & NG)

  /** A domain preset rather than the packaged config: the chain has to start at a plausible genesis timestamp, and the
    * blocks below are stamped from the current time. No DAO address is set, so the whole block reward goes to the
    * miner - which is what the balance expectations are about.
    */
  val settings: WavesSettings = {
    val base = DomainPresets.TransactionStateSnapshot
    base
      .copy(
        minerSettings = base.minerSettings.copy(quorum = 0, intervalAfterLastBlockThenGenerationIsAllowed = 1 hour),
        blockchainSettings = base.blockchainSettings.copy(rewardsSettings = RewardsSettings.TESTNET)
      )
      .configure(_.copy(daoAddress = None))
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
