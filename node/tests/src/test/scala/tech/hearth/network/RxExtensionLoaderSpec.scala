package tech.hearth.network

import tech.hearth.block.Block
import tech.hearth.common.state.ByteStr
import tech.hearth.lang.ValidationError
import tech.hearth.network.RxScoreObserver.ChannelClosedAndSyncWith
import tech.hearth.test.FreeSpec
import tech.hearth.transaction.TxValidationError.GenericError
import tech.hearth.{BlockGen, RxScheduler}
import io.netty.channel.{Channel, ChannelFuture}
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.channel.local.LocalChannel
import monix.eval.{Coeval, Task}
import monix.reactive.Observable
import monix.reactive.subjects.PublishSubject as PS

import scala.concurrent.duration.*
import scala.concurrent.{Future, Promise}
import scala.util.Try

class RxExtensionLoaderSpec extends FreeSpec with RxScheduler with BlockGen {
  import RxExtensionLoaderSpec.*

  val MaxRollback = 10
  type Applier = (Channel, ExtensionBlocks) => Task[Either[ValidationError, Option[BigInt]]]
  val simpleApplier: Applier = (_, _) => Task(Right(Some(0)))
  // Keeps the applier busy so the loader stays in ApplierState.Applying, which is what makes a request optimistic.
  val neverApplier: Applier = (_, _) => Task.never

  override def testSchedulerName: String = "test-rx-extension-loader"

  private def withExtensionLoader(
      lastBlockIds: Seq[ByteStr] = Seq.empty,
      timeOut: FiniteDuration = 1.day,
      cacheTimeout: FiniteDuration = 3.minute,
      applier: Applier = simpleApplier,
      maxRollback: Int = MaxRollback
  )(
      f: (
          InMemoryInvalidBlockStorage,
          PS[(Channel, Block)],
          PS[(Channel, BlockIds)],
          PS[ChannelClosedAndSyncWith],
          Observable[(Channel, Block, Option[BlockSnapshotResponse])]
      ) => Any
  ) = {
    val blocks          = PS[(Channel, Block)]()
    val sigs            = PS[(Channel, BlockIds)]()
    val ccsw            = PS[ChannelClosedAndSyncWith]()
    val snapshots       = PS[(Channel, BlockSnapshotResponse)]()
    val timeout         = PS[Channel]()
    val op              = PeerDatabase.NoOp
    val invBlockStorage = new InMemoryInvalidBlockStorage
    val (singleBlocks, _, _) =
      RxExtensionLoader(
        timeOut,
        cacheTimeout,
        isLightMode = false,
        blacklistOnScoreMismatch = false,
        Coeval(lastBlockIds.reverse.take(maxRollback)),
        op,
        invBlockStorage,
        blocks,
        sigs,
        snapshots,
        ccsw,
        testScheduler,
        timeout
      )(
        applier
      )

    try {
      f(invBlockStorage, blocks, sigs, ccsw, singleBlocks)
    } finally {
      blocks.onComplete()
      sigs.onComplete()
      ccsw.onComplete()
      timeout.onComplete()
    }
  }

  "should propagate unexpected block" in withExtensionLoader() { (_, blocks, _, _, singleBlocks) =>
    val ch              = new LocalChannel()
    val newSingleBlocks = newItems(singleBlocks)
    val block           = randomSignerBlockGen.sample.get

    test(for {
      _ <- send(blocks)((ch, block))
    } yield {
      newSingleBlocks().last shouldBe ((ch, block, None))
    })
  }

  "should blacklist GetBlockIds timeout" in withExtensionLoader(Seq.tabulate(100)(ref), 1.millis) { (_, _, _, ccsw, _) =>
    val ch = new EmbeddedChannel()
    test(for {
      _ <- send(ccsw, timeout = 1000)(ChannelClosedAndSyncWith(None, Some(BestChannel(ch, 1: BigInt))))
    } yield {
      ch.isOpen shouldBe false
    })
  }

