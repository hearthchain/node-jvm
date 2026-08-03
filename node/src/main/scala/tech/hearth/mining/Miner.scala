package tech.hearth.mining

import cats.syntax.either.*
import tech.hearth.block.Block
import tech.hearth.block.Block.*
import tech.hearth.common.state.ByteStr
import tech.hearth.consensus.nxt.NxtLikeConsensusBlockData
import tech.hearth.consensus.{GeneratingBalanceProvider, PoSSelector}
import tech.hearth.features.BlockchainFeatures
import tech.hearth.metrics.{BlockStats, *}
import tech.hearth.mining.microblocks.MicroBlockMiner
import tech.hearth.network.*
import tech.hearth.settings.MinerSettings
import tech.hearth.state.*
import tech.hearth.state.BlockchainUpdaterImpl.BlockApplyResult.{Applied, Ignored}
import tech.hearth.state.appender.BlockAppender
import tech.hearth.state.diffs.BlockDiffer
import tech.hearth.transaction.*
import tech.hearth.transaction.TxValidationError.BlockFromFuture
import tech.hearth.utils.{ScorexLogging, Time}
import tech.hearth.utx.UtxPool
import io.netty.channel.group.ChannelGroup
import kamon.Kamon
import monix.eval.Task
import monix.execution.Scheduler
import monix.execution.cancelables.{CompositeCancelable, SerialCancelable}
import monix.reactive.Observable
import tech.hearth.crypto.*

import java.time.LocalTime
import scala.concurrent.duration.*

trait Miner {
  def scheduleMining(baseBlockchain: Option[Blockchain] = None, cancelMicroBlockMining: Boolean = true): Unit
}

trait MinerDebugInfo {
  def state: MinerDebugInfo.State
  def nextBlockGenerationOffsets: Map[Address, Either[String, FiniteDuration]]
}

object MinerDebugInfo {
  sealed trait State
  case object MiningBlocks              extends State
  case object MiningMicroblocks         extends State
  case object Disabled                  extends State
  final case class Error(error: String) extends State
}

