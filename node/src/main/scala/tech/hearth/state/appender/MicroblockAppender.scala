package tech.hearth.state.appender

import cats.data.EitherT
import cats.syntax.traverse.*
import tech.hearth.block.Block.BlockId
import tech.hearth.block.{MicroBlock, MicroBlockSnapshot}
import tech.hearth.lang.ValidationError
import tech.hearth.metrics.*
import tech.hearth.mining.BlockChallenger
import tech.hearth.network.*
import tech.hearth.network.MicroBlockSynchronizer.MicroblockData
import tech.hearth.protobuf.PBSnapshots
import tech.hearth.state.{Blockchain, Height}
import tech.hearth.transaction.BlockchainUpdater
import tech.hearth.transaction.TxValidationError.{InvalidSignature, InvalidStateHash}
import tech.hearth.utils.ScorexLogging
import tech.hearth.utx.UtxPool
import io.netty.channel.Channel
import io.netty.channel.group.ChannelGroup
import kamon.Kamon
import monix.eval.Task
import monix.execution.Scheduler

import scala.util.{Left, Right}

object MicroblockAppender extends ScorexLogging {
  private val microblockProcessingTimeStats = Kamon.timer("microblock-appender.processing-time").withoutTags()

  def apply(blockchainUpdater: BlockchainUpdater & Blockchain, utxStorage: UtxPool, scheduler: Scheduler, verify: Boolean = true)(
      microBlock: MicroBlock,
      snapshot: Option[MicroBlockSnapshot]
  ): Task[Either[ValidationError, BlockId]] =
    Task(microblockProcessingTimeStats.measureSuccessful {
      blockchainUpdater
        .processMicroBlock(microBlock, snapshot, verify)
        .map { totalBlockId =>
          if (microBlock.transactionData.nonEmpty) {
            utxStorage.removeAll(microBlock.transactionData)
            log.trace(
              s"Removing txs of ${microBlock.stringRepr(totalBlockId)} ${microBlock.transactionData.map(_.id()).mkString("(", ", ", ")")} from UTX pool"
            )
          }

          utxStorage.scheduleCleanup()
          totalBlockId
        }
    }).executeOn(scheduler)

  def apply(
      blockchainUpdater: BlockchainUpdater & Blockchain,
      utxStorage: UtxPool,
      allChannels: ChannelGroup,
      peerDatabase: PeerDatabase,
      blockChallenger: Option[BlockChallenger],
      scheduler: Scheduler
  )(ch: Channel, md: MicroblockData, snapshot: Option[(Channel, MicroBlockSnapshotResponse)]): Task[Unit] = {
    import md.microBlock
    val microblockTotalResBlockSig = microBlock.wholeBlockSignature
    (for {
      _ <- EitherT(Task.now(microBlock.signaturesValid()))
      microBlockSnapshot = snapshot
        .map { case (_, mbs) =>
          microBlock.transactionData.zip(mbs.snapshots).map { case (tx, pbs) =>
            PBSnapshots.fromProtobuf(pbs, tx.id(), Height(blockchainUpdater.height))
          }
        }
        .map(ss => MicroBlockSnapshot(microblockTotalResBlockSig, ss))

      blockId <- EitherT(apply(blockchainUpdater, utxStorage, scheduler)(microBlock, microBlockSnapshot))
    } yield blockId).value.flatMap {
      case Right(blockId) =>
        Task {
          md.invOpt match {
            case Some(mi) => allChannels.broadcast(mi, except = md.microblockOwners())
            case None     => log.warn(s"${id(ch)} Not broadcasting MicroBlockInv")
          }
          BlockStats.applied(microBlock, blockId)
        }
      case Left(is: InvalidSignature) =>
        Task {
          val idOpt = md.invOpt.map(_.totalBlockId)
          peerDatabase.blacklistAndClose(ch, s"Could not append microblock ${idOpt.getOrElse(s"(sig=$microblockTotalResBlockSig)")}: $is")
        }
      case Left(ish: InvalidStateHash) =>
        val channelToBlacklist = snapshot.map(_._1).getOrElse(ch)
        val idOpt              = md.invOpt.map(_.totalBlockId)
        peerDatabase.blacklistAndClose(
          channelToBlacklist,
          s"Could not append microblock ${idOpt.getOrElse(s"(sig=$microblockTotalResBlockSig)")}: $ish"
        )
        md.invOpt.foreach(mi => BlockStats.declined(mi.totalBlockId))

        blockChallenger.traverse(_.challengeMicroblock(md, channelToBlacklist).executeOn(scheduler)).void

      case Left(ve) =>
        Task {
          md.invOpt.foreach(mi => BlockStats.declined(mi.totalBlockId))
          val idOpt = md.invOpt.map(_.totalBlockId)
          log.debug(s"${id(ch)} Could not append microblock ${idOpt.getOrElse(s"(sig=$microblockTotalResBlockSig)")}: $ve")
        }
    }
  }
}
