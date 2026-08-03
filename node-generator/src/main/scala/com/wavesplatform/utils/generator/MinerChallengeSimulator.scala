package com.wavesplatform.utils.generator

import com.typesafe.config.{ConfigFactory, ConfigParseOptions}
import com.wavesplatform.GenesisBlockGenerator
import com.wavesplatform.account.Address
import com.wavesplatform.block.Block
import com.wavesplatform.block.Block.BlockId
import com.wavesplatform.consensus.PoSSelector
import com.wavesplatform.database.{RDB, RocksDBWriter}
import com.wavesplatform.events.{BlockchainUpdateTriggers, UtxEvent}
import com.wavesplatform.features.BlockchainFeatures
import com.wavesplatform.history.StorageFactory
import com.wavesplatform.lang.ValidationError
import com.wavesplatform.mining.{ForgeAttemptResult, Miner, MinerImpl}
import com.wavesplatform.network.BlockSnapshotResponse
import com.wavesplatform.settings.*
import com.wavesplatform.state.BlockchainUpdaterImpl.BlockApplyResult
import com.wavesplatform.state.appender.BlockAppender
import com.wavesplatform.state.{BalanceSnapshot, BlockEndorser, BlockchainUpdaterImpl, EndorsementStorage}
import com.wavesplatform.utils.{Schedulers, Time}
import com.wavesplatform.utx.UtxPoolImpl
import io.netty.channel.group.DefaultChannelGroup
import monix.eval.Task
import monix.execution.schedulers.SchedulerService
import monix.reactive.subjects.ConcurrentSubject
import org.apache.commons.io.FileUtils
import pureconfig.ConfigSource
import tech.hearth.crypto.{SigningKey, VrfKey}

import java.io.{File, FileNotFoundException}
import scala.concurrent.duration.*
import scala.language.reflectiveCalls

object MinerChallengeSimulator {
  implicit val scheduler: SchedulerService = Schedulers.singleThread("miner-challenge-simulator")
  sys.addShutdownHook(synchronized {
    scheduler.shutdown()
    scheduler.awaitTermination(10 seconds)
  })

  val forkHeight = 1000000

  var quit = false
  sys.addShutdownHook {
    quit = true
  }

  def main(args: Array[String]): Unit = {
    val genesisConfFile = new File(args.headOption.getOrElse(throw new IllegalArgumentException("Specify a path to genesis generator config file")))
    if (!genesisConfFile.exists()) throw new FileNotFoundException(genesisConfFile.getCanonicalPath)
    val nodeConfFile = new File(args.tail.headOption.getOrElse(throw new IllegalArgumentException("Specify a path to node config file")))
    if (!nodeConfFile.exists()) throw new FileNotFoundException(nodeConfFile.getCanonicalPath)
    val challengingMinerIdx = args.drop(2).headOption.map(_.toInt).getOrElse(1)

    val config      = readConfFile(genesisConfFile)
    val genSettings = GenesisBlockGenerator.parseSettings(config)
    val genesis =
      ConfigSource
        .fromConfig(ConfigFactory.parseString(GenesisBlockGenerator.createConfig(genSettings)))
        .at("genesis")
        .loadOrThrow[GenesisSettings]

    val blockchainSettings = BlockchainSettings(
      genSettings.chainId.toChar,
      genSettings.functionalitySettings.copy(preActivatedFeatures = BlockchainFeatures.implemented.map(_ -> 0).toMap),
      genesis,
      RewardsSettings.MAINNET
    )
    val wavesSettings = {
      val settings = WavesSettings.fromRootConfig(loadConfig(Some(nodeConfFile).map(readConfFile)))
      settings.copy(blockchainSettings = blockchainSettings, minerSettings = settings.minerSettings.copy(quorum = 0))
    }

    val miners: Seq[(SigningKey, VrfKey)] = genSettings.distributions.collect {
      case item if item.miner =>
        val info = GenesisBlockGenerator.toFullAddressInfo(item)
        (info.signingKey, info.vrfKey)
    }

    val maliciousMiner   = miners.head
    val challengingMiner = miners(challengingMinerIdx)

    var originalBlockchain =
      BlockchainObjects.createOriginal(wavesSettings, genSettings.timestamp.getOrElse(System.currentTimeMillis()))
    var challengingBlockchain: Option[BlockchainObjects] = None

    while (!Thread.currentThread().isInterrupted && !quit) synchronized {
      val prevTime         = originalBlockchain.fakeTime
      val originalScore    = originalBlockchain.forgeAndAppendBlock(miners, challengingMiner, maliciousMiner).get
      val challengingScore = challengingBlockchain.flatMap(_.forgeAndAppendBlock(miners, challengingMiner, maliciousMiner))

      if (originalBlockchain.blockchain.height > forkHeight - 1) {
        if (challengingScore.exists(_ <= originalScore)) {
          println(s"Original score = $originalScore, challenging score = ${challengingScore.get} on height ${originalBlockchain.blockchain.height}")
        } else if (challengingScore.isDefined) {
          println(s"Original score ($originalScore) < challenging score (${challengingScore.get}) on height ${originalBlockchain.blockchain.height}")
          quit = true
        }
      }

      if (originalBlockchain.blockchain.height == forkHeight + 1 && challengingBlockchain.isEmpty) {
        originalBlockchain.blockchain.shutdown()
        originalBlockchain.rdb.close()
        challengingBlockchain = Some(BlockchainObjects.createChallenging(wavesSettings, challengingMiner, maliciousMiner))
        originalBlockchain = BlockchainObjects.createOriginal(wavesSettings, prevTime.time)
      }
    }
  }