class MinerImpl(
    allChannels: ChannelGroup,
    blockchainUpdater: Blockchain & BlockchainUpdater & NG,
    settings: MinerSettings,
    timeService: Time,
    utx: UtxPool,
    blockEndorser: BlockEndorser,
    endorsementStorage: EndorsementStorage,
    pos: PoSSelector,
    val minerScheduler: Scheduler,
    val appenderScheduler: Scheduler,
    transactionAdded: Observable[Unit],
    maxTimeDrift: Long = appender.MaxTimeDrift
) extends Miner
    with MinerDebugInfo
    with ScorexLogging {

  private val scheduledAttempts            = SerialCancelable()
  private val microBlockAttempt            = SerialCancelable()
  val generatorKeys: GeneratorKeys         = GeneratorKeys.fromSettings(settings)
  private def accounts: Seq[MiningAccount] = generatorKeys.accounts

  @volatile
  private var debugStateRef: MinerDebugInfo.State = MinerDebugInfo.Disabled

  private val microBlockMiner: MicroBlockMiner = MicroBlockMiner(
    debugStateRef = _,
    allChannels,
    blockchainUpdater,
    utx,
    endorsementStorage,
    settings,
    minerScheduler,
    appenderScheduler,
    transactionAdded
  )

  override def nextBlockGenerationOffsets: Map[Address, Either[String, FiniteDuration]] =
    accounts.map { ma => ma.signingKey.toAddress -> nextBlockGenOffsetWithConditions(ma.signingKey, ma.vrfKey, blockchainUpdater) }.toMap

  def scheduleMining(baseBlockchain: Option[Blockchain], cancelMicroBlockMining: Boolean): Unit = {
    scheduledAttempts := CompositeCancelable.fromSet(accounts.map { a =>
      generateBlockTask(a.signingKey, a.vrfKey, baseBlockchain)
        .onErrorHandle(err => log.warn(s"Error mining block by ${a.address}: ${err.getMessage}"))
        .runAsyncLogErr(using appenderScheduler)
    }.toSet)

    if (cancelMicroBlockMining) {
      Miner.blockMiningStarted.increment()
      microBlockAttempt := SerialCancelable()
      debugStateRef = MinerDebugInfo.MiningBlocks
    }
  }

  override def state: MinerDebugInfo.State = debugStateRef

  private def checkAge(parentHeight: Int, parentTimestamp: Long): Either[String, Unit] =
    if (parentHeight == 1) Either.unit
    else {
      val blockAge = (timeService.correctedTime() - parentTimestamp).millis
      Either.raiseWhen(blockAge > settings.intervalAfterLastBlockThenGenerationIsAllowed) {
        s"BlockChain is too old (last block timestamp is $parentTimestamp generated $blockAge ago)"
      }
    }

  private def consensusData(blockchain: Blockchain, vrfKey: VrfKey, blockTime: Long): Either[String, NxtLikeConsensusBlockData] = {
    val lastBlockHeader = blockchain.lastBlockHeader.get.header
    pos
      .copy(blockchain = blockchain)
      .consensusData(
        vrfKey,
        blockchain.height,
        blockchain.settings.genesisSettings.averageBlockDelay,
        lastBlockHeader.baseTarget,
        lastBlockHeader.timestamp,
        blockchainUpdater.parentHeader(lastBlockHeader, 2).map(_.timestamp),
        blockTime
      )
      .leftMap(_.toString)
  }

  private def packTransactionsForKeyBlock(
      miner: Address,
      reference: ByteStr,
      prevStateHash: Option[ByteStr]
  ): (Seq[Transaction], MiningConstraint, Option[ByteStr]) = {
    val estimators = MiningConstraints(Some(settings))
    val keyBlockStateHash = prevStateHash.flatMap { prevHash =>
      BlockDiffer
        .createInitialBlockSnapshot(blockchainUpdater, reference, miner, None)
        .toOption
        .map { initSnapshot =>
          if (initSnapshot == StateSnapshot.empty) prevHash
          else TxStateSnapshotHashBuilder.createHashFromSnapshot(initSnapshot, None).createHash(prevHash)
        }
    }

    // NG is active: the key block carries no transactions
    (Seq.empty, estimators.total, keyBlockStateHash)
  }

  /** @param referenceOpt Used only in tests
    */
  def forgeBlock(signingKey: SigningKey, vrfKey: VrfKey, referenceOpt: Option[ByteStr] = None): ForgeAttemptResult = {
    val reference = referenceOpt.getOrElse {
      val lastBlockHeader = blockchainUpdater.lastBlockHeader.get.header

      val maxMicroblockTimestampOffsetMs = // See min-micro-block-age in application.conf
        if (accounts.exists(_.address == lastBlockHeader.generator.toAddress)) settings.minMicroBlockAge.toMillis
        else 0L

      val lastBlockInfo = blockchainUpdater.bestLastBlockInfo(timeService.monotonicMillis() - maxMicroblockTimestampOffsetMs)
      lastBlockInfo.get.blockId
    }

    val blockchain     = blockchainUpdater.referencedBlockchain(reference)
    val refBlockHeader = blockchain.lastBlockHeader.get
    val refBaseTarget  = refBlockHeader.header.baseTarget
    val height         = blockchain.height
    val newBlockHeight = Height(height + 1)

    val address = signingKey.toAddress

    metrics.blockBuildTimeStats.measureSuccessful {
      val stopReasons = for {
        _ <- Right(())
        balance = blockchain.generatingBalance(address)
        _ <- Either.raiseUnless(GeneratingBalanceProvider.isMiningAllowed(balance)) {
          s"$address is not committed on $newBlockHeight. Try to commit to generation on next period"
        }
        _ <- Either.raiseWhen(blockchain.isConflict(newBlockHeight, address)) {
          s"$address is conflict on $newBlockHeight. Try to commit to generation on next period"
        }
      } yield balance

      def retryReasons(balance: Long) = for {
        _ <- checkQuorumAvailable()
        validBlockDelay <- pos
          .getValidBlockDelay(height, vrfKey, refBaseTarget, balance)
          .leftMap(_.toString)
        currentTime = timeService.correctedTime()
        blockTime = math.max(
          refBlockHeader.header.timestamp + validBlockDelay,
          currentTime - 1.minute.toMillis
        )
        _ <- Either.cond(
          blockTime <= currentTime + maxTimeDrift,
          log.debug(s"Forging with $address, balance $balance, prev block $reference at $height with target $refBaseTarget"),
          s"Block time $blockTime is from the future: current time is $currentTime, MaxTimeDrift = $maxTimeDrift"
        )
        consensusData <- consensusData(blockchain, vrfKey, blockTime)
        // LightNode is active: the block carries the previous state hash
        prevStateHash                             = Some(blockchain.lastStateHash(Some(reference)))
        (unconfirmed, totalConstraint, stateHash) = packTransactionsForKeyBlock(address, reference, prevStateHash)
        block <- Block
          .buildAndSign(
            blockTime,
            reference,
            consensusData.baseTarget,
            consensusData.generationSignature,
            unconfirmed,
            signingKey,
            blockFeatures(blockchain),
            if (blockchain.supportsLightNodeBlockFields(newBlockHeight.toInt)) stateHash else None,
            challengedHeader = None,
            finalizationVoting = None // Haven't voted in a key block
          )
          .leftMap(_.err)
      } yield ForgeAttemptResult.Success(block, totalConstraint)

      stopReasons
        .leftMap(ForgeAttemptResult.PermanentFailure.apply)
        .flatMap { balance =>
          retryReasons(balance).leftMap(ForgeAttemptResult.TemporaryFailure.apply)
        }
    }.merge
  }

  private def checkQuorumAvailable(): Either[String, Int] =
    Right(allChannels.size())
      .ensureOr(chanCount => s"Quorum not available ($chanCount/${settings.quorum}), not forging block.")(_ >= settings.quorum)

  private def blockFeatures(blockchain: Blockchain): Seq[Short] = {
    val exclude = blockchain.approvedFeatures.keySet ++ blockchain.settings.functionalitySettings.preActivatedFeatures.keySet
    settings.supportedFeatures
      .filterNot(exclude)
      .filter(BlockchainFeatures.implemented)
      .distinct
      .sorted
  }

  def nextBlockGenerationTime(blockchain: Blockchain, signingKey: SigningKey, vrfKey: VrfKey): Either[String, Long] = {
    val balance = blockchain.generatingBalance(signingKey.toAddress)

    if (GeneratingBalanceProvider.isMiningAllowed(balance)) {
      val lastBlockHeader = blockchain.lastBlockHeader.get.header
      val blockDelayE     = pos.copy(blockchain = blockchain).getValidBlockDelay(blockchain.height, vrfKey, lastBlockHeader.baseTarget, balance)
      for {
        delay <- blockDelayE.leftMap(_.toString)
        expectedTS = delay + lastBlockHeader.timestamp
        _ <- Either.raiseUnless(0 < expectedTS && expectedTS < Long.MaxValue)(s"Invalid next block generation time: $expectedTS")
      } yield expectedTS
    } else
      Left(
        s"Balance $balance of ${signingKey.toAddress} is lower than required for generation: ${GeneratingBalanceProvider.MinimalEffectiveBalanceForGenerator2}"
      )
  }

  private def nextBlockGenOffsetWithConditions(signingKey: SigningKey, vrfKey: VrfKey, blockchain: Blockchain): Either[String, FiniteDuration] = {
    val height      = blockchain.height
    val lastBlock   = blockchain.lastBlockHeader.get
    val prevBlockTs = lastBlock.header.timestamp
    for {
      _           <- checkAge(height, prevBlockTs)
      nextBlockTs <- nextBlockGenerationTime(blockchain, signingKey, vrfKey)
      minNextBlockTs  = if (height == 1) 0L else prevBlockTs + settings.minimalBlockGenerationOffset.toMillis
      adjustedBlockTs = nextBlockTs.max(minNextBlockTs)
      offset          = 0L.max(adjustedBlockTs - timeService.correctedTime()).millis
    } yield offset
  }

  /** @param baseBlockchain If specified - wait for last block appended
    */
  private[mining] def generateBlockTask(signingKey: SigningKey, vrfKey: VrfKey, baseBlockchain: Option[Blockchain]): Task[Unit] = {
    (for {
      offset <- nextBlockGenOffsetWithConditions(signingKey, vrfKey, baseBlockchain.getOrElse(blockchainUpdater))
      quorumAvailable = checkQuorumAvailable().isRight
    } yield {
      if (quorumAvailable) offset
      else offset.max(settings.noQuorumMiningDelay)
    }) match {
      case Right(offset) =>
        val waitBlockId    = baseBlockchain.flatMap(_.lastBlockId)
        val waitBlockIdStr = waitBlockId.fold("")(id => s" with waiting $id")
        log.debug(
          f"Next attempt for acc=${signingKey.toAddress} in ${offset.toUnit(SECONDS)}%.3f seconds (${LocalTime.now().plusNanos(offset.toNanos)})$waitBlockIdStr"
        )

        val waitBlockAppendedTask = waitBlockId match {
          case Some(blockId) =>
            def waitUntilBlockAppended(block: BlockId): Task[Unit] =
              if (blockchainUpdater.contains(block)) Task.unit
              else Task.defer(waitUntilBlockAppended(block)).delayExecution(1 seconds)

            waitUntilBlockAppended(blockId)

          case None => Task.unit
        }

        def appendTask(block: Block, totalConstraint: MiningConstraint) = // TODO: accept blockAppender instead all these dependencies?
          BlockAppender(blockchainUpdater, timeService, utx, pos, blockEndorser, appenderScheduler)(block, None).flatMap {
            case Left(BlockFromFuture(_, _)) => // Time was corrected, retry
              generateBlockTask(signingKey, vrfKey, None)

            case Left(err) =>
              Task.raiseError(new RuntimeException(err.toString))

            case Right(Applied(score = score)) =>
              log.debug(s"Forged and applied $block with cumulative score $score")
              BlockStats.mined(block, blockchainUpdater.height)
              if (blockchainUpdater.isLastBlockId(block.id())) {
                allChannels.broadcast(BlockForged(block))
                if (!totalConstraint.isFull) startMicroBlockMining(signingKey, block, totalConstraint)
              }
              Task.unit

            case Right(Ignored) =>
              Task.raiseError(new RuntimeException("Newly created block has already been appended, should not happen"))
          }.uncancelable

        for {
          elapsed <- waitBlockAppendedTask.timed.map(_._1)
          newOffset = (offset - elapsed).max(Duration.Zero)

          _      <- Task.sleep(newOffset)
          result <- Task(forgeBlock(signingKey, vrfKey)).executeOn(minerScheduler)

          _ <- result match {
            case ForgeAttemptResult.Success(block, restConstraint) =>
              appendTask(block, restConstraint)

            case ForgeAttemptResult.TemporaryFailure(err) =>
              log.debug(s"No block generated because $err, retrying")
              generateBlockTask(signingKey, vrfKey, None)

            case ForgeAttemptResult.PermanentFailure(err) =>
              log.debug(s"No block generated because $err, stopping")
              Task.unit
          }
        } yield ()

      case Left(err) =>
        log.debug(s"Not scheduling block mining because $err")
        debugStateRef = MinerDebugInfo.Error(err)
        Task.unit
    }
  }

  private def startMicroBlockMining(
      signingKey: SigningKey,
      lastBlock: Block,
      restTotalConstraint: MiningConstraint
  ): Unit = {
    Miner.microMiningStarted.increment()
    microBlockAttempt := microBlockMiner
      .generateMicroBlockSequence(signingKey, lastBlock, restTotalConstraint, 0)
      .runAsyncLogErr(using minerScheduler)
    log.trace(s"MicroBlock mining scheduled for acc=${signingKey.toAddress}")
  }

  // noinspection TypeAnnotation,ScalaStyle
  private object metrics {
    val blockBuildTimeStats = Kamon.timer("miner.pack-and-forge-block-time").withoutTags()
  }
}

object Miner {
  private[mining] val blockMiningStarted = Kamon.counter("block-mining-started").withoutTags()
  private[mining] val microMiningStarted = Kamon.counter("micro-mining-started").withoutTags()

  val MaxTransactionsPerMicroblock: Int = 500

  val StrictDisabledMiner: Miner & MinerDebugInfo = new Miner with MinerDebugInfo {
    override def scheduleMining(baseBlockchain: Option[Blockchain], cancelMicroBlockMining: Boolean): Unit = {}
    override val state: MinerDebugInfo.State                                                               = MinerDebugInfo.Disabled
    override def nextBlockGenerationOffsets: Map[Address, Either[String, FiniteDuration]]                  = Map.empty
  }

  def forwardTo(underlying: => Miner): Miner = { (blockchain: Option[Blockchain], cancelMicroBlockMining: Boolean) =>
    underlying.scheduleMining(blockchain, cancelMicroBlockMining)
  }
}
