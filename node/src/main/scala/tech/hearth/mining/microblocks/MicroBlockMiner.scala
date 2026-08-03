package tech.hearth.mining.microblocks

import tech.hearth.block.Block
import tech.hearth.mining.{MinerDebugInfo, MiningConstraint}
import tech.hearth.settings.MinerSettings
import tech.hearth.state.{Blockchain, EndorsementStorage}
import tech.hearth.transaction.BlockchainUpdater
import tech.hearth.utx.UtxPool
import io.netty.channel.group.ChannelGroup
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable
import tech.hearth.crypto.SigningKey

trait MicroBlockMiner {
  def generateMicroBlockSequence(
      signingKey: SigningKey,
      accumulatedBlock: Block,
      restTotalConstraint: MiningConstraint,
      lastMicroBlock: Long
  ): Task[Unit]
}

object MicroBlockMiner {
  def apply(
      setDebugState: MinerDebugInfo.State => Unit,
      allChannels: ChannelGroup,
      blockchainUpdater: BlockchainUpdater & Blockchain,
      utx: UtxPool,
      endorsementStorage: EndorsementStorage,
      settings: MinerSettings,
      minerScheduler: Scheduler,
      appenderScheduler: Scheduler,
      transactionAdded: Observable[Unit]
  ): MicroBlockMiner =
    new MicroBlockMinerImpl(
      setDebugState,
      allChannels,
      blockchainUpdater,
      utx,
      endorsementStorage,
      settings,
      minerScheduler,
      appenderScheduler,
      transactionAdded
    )
}