  "should request GetBlockIds and then span blocks from peer" in withExtensionLoader(Seq.tabulate(100)(ref)) { (_, _, sigs, ccsw, _) =>
    val ch                   = new EmbeddedChannel()
    val totalBlocksInHistory = 100
    test(for {
      _ <- send(ccsw)(ChannelClosedAndSyncWith(None, Some(BestChannel(ch, 1: BigInt))))
      _ = ch.readOutbound[GetBlockIds].ids shouldBe Range(totalBlocksInHistory - MaxRollback, totalBlocksInHistory).map(ref).reverse
      _ <- send(sigs)((ch, BlockIds(Range(97, 102).map(ref))))
    } yield {
      ch.readOutbound[GetBlock].id shouldBe ref(100)
      ch.readOutbound[GetBlock].id shouldBe ref(101)
    })
  }

  "should blacklist if received BlockIds contains banned id" in withExtensionLoader(Seq.tabulate(100)(ref), 1.millis) {
    (invBlockStorage, _, sigs, ccsw, _) =>
      invBlockStorage.add(ref(105), GenericError("Some error"))
      val ch = new EmbeddedChannel()
      test(for {
        _ <- send(ccsw)(ChannelClosedAndSyncWith(None, Some(BestChannel(ch, 1: BigInt))))
        _ = ch.readOutbound[GetBlockIds].ids.size shouldBe MaxRollback
        _ <- send(sigs)((ch, BlockIds(Range(99, 110).map(ref))))
      } yield {
        ch.isOpen shouldBe false
      })
  }

  "should blacklist if some blocks didn't arrive in due time" in withExtensionLoader(Seq.tabulate(100)(ref), 1.second) { (_, blocks, sigs, ccsw, _) =>
    val ch = new EmbeddedChannel()

    test(for {
      _ <- send(ccsw)(ChannelClosedAndSyncWith(None, Some(BestChannel(ch, 1: BigInt))))
      _ = ch.readOutbound[GetBlockIds].ids.size shouldBe MaxRollback
      _ <- send(sigs)((ch, BlockIds(Range(97, 102).map(ref))))
      _ = ch.readOutbound[GetBlock].id shouldBe ref(100)
      _ = ch.readOutbound[GetBlock].id shouldBe ref(101)
      _ <- send(blocks)((ch, block(100)))
      _ <- ch.closeF()
    } yield ())
  }

  "should process received extension" in {
    @volatile var applied = false
    val successfulApplier: Applier = (_, _) =>
      Task {
        applied = true
        Right(None)
      }
    val allBlocks = Seq.tabulate(102)(block)
    withExtensionLoader(allBlocks.view.take(100).map(_.id()).toSeq, applier = successfulApplier) { (_, blocks, sigs, ccsw, _) =>
      val ch = new EmbeddedChannel()
      test(for {
        _ <- send(ccsw)(ChannelClosedAndSyncWith(None, Some(BestChannel(ch, 1: BigInt))))
        _ = ch.readOutbound[GetBlockIds].ids.size shouldBe MaxRollback
        _ <- send(sigs)((ch, BlockIds(allBlocks.view.takeRight(5).map(_.id()).toSeq)))
        _ = ch.readOutbound[GetBlock].id shouldBe allBlocks(100).id()
        _ = ch.readOutbound[GetBlock].id shouldBe allBlocks(101).id()
        _ <- send(blocks)((ch, allBlocks(100)))
        _ <- send(blocks)((ch, allBlocks(101)))
      } yield {
        applied shouldBe true
      })
    }
  }

