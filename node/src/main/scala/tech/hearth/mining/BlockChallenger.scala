package tech.hearth.mining

import cats.data.EitherT
import cats.syntax.traverse.*
import tech.hearth.account.Address
import tech.hearth.block.{Block, ChallengedHeader, FinalizationVoting}
import tech.hearth.common.state.ByteStr
import tech.hearth.consensus.PoSSelector
import tech.hearth.features.BlockchainFeatures
import tech.hearth.lang.ValidationError
import tech.hearth.metrics.BlockStats
import tech.hearth.network.*
import tech.hearth.network.MicroBlockSynchronizer.MicroblockData
import tech.hearth.settings.WavesSettings
import tech.hearth.state.BlockchainUpdaterImpl.BlockApplyResult
import tech.hearth.state.BlockchainUpdaterImpl.BlockApplyResult.Applied
import tech.hearth.state.appender.MaxTimeDrift
import tech.hearth.state.diffs.BlockDiffer
import tech.hearth.state.{BlockFee, Blockchain, SnapshotBlockchain, StateSnapshot, TxStateSnapshotHashBuilder}
import tech.hearth.transaction.TxValidationError.GenericError
import tech.hearth.transaction.{BlockchainUpdater, Transaction}
import tech.hearth.utils.{ScorexLogging, Time}
import io.netty.channel.Channel
import io.netty.channel.group.ChannelGroup
import monix.eval.Task
import tech.hearth.crypto.*

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

trait BlockChallenger {
  def challengeBlock(block: Block, ch: Channel): Task[Unit]
  def challengeMicroblock(md: MicroblockData, ch: Channel): Task[Unit]
  def pickBestAccount(accounts: Seq[((SigningKey, VrfKey), Long)]): Either[GenericError, ((SigningKey, VrfKey), Long)]
  def getChallengingAccounts(challengedMiner: Address): Either[ValidationError, Seq[((SigningKey, VrfKey), Long)]]
  def getProcessingTx(id: ByteStr): Option[Transaction]
  def allProcessingTxs: Seq[Transaction]
}

