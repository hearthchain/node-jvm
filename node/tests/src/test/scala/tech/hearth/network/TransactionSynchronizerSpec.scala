package tech.hearth.network

import cats.kernel.Eq
import tech.hearth.common.state.ByteStr
import tech.hearth.db.WithDomain
import tech.hearth.settings.SynchronizationSettings.UtxSynchronizerSettings
import tech.hearth.test.DomainPresets.RideV6
import tech.hearth.test.PropSpec
import tech.hearth.transaction.TxHelpers.transfer
import tech.hearth.transaction.smart.script.trace.TracedResult
import tech.hearth.utils.Schedulers
import monix.execution.atomic.AtomicInt
import monix.reactive.Observable

import scala.concurrent.Future

class TransactionSynchronizerSpec extends PropSpec with WithDomain {
  property("synchronizer should broadcast transactions on new both microblocks and blocks") {
    withDomain(RideV6) { d =>
      val blockIds =
        Observable
          .repeatEval(d.blockchain.lastBlockId.getOrElse(ByteStr.empty))
          .distinctUntilChanged(using Eq.fromUniversalEquals)

      val tx  = transfer()
      val txs = Observable.repeatEval(tx)

      val broadcastCount = AtomicInt(0)

      val scheduler = Schedulers.fixedPool(4, "synchronizer")
      val synchronizer = TransactionSynchronizer(
        UtxSynchronizerSettings(1000000, 8, 5000, true),
        blockIds,
        txs.map((null, _)),
        (_, _) => Future.successful { broadcastCount.increment(); TracedResult(Right(true)) }
      )(using scheduler)

      val appends = 20
      (1 to appends).foreach { i =>
        if (i % 2 == 1)
          d.appendBlock()
        else
          d.appendMicroBlock(transfer())
        while (broadcastCount.get() != i + 1)
          Thread.sleep(10)
      }

      broadcastCount.get() shouldBe appends + 1

      synchronizer.cancel()
      scheduler.shutdown()
    }
  }
}
