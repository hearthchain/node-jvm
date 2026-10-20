package tech.hearth.finalization

import tech.hearth.block.Block
import tech.hearth.db.WithState.AddrWithBalance
import tech.hearth.history.Domain
import tech.hearth.network.ExtensionBlocks
import tech.hearth.network.RxExtensionLoader.ApplyExtensionResult
import tech.hearth.state.{GeneratorIndex, Height}
import tech.hearth.test.DomainPresets.*
import tech.hearth.transaction.TxHelpers
import io.netty.channel.embedded.EmbeddedChannel
import monix.execution.Scheduler.Implicits.global

import scala.concurrent.duration.*

class ExtensionAfterFinalizationSpec extends BaseFinalizationSpec {
  private val node0Acc = TxHelpers.signer(0)
  private val node1Acc = TxHelpers.signer(1)
  private val allNodes = Seq(node0Acc, node1Acc)

  private val settings = DomainPresets.DeterministicFinality
    .copy(minerSettings = DomainPresets.DeterministicFinality.minerSettings.copy(quorum = 0))
    .configure(_.copy(generationPeriodLength = 3))

  private def appendExtension(d: Domain, blocks: Block*): ApplyExtensionResult =
    d.extensionAppender()(ExtensionBlocks(d.blockchain.score + 1, blocks, Map.empty, new EmbeddedChannel())).runSyncUnsafe(60.seconds)

  /** Finalizes height 3 at height 5, returning a competing block built while the tip was at `forkAt`, 2 or 3. */
  private def finalizeHeight3(d: Domain, forkAt: Int): Block = {
    require(forkAt == 2 || forkAt == 3, s"Can only fork at height 2 or 3, got $forkAt")
    def forkHere(): Option[Block] = Option.when(d.blockchain.height == forkAt)(d.createBlock(generator = node0Acc, strictTime = true))

    val genesisBlockId = d.blockchain.lastBlockId.value
    d.appendBlock()
    val forkAt2 = forkHere()

    val block3 = d.createBlock(txs = allNodes.map(x => TxHelpers.commitToGeneration(Height(4), x)), generator = node1Acc)
    d.appendBlock(block3)
    val forkAt3 = forkHere()

    d.appender.appendBlock(
      d.createBlock(
        generator = node1Acc,
        strictTime = true,
        finalizationVoting = Some(
          mkFinalizationVoting(valid = Seq(GeneratorIndex(0)))
            .signed(endorsedId = block3.id(), finalizedId = genesisBlockId, validEndorsers = node0Acc)
        )
      )
    )
    d.appender.appendBlock(d.createBlock(generator = node1Acc, strictTime = true))
    d.allFinalizedHeightIs(3)
    forkAt2.orElse(forkAt3).value
  }

  "an extension forking below the finalized height is rejected without a rollback" in
    withDomain(settings, AddrWithBalance.enoughBalances(allNodes*), generators = allNodes) { d =>
      val fork3 = finalizeHeight3(d, forkAt = 2)
      val tip   = d.blockchain.lastBlockId

      appendExtension(d, fork3).left.value.toString should include("below the sync boundary 3")
      d.blockchain.height shouldBe 5
      d.blockchain.lastBlockId shouldBe tip
    }

  "an extension forking from the finalized block itself is applied" in
    withDomain(settings, AddrWithBalance.enoughBalances(allNodes*), generators = allNodes) { d =>
      val fork4 = finalizeHeight3(d, forkAt = 3)
      d.testTime.setTime(fork4.header.timestamp.max(d.testTime.correctedTime()))

      appendExtension(d, fork4) shouldBe a[Right[?, ?]]
      d.blockchain.height shouldBe 4
      d.blockchain.lastBlockId.value shouldBe fork4.id()
    }

  "a manual rollback still crosses the finalized height" in
    withDomain(settings, AddrWithBalance.enoughBalances(allNodes*), generators = allNodes) { d =>
      finalizeHeight3(d, forkAt = 2)
      val block2 = d.blockchain.blockId(2).value

      d.blockchain.removeAfter(block2) shouldBe a[Right[?, ?]]
      d.blockchain.height shouldBe 2
      d.finalizedHeightIs(1)
    }
}
