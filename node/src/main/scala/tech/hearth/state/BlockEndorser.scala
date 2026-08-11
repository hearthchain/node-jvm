package tech.hearth.state

import com.typesafe.scalalogging.StrictLogging
import tech.hearth.block.BlockEndorsement
import tech.hearth.mining.GeneratorKeys
import tech.hearth.network.{ChannelGroupExt, EndorseBlock}
import io.netty.channel.group.ChannelGroup

trait BlockEndorser {

  /** Voting happens
    *   for block at endorserHeight
    *   with finalizedBlock at votingHeight
    *   by generators, committed at votingHeight
    */
  def vote(generatorSet: GeneratorSet): Unit
}

object BlockEndorser {
  object Disabled extends BlockEndorser {
    override def vote(generatorSet: GeneratorSet): Unit = {}
  }

  /** @param generatorKeys
    *   The accounts this node generates with. An endorsement is signed by the BLS key an account committed, and those
    *   keys are configured in `hearth.miner.accounts` - the wallet holds no BLS key and knows nothing about the accounts
    *   the miner was configured with, so asking it which committed generators are ours answers for the wrong set.
    */
  class InMemory(
      maxSyncRollbackLength: Int,
      blockchain: Blockchain,
      generatorKeys: GeneratorKeys,
      endorsementStorage: EndorsementStorage,
      allChannels: ChannelGroup
  ) extends BlockEndorser,
        StrictLogging {
    override def vote(generatorSet: GeneratorSet): Unit = {
      val votingHeight   = Height(blockchain.height)
      val endorsedHeight = votingHeight - 1
      if (endorsedHeight > GenesisBlockHeight) for {
        votingPeriod <- blockchain.generationPeriodOf(votingHeight).toSeq

        votingBlockHeader   <- blockchain.blockHeader(votingHeight.toInt).toSeq
        endorsedBlockHeader <- blockchain.blockHeader(endorsedHeight.toInt).toSeq

        finalizedHeight = blockchain.finalizedHeightOrFallback(maxSyncRollbackLength)
        if endorsedHeight > finalizedHeight

        finalizedId <- blockchain
          .blockId(finalizedHeight.toInt)
          .toSeq

        endorsedId = endorsedBlockHeader.id()

        committed        = blockchain.committedGenerators(votingPeriod)
        votingBlockMiner = votingBlockHeader.header.generator.toAddress
        minerIndex       = committed.indexWhere(_.address == votingBlockMiner)
        if minerIndex >= 0 // -1 means no miner among committed, impossible

        balances = generatorSet.collect {
          case x if blockchain.isGeneratingBalanceValid(votingHeight, votingBlockHeader.header, x.balance) => x.address -> x.balance
        }.toMap

        filter = {
          val normalizedEndorsers = committed.map { cg =>
            (cg.address, cg.endorserPublicKey, balances.getOrElse(cg.address, 0L))
          }.toVector

          val conflict = blockchain.conflictGenerators(votingPeriod).upTo(votingHeight)
          EndorsementFilter(
            blockchain.settings.functionalitySettings.maxValidEndorsers,
            GeneratorIndex(minerIndex),
            isMiner = generatorKeys.contains(votingBlockMiner),
            finalizedId,
            finalizedHeight,
            endorsedId,
            normalizedEndorsers,
            conflict
          )
        }
        if endorsementStorage.startVoting(filter)

        (endorserAddress, idx) <- for {
          (cg, idx) <- committed.zipWithIndex
          if idx != filter.miner.toInt // A miner doesn’t need to endorse its own blocks - a mining is already an endorsement
          if generatorKeys.contains(cg.address)
          if balances.contains(cg.address)
        } yield (cg.address, GeneratorIndex(idx))

        signature <- generatorKeys
          .signWithEndorserKey(endorserAddress, BlockEndorsement.mkMessage(finalizedId, finalizedHeight, endorsedId))
          .toSeq
        endorsement = BlockEndorsement(idx, finalizedId, finalizedHeight, endorsedId, signature)
        networkMsg  = EndorseBlock.from(endorsement)
        broadcast <- endorsementStorage.tryAdd(networkMsg) match {
          case Right(r) => Some(r)
          case Left(err) =>
            logger.warn(s"Can't add endorsement from #$idx $endorserAddress: $err")
            None
        }
        if broadcast
      } allChannels.broadcast(networkMsg)
    }
  }
}
