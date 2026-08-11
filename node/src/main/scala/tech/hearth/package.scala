package tech

import cats.syntax.either.*
import com.typesafe.scalalogging.Logger
import tech.hearth.block.Block
import tech.hearth.lang.ValidationError
import tech.hearth.mining.Miner
import tech.hearth.settings.HearthSettings
import tech.hearth.state.Blockchain
import tech.hearth.transaction.BlockchainUpdater
import tech.hearth.transaction.TxValidationError.GenericError
import org.slf4j.LoggerFactory

import java.time.Instant

package object hearth {
  private lazy val logger: Logger =
    Logger(LoggerFactory.getLogger(this.getClass.getName))
  private def checkOrAppend(
      block: Block,
      blockchainUpdater: Blockchain & BlockchainUpdater,
      miner: Miner
  ): Either[ValidationError, Unit] =
    if (blockchainUpdater.isEmpty) {
      blockchainUpdater.processBlock(block, block.header.generationSignature, snapshot = None, generatorSet = Seq.empty).map { _ =>
        logger.info(
          s"Genesis block ${block.id()} (generated at ${Instant.ofEpochMilli(block.header.timestamp)}) has been added to the state"
        )
      }
    } else
      Either
        .raiseUnless(
          blockchainUpdater.blockHeader(1).exists(_.id() == block.id())
        )(GenericError("Mismatched genesis blocks in configuration and blockchain"))
        .map(_ => miner.scheduleMining())

  def checkGenesis(settings: HearthSettings, blockchainUpdater: Blockchain & BlockchainUpdater, miner: Miner): Unit = {
    (for {
      block <- Block.genesis(settings.blockchainSettings)
      _     <- checkOrAppend(block, blockchainUpdater, miner)
    } yield ()).left
      .foreach { e =>
        logger.error("INCORRECT NODE CONFIGURATION!!! NODE STOPPED BECAUSE OF THE FOLLOWING ERROR:")
        logger.error(e.toString)
        tech.hearth.utils.forceStopApplication()
      }
  }
}