  case class BlockchainObjects(
      blockchain: BlockchainUpdaterImpl,
      rdb: RDB,
      miner: MinerImpl,
      blockAppender: (Block, Option[BlockSnapshotResponse]) => Task[Either[ValidationError, BlockApplyResult]],
      fakeTime: FakeTime,
      isChallenging: Boolean
  ) {
    def forgeAndAppendBlock(
        miners: Seq[(SigningKey, VrfKey)],
        challengingMiner: (SigningKey, VrfKey),
        maliciousMiner: (SigningKey, VrfKey)
    ): Option[BigInt] = {
      val miningTimes = getBlockMiningTimes(miners)
      val (bestMiner, nextTime) = if (blockchain.height == forkHeight && isChallenging) {
        miningTimes.find(_._1 == challengingMiner).get
      } else if (blockchain.height == forkHeight) {
        miningTimes.find(_._1 == maliciousMiner).get
      } else {
        miningTimes.minBy(_._2)
      }
      fakeTime.time = nextTime

      miner.forgeBlock(bestMiner._1, bestMiner._2) match {
        case ForgeAttemptResult.Success(block, _) =>
          blockAppender(block, None).runSyncUnsafe() match {
            case Right(BlockApplyResult.Applied(score = score)) => Some(score)
            case other =>
              println(s"Error appending block: $other")
              quit = true
              Some(0)
          }

        case err =>
          println(s"Error generating block: $err")
          quit = true
          Some(0)
      }
    }

    private def getBlockMiningTimes(miners: Seq[(SigningKey, VrfKey)]): Seq[((SigningKey, VrfKey), Long)] =
      miners.flatMap { case kp @ (sk, vk) =>
        val time = miner.nextBlockGenerationTime(blockchain, sk, vk)
        time.toOption.map(kp -> _)
      }
  }

  object BlockchainObjects {
    def createOriginal(wavesSettings: WavesSettings, startTime: Long): BlockchainObjects = {
      val rdb      = RDB.open(wavesSettings.dbSettings)
      val fakeTime = createFakeTime(startTime)
      val (blockchainUpdater, _) =
        StorageFactory(wavesSettings, rdb, fakeTime, BlockchainUpdateTriggers.noop)
      com.wavesplatform.checkGenesis(wavesSettings, blockchainUpdater, Miner.StrictDisabledMiner)
      sys.addShutdownHook(synchronized {
        blockchainUpdater.shutdown()
        rdb.close()
      })
      val (miner, appender) = createMinerAndAppender(blockchainUpdater, fakeTime, wavesSettings)
      BlockchainObjects(blockchainUpdater, rdb, miner, appender, fakeTime, false)
    }