class BlockChallengerImpl(
    blockchainUpdater: BlockchainUpdater & Blockchain,
    allChannels: ChannelGroup,
    generatorKeys: GeneratorKeys,
    settings: WavesSettings,
    timeService: Time,
    pos: PoSSelector,
    appendBlock: Block => Task[Either[ValidationError, BlockApplyResult]],
    timeDrift: Long = MaxTimeDrift
) extends BlockChallenger
    with ScorexLogging {

  private val processingTxs: ConcurrentHashMap[ByteStr, Transaction] = new ConcurrentHashMap()

  override def challengeBlock(block: Block, ch: Channel): Task[Unit] = {
    log.debug(s"Challenging block $block")

    withProcessingTxs(block.transactionData) {
      (for {
        challengingBlock <- EitherT(
          createChallengingBlock(
            block,
            block.header.stateHash,
            block.signature,
            block.transactionData,
            blockchainUpdater.lastStateHash(Some(block.header.reference)),
            block.header.finalizationVoting
          )
        )
        applyResult <- EitherT(appendBlock(challengingBlock))
      } yield applyResult -> challengingBlock).value
    }.map {
      case Right((_: Applied, challengingBlock)) =>
        log.debug(s"Successfully challenged $block with $challengingBlock")
        BlockStats.challenged(challengingBlock, blockchainUpdater.height)
        if (blockchainUpdater.isLastBlockId(challengingBlock.id())) {
          allChannels.broadcast(BlockForged(challengingBlock), Some(ch))
        }
      case Right((_, challengingBlock)) => log.debug(s"Ignored challenging block $challengingBlock")
      case Left(err)                    => log.debug(s"Could not challenge $block: $err")
    }
  }

  override def challengeMicroblock(md: MicroblockData, ch: Channel): Task[Unit] = {
    val idStr = md.invOpt.map(_.totalBlockId.toString).getOrElse(s"(sig=${md.microBlock.wholeBlockSignature})")
    log.debug(s"Challenging microblock $idStr")

    (for {
      discarded <- EitherT(Task(blockchainUpdater.removeAfter(blockchainUpdater.lastBlockHeader.get.header.reference)))
      block     <- EitherT(Task(discarded.headOption.map(_._1).toRight(GenericError("Liquid block wasn't discarded"))))
      txs = block.transactionData ++ md.microBlock.transactionData
      (applyResult, challengingBlock) <- EitherT(withProcessingTxs(txs) {
        (for {
          challengingBlock <- EitherT(
            createChallengingBlock(
              block,
              md.microBlock.stateHash,
              md.microBlock.wholeBlockSignature,
              txs,
              blockchainUpdater.lastStateHash(Some(block.header.reference)),
              FinalizationVoting.combine(block.header.finalizationVoting, md.microBlock.finalizationVoting)
            )
          )
          applyResult <- EitherT(appendBlock(challengingBlock))
        } yield applyResult -> challengingBlock).value
      })
    } yield {
      applyResult match {
        case _: Applied =>
          log.debug(s"Successfully challenged microblock $idStr with $challengingBlock")
          BlockStats.challenged(challengingBlock, blockchainUpdater.height)
          if (blockchainUpdater.isLastBlockId(challengingBlock.id())) {
            allChannels.broadcast(BlockForged(challengingBlock), Some(ch))
          }
        case _ =>
          log.debug(s"Ignored challenging block $challengingBlock")
      }
    }).fold(
      err => log.debug(s"Could not challenge microblock $idStr: $err"),
      identity
    )
  }

  override def pickBestAccount(accounts: Seq[((SigningKey, VrfKey), Long)]): Either[GenericError, ((SigningKey, VrfKey), Long)] =
    accounts.minByOption(_._2).toRight(GenericError("No suitable account in wallet"))

  override def getChallengingAccounts(challengedMiner: Address): Either[ValidationError, Seq[((SigningKey, VrfKey), Long)]] = {
    lazy val challengedBalance = blockchainUpdater.generatingBalance(challengedMiner)
    generatorKeys.accounts.map(a => (a.signingKey, a.vrfKey)).traverse { case acc @ (sk, vk) =>
      val ownBalance = blockchainUpdater.generatingBalance(sk.toAddress)
      pos
        .getValidBlockDelay(
          blockchainUpdater.height,
          vk,
          blockchainUpdater.lastBlockHeader.get.header.baseTarget,
          ownBalance + challengedBalance
        )
        .map((acc, _))
    }
  }

  override def getProcessingTx(id: ByteStr): Option[Transaction] = Option(processingTxs.get(id))

  override def allProcessingTxs: Seq[Transaction] = processingTxs.values.asScala.toSeq

  private def withProcessingTxs[A](txs: Seq[Transaction])(body: Task[A]): Task[A] =
    Task(processingTxs.putAll(txs.map(tx => tx.id() -> tx).toMap.asJava))
      .bracket(_ => body)(_ => Task(processingTxs.clear()))

  private def createChallengingBlock(
      challengedBlock: Block,
      challengedStateHash: Option[ByteStr],
      challengedSignature: ByteStr,
      txs: Seq[Transaction],
      prevStateHash: ByteStr,
      challengedFinalizationVoting: Option[FinalizationVoting]
  ): Task[Either[ValidationError, Block]] = Task {
    // The challenging block takes the place of the challenged one, so it is built on the same parent
    val prevBlock = blockchainUpdater
      .heightOf(challengedBlock.header.reference)
      .flatMap(blockchainUpdater.blockHeader)
      .getOrElse(blockchainUpdater.lastBlockHeader.get)
    val prevBlockHeader = prevBlock.header

    for {
      allAccounts       <- getChallengingAccounts(challengedBlock.sender.toAddress)
      ((sk, vk), delay) <- pickBestAccount(allAccounts)
      blockTime = prevBlockHeader.timestamp + delay
      _ <- Either.cond(
        blockTime < challengedBlock.header.timestamp,
        (),
        GenericError(s"Challenging block timestamp ($blockTime) is not better than challenged block timestamp (${challengedBlock.header.timestamp})")
      )
      consensusData <- pos.consensusData(
        vk,
        blockchainUpdater.height,
        blockchainUpdater.settings.genesisSettings.averageBlockDelay,
        prevBlockHeader.baseTarget,
        prevBlockHeader.timestamp,
        blockchainUpdater.parentHeader(prevBlockHeader, 2).map(_.timestamp),
        blockTime
      )
      blockWithoutChallengeAndStateHash <- Block.buildAndSign(
        blockTime,
        challengedBlock.header.reference,
        consensusData.baseTarget,
        consensusData.generationSignature,
        txs,
        sk,
        blockFeatures(blockchainUpdater, settings),
        stateHash = None,
        challengedHeader = None,
        finalizationVoting = challengedFinalizationVoting
      )
      hitSource <- pos.validateGenerationSignature(blockWithoutChallengeAndStateHash)
      blockchainWithNewBlock = SnapshotBlockchain(
        blockchainUpdater,
        StateSnapshot.empty,
        blockWithoutChallengeAndStateHash,
        hitSource,
        BlockFee.empty,
        blockchainUpdater.computeNextReward,
        None
      )
      // The parent, so that the penalties it carries land in the initial snapshot: the differ applies the same ones
      // when this block is appended, and the state hash computed here has to match what it arrives at
      initialBlockSnapshot <- BlockDiffer.createInitialBlockSnapshot(
        blockchainUpdater,
        challengedBlock.header.reference,
        sk.toAddress,
        Some(prevBlock)
      )
      stateHash <- TxStateSnapshotHashBuilder
        .computeStateHash(
          txs,
          TxStateSnapshotHashBuilder.createHashFromSnapshot(initialBlockSnapshot, None).createHash(prevStateHash),
          initialBlockSnapshot,
          sk,
          Some(prevBlockHeader.timestamp),
          blockTime,
          isChallenging = true,
          blockchainWithNewBlock
        )
        .resultE
      challengingBlock <- Block.buildAndSign(
        blockTime,
        challengedBlock.header.reference,
        consensusData.baseTarget,
        consensusData.generationSignature,
        txs,
        sk,
        blockFeatures(blockchainUpdater, settings),
        Some(stateHash),
        Some(
          ChallengedHeader(
            challengedBlock.header.timestamp,
            challengedBlock.header.baseTarget,
            challengedBlock.header.generationSignature,
            challengedBlock.header.featureVotes,
            challengedBlock.header.generator,
            challengedStateHash,
            challengedSignature,
            challengedFinalizationVoting
          )
        ),
        finalizationVoting = None
      )
    } yield {
      log.debug(s"Forged challenging block $challengingBlock")
      challengingBlock
    }
  }.flatMap {
    case res @ Right(block) => waitForTimeAlign(block.header.timestamp, timeDrift).map(_ => res)
    case err @ Left(_)      => Task(err)
  }

  private def blockFeatures(blockchain: Blockchain, settings: WavesSettings): Seq[Short] = {
    val exclude = blockchain.approvedFeatures.keySet ++ settings.blockchainSettings.functionalitySettings.preActivatedFeatures.keySet

    settings.minerSettings.supportedFeatures
      .filterNot(exclude)
      .filter(BlockchainFeatures.implemented)
      .sorted
  }

  private def waitForTimeAlign(blockTime: Long, timeDrift: Long): Task[Unit] =
    Task {
      val currentTime = timeService.correctedTime()
      blockTime - currentTime - timeDrift
    }.flatMap { timeDiff =>
      if (timeDiff > 0) {
        Task.sleep(timeDiff.millis)
      } else {
        Task.unit
      }
    }
}
