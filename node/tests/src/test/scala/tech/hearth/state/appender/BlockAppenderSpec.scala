package tech.hearth.state.appender

import tech.hearth.common.utils.EitherExt2.*
import tech.hearth.db.WithDomain
import tech.hearth.common.state.ByteStr
import tech.hearth.db.WithState.AddrWithBalance
import tech.hearth.network.{MessageCodec, PBBlockSpec, PeerDatabase, RawBytes}
import tech.hearth.state.{BlockEndorser, Height}
import tech.hearth.state.BlockchainUpdaterImpl.BlockApplyResult
import tech.hearth.state.BlockchainUpdaterImpl.BlockApplyResult.Ignored
import tech.hearth.test.DomainPresets.configure
import tech.hearth.test.{FlatSpec, TestTime}
import tech.hearth.transaction.TxHelpers
import tech.hearth.utils.Schedulers
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.channel.group.DefaultChannelGroup
import io.netty.util.concurrent.GlobalEventExecutor
import monix.execution.Scheduler.Implicits.global
import monix.execution.schedulers.SchedulerService
import org.scalatest.BeforeAndAfterAll

class BlockAppenderSpec extends FlatSpec with WithDomain with BeforeAndAfterAll {
  private val appenderScheduler: SchedulerService = Schedulers.singleThread("appender")
  private val testTime: TestTime                  = TestTime()

  "BlockAppender" should "not broadcast block that wasn't applied to state" in {
    val sender = TxHelpers.signer(1)
    withDomain(DomainPresets.ConsensusImprovements, AddrWithBalance.enoughBalances(sender), generators = Seq(sender)) { d =>
      val channels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE)
      val channel1 = new EmbeddedChannel(new MessageCodec(PeerDatabase.NoOp))
      val channel2 = new EmbeddedChannel(new MessageCodec(PeerDatabase.NoOp))
      channels.add(channel1)
      channels.add(channel2)
      val appender = BlockAppender(
        d.blockchain,
        testTime,
        d.utxPool,
        d.posSelector,
        channels,
        PeerDatabase.NoOp,
        None,
        BlockEndorser.Disabled,
        appenderScheduler
      )(channel2, _, None)

      val block = d.createBlock(generator = sender, strictTime = true)

      testTime.setTime(block.header.timestamp)
      appender(block).runSyncUnsafe(scala.concurrent.duration.Duration(60, "s"))

      channel1.outboundMessages().isEmpty shouldBe false
      PBBlockSpec.deserializeData(channel1.readOutbound[RawBytes]().data).get shouldBe block

      d.blockchainUpdater
        .processBlock(
          block,
          // The proof verifies against the VRF key the generator committed, which is the one derived for `sender`
          tech.hearth.crypto
            .verifyVRF(block.header.generationSignature, d.blockchain.hitSource(1).get.arr, ByteStr(TxHelpers.vrfKeyOf(sender).publicKey()))
            .explicitGet(),
          snapshot = None,
          generatorSet = Seq.empty
        )
        .explicitGet() shouldBe Ignored

      appender(block).runSyncUnsafe(scala.concurrent.duration.Duration(60, "s"))
      channel1.outboundMessages().isEmpty shouldBe true
    }
  }

  "BlockAppender" should "ignore a block if it is already appended" in {
    val miner = TxHelpers.signer(0)
    withDomain(DomainPresets.ConsensusImprovements, AddrWithBalance.enoughBalances(miner)) { d =>
      val b        = d.createBlock(strictTime = true, generator = miner)
      def append() = d.appender.appendBlockWithoutFallback(b).explicitGet()

      append() shouldBe a[BlockApplyResult.Applied]
      append() shouldBe BlockApplyResult.Ignored
    }
  }

  "BlockAppender" should "accept the first block of a period from a generator committed only for that period" in {
    val genesisGenerator = TxHelpers.signer(0)
    val nextGenerator    = TxHelpers.signer(1)
    // Periods [1, 2] and [3, 4]: height 3 is the first block the next period's committee generates, and the only
    // height at which the block's own period and its parent's differ
    withDomain(
      DomainPresets.DeterministicFinality.configure(_.copy(generationPeriodLength = 2)),
      AddrWithBalance.enoughBalances(genesisGenerator, nextGenerator),
      generators = Seq(genesisGenerator)
    ) { d =>
      d.appender
        .appendBlockWithoutFallback(
          d.createBlock(Seq(TxHelpers.commitToGeneration(Height(3), nextGenerator)), strictTime = true, generator = genesisGenerator)
        )
        .explicitGet() shouldBe a[BlockApplyResult.Applied]
      d.blockchain.height shouldBe 2

      d.appender
        .appendBlockWithoutFallback(d.createBlock(strictTime = true, generator = nextGenerator))
        .explicitGet() shouldBe a[BlockApplyResult.Applied]
      d.blockchain.height shouldBe 3
      d.lastBlock.header.generator.toAddress shouldBe nextGenerator.toAddress
    }
  }

  override protected def afterAll(): Unit = {
    super.afterAll()
    appenderScheduler.shutdown()
  }
}