    def createChallenging(
        wavesSettings: WavesSettings,
        challengingMiner: (SigningKey, VrfKey),
        maliciousMiner: (SigningKey, VrfKey)
    ): BlockchainObjects = {
      val correctBlockchainDbDir = wavesSettings.dbSettings.directory + "/../challenged"
      FileUtils.copyDirectory(new File(wavesSettings.dbSettings.directory), new File(correctBlockchainDbDir))
      val dbSettings         = wavesSettings.dbSettings.copy(directory = correctBlockchainDbDir)
      val fixedWavesSettings = wavesSettings.copy(dbSettings = dbSettings)
      val rdb                = RDB.open(dbSettings)
      val rocksDBWriter = RocksDBWriter(
        rdb,
        fixedWavesSettings.blockchainSettings,
        fixedWavesSettings.dbSettings,
        isLightMode = false
      )
      val fakeTime = createFakeTime(rocksDBWriter.lastBlockTimestamp.get)
      val blockchainUpdater = new BlockchainUpdaterImpl(
        rocksDBWriter,
        fixedWavesSettings,
        fakeTime,
        BlockchainUpdateTriggers.noop
      ) {
        override def balanceSnapshots(address: Address, from: Int, to: Option[BlockId]): Seq[BalanceSnapshot] = {
          val initSnapshots = super.balanceSnapshots(address, from, to)

          if (address == maliciousMiner._1.toAddress) {
            initSnapshots.map { bs =>
              bs.copy(leaseOut = bs.regularBalance)
            }
          } else if (address == challengingMiner._1.toAddress) {
            initSnapshots.map { bs =>
              bs.copy(leaseIn = super.balance(maliciousMiner._1.toAddress))
            }
          } else {
            initSnapshots
          }
        }
      }

      com.wavesplatform.checkGenesis(fixedWavesSettings, blockchainUpdater, Miner.StrictDisabledMiner)
      sys.addShutdownHook(synchronized {
        blockchainUpdater.shutdown()
        rdb.close()
      })

      val (miner, appender) = createMinerAndAppender(blockchainUpdater, fakeTime, wavesSettings)
      BlockchainObjects(blockchainUpdater, rdb, miner, appender, fakeTime, true)
    }

    private def createMinerAndAppender(
        blockchain: BlockchainUpdaterImpl,
        fakeTime: Time,
        wavesSettings: WavesSettings
    ): (
        MinerImpl,
        (
            com.wavesplatform.block.Block,
            Option[com.wavesplatform.network.BlockSnapshotResponse]
        ) => monix.eval.Task[Either[com.wavesplatform.lang.ValidationError, com.wavesplatform.state.BlockchainUpdaterImpl.BlockApplyResult]]
    ) = {
      val utx = new UtxPoolImpl(fakeTime, blockchain, wavesSettings.utxSettings, wavesSettings.maxTxErrorLogSize, wavesSettings.minerSettings.enable)
      val posSelector = PoSSelector(blockchain, None)
      val utxEvents   = ConcurrentSubject.publish[UtxEvent](using scheduler)
      val miner = new MinerImpl(
        new DefaultChannelGroup("", null),
        blockchain,
        wavesSettings.minerSettings,
        fakeTime,
        utx,
        BlockEndorser.Disabled,
        EndorsementStorage.Disabled,
        posSelector,
        scheduler,
        scheduler,
        utxEvents.collect { case _: UtxEvent.TxAdded => () }
      )
      val blockAppender = BlockAppender(blockchain, fakeTime, utx, posSelector, BlockEndorser.Disabled, scheduler, verify = false)

      miner -> blockAppender
    }

    private def createFakeTime(startTime: Long) =
      new FakeTime(startTime)
  }

  private def readConfFile(f: File) = ConfigFactory.parseFile(f, ConfigParseOptions.defaults().setAllowMissing(false))
}