  // The extension's own ids are liquid and expire; only persisted history guarantees a resolvable anchor.
  "should anchor an optimistic block id request in local history" in {
    val allBlocks = Seq.tabulate(102)(block)
    withExtensionLoader(allBlocks.view.take(100).map(_.id()).toSeq, applier = neverApplier) { (_, blocks, sigs, ccsw, _) =>
      val ch = new EmbeddedChannel()
      test(for {
        _ <- send(ccsw)(ChannelClosedAndSyncWith(None, Some(BestChannel(ch, 1: BigInt))))
        _ = ch.readOutbound[GetBlockIds].ids.size shouldBe MaxRollback
        _ <- send(sigs)((ch, BlockIds(allBlocks.view.takeRight(5).map(_.id()).toSeq)))
        _ = ch.readOutbound[GetBlock].id shouldBe allBlocks(100).id()
        _ = ch.readOutbound[GetBlock].id shouldBe allBlocks(101).id()
        _ <- send(blocks)((ch, allBlocks(100)))
        _ <- send(blocks)((ch, allBlocks(101)))
      } yield {
        val optimistic = ch.readOutbound[GetBlockIds].ids
        optimistic.take(2) shouldBe Seq(allBlocks(101).id(), allBlocks(100).id())
        optimistic should contain(allBlocks(99).id())
      })
    }
  }

  // GetBlockIds is capped at BlockIdSeqSpec.MaxIds on the wire, and LegacyFrameCodec makes the *receiver* blacklist
  // the sender of an oversize frame. lastBlockIds can be max-rollback + 1 long and an extension up to max-rollback,
  // so concatenating them unguarded overruns the cap exactly when a lagging node is catching up.
  "should keep an optimistic block id request within the wire limit" in {
    val allBlocks = Seq.tabulate(BlockIdSeqSpec.MaxIds + 2)(block)
    val history   = allBlocks.view.take(BlockIdSeqSpec.MaxIds).map(_.id()).toSeq
    withExtensionLoader(history, applier = neverApplier, maxRollback = BlockIdSeqSpec.MaxIds - 1) { (_, blocks, sigs, ccsw, _) =>
      val ch   = new EmbeddedChannel()
      val last = allBlocks.takeRight(2)
      test(for {
        _ <- send(ccsw)(ChannelClosedAndSyncWith(None, Some(BestChannel(ch, 1: BigInt))))
        _ = ch.readOutbound[GetBlockIds].ids.size shouldBe BlockIdSeqSpec.MaxIds - 1
        _ <- send(sigs)((ch, BlockIds(allBlocks.view.takeRight(5).map(_.id()).toSeq)))
        _ = ch.readOutbound[GetBlock]
        _ = ch.readOutbound[GetBlock]
        _ <- send(blocks)((ch, last.head))
        _ <- send(blocks)((ch, last.last))
      } yield {
        val anchors    = history.reverse.take(BlockIdSeqSpec.MaxIds - 1)
        val optimistic = ch.readOutbound[GetBlockIds].ids
        optimistic.size shouldBe BlockIdSeqSpec.MaxIds
        // the persisted anchors guarantee a non-empty reply, so the extension ids are the ones that give way
        optimistic.takeRight(anchors.size) shouldBe anchors
        optimistic.head shouldBe last.last.id()
      })
    }
  }

  "should blacklist peer after receiving empty block id list" in {
    withExtensionLoader(Seq.tabulate(100)(ref)) { (_, _, sigs, ccsw, _) =>
      val ch = new EmbeddedChannel()

      test(for {
        _ <- send(ccsw)(ChannelClosedAndSyncWith(None, Some(BestChannel(ch, 1: BigInt))))
        _ = ch.readOutbound[GetBlockIds].ids.size shouldBe MaxRollback
        _ <- send(sigs)((ch, BlockIds(Seq.empty)))
        _ <- ch.closeF()
      } yield ())
    }
  }
}

object RxExtensionLoaderSpec {
  implicit class ChannelExt(val channel: Channel) extends AnyVal {
    def closeF(): Future[Unit] = {
      val closePromise = Promise[Unit]()
      channel.closeFuture().addListener((future: ChannelFuture) => closePromise.complete(Try(future.get())))
      closePromise.future
    }
  }
}
