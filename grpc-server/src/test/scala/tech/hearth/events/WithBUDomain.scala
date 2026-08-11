package tech.hearth.events

import com.google.common.util.concurrent.MoreExecutors
import tech.hearth.db.WithDomain
import FakeObserver.*
import tech.hearth.db.WithState.AddrWithBalance
import tech.hearth.events.api.grpc.protobuf.{GetBlockUpdateResponse, GetBlockUpdatesRangeRequest, SubscribeRequest}
import tech.hearth.events.protobuf.BlockchainUpdated as PBBlockchainUpdated
import tech.hearth.events.repo.LiquidState
import tech.hearth.history.Domain
import tech.hearth.settings.{Constants, GenesisAssetSettings, HearthSettings}
import tech.hearth.state.Height
import tech.hearth.transaction.TxHelpers
import tech.hearth.utils.Schedulers
import monix.execution.ExecutionModel.SynchronousExecution
import monix.execution.Scheduler
import org.rocksdb.RocksDB
import monix.reactive.subjects.PublishToOneSubject
import org.scalatest.Suite
import org.scalatest.concurrent.ScalaFutures.convertScalaFuture

trait WithBUDomain extends WithDomain { suite: Suite =>
  private given scheduler: Scheduler = Schedulers.singleThread("bu-domain", executionModel = SynchronousExecution)
  def withDomainAndRepo(
      settings: HearthSettings,
      balances: Seq[AddrWithBalance] = Seq(AddrWithBalance(TxHelpers.defaultSigner.toAddress, Constants.TotalHearth * Constants.UnitsInHearth)),
      assets: Seq[GenesisAssetSettings] = Seq.empty,
      generators: Seq[tech.hearth.crypto.SigningKey] = Nil
  )(f: (Domain, Repo) => Unit, wrapDB: RocksDB => RocksDB = identity): Unit = {
    withDomain(settings, balances = balances, assets = assets, generators = generators) { d =>
      tempDb { rdb =>
        val repo = new Repo(wrapDB(rdb.db), d.blocksApi)
        d.triggers = Seq(repo)
        try f(d, repo)
        finally repo.shutdownHandlers()
      }
    }
  }

  def withManualHandle(settings: HearthSettings, setSendUpdate: (() => Unit) => Unit)(f: (Domain, Repo) => Unit): Unit =
    withDomain(settings, balances = Seq(AddrWithBalance(TxHelpers.defaultSigner.toAddress, Constants.TotalHearth * Constants.UnitsInHearth))) { d =>
      tempDb { rdb =>
        val repo = new Repo(rdb.db, d.blocksApi) {
          override def newHandler(
              id: String,
              maybeLiquidState: Option[LiquidState],
              subject: PublishToOneSubject[BlockchainUpdated],
              maxQueueSize: Int
          ): Handler =
            new Handler(id, maybeLiquidState, subject, maxQueueSize)(using Scheduler(MoreExecutors.newDirectExecutorService())) {
              setSendUpdate(() => super.sendUpdate())
              override def sendUpdate(): Unit = ()
            }
        }
        d.triggers = Seq(repo)
        try f(d, repo)
        finally repo.shutdownHandlers()
      }
    }

  def withGenerateSubscription(
      request: SubscribeRequest = SubscribeRequest.of(1, Int.MaxValue),
      settings: HearthSettings,
      balances: Seq[AddrWithBalance] = Seq(AddrWithBalance(TxHelpers.defaultSigner.toAddress, Constants.TotalHearth * Constants.UnitsInHearth)),
      assets: Seq[GenesisAssetSettings] = Seq.empty
  )(generateBlocks: Domain => Unit)(f: Seq[PBBlockchainUpdated] => Unit): Unit = {
    withDomainAndRepo(settings, balances, assets) { (d, repo) =>
      val subscription = repo.createFakeObserver(request)
      generateBlocks(d)

      val result = subscription.fetchAllEvents(d.blockchain, if (request.toHeight > 0) request.toHeight else Int.MaxValue)
      f(result.map(_.getUpdate))
    }
  }

  def withGenerateGetBlockUpdate(
      height: Int = 1,
      settings: HearthSettings,
      balances: Seq[AddrWithBalance] = Seq(AddrWithBalance(TxHelpers.defaultSigner.toAddress, Constants.TotalHearth * Constants.UnitsInHearth)),
      assets: Seq[GenesisAssetSettings] = Seq.empty
  )(generateBlocks: Domain => Unit)(f: GetBlockUpdateResponse => Unit): Unit = {
    withDomainAndRepo(settings, balances, assets) { (d, repo) =>
      generateBlocks(d)
      val getBlockUpdate = repo.getBlockUpdate(Height(height))
      f(getBlockUpdate)
    }
  }

  def withGenerateGetBlockUpdateRange(
      request: GetBlockUpdatesRangeRequest,
      settings: HearthSettings,
      balances: Seq[AddrWithBalance] = Seq(AddrWithBalance(TxHelpers.defaultSigner.toAddress, Constants.TotalHearth * Constants.UnitsInHearth)),
      assets: Seq[GenesisAssetSettings] = Seq.empty
  )(generateBlocks: Domain => Unit)(f: Seq[PBBlockchainUpdated] => Unit): Unit = {
    withDomainAndRepo(settings, balances, assets) { (d, repo) =>
      generateBlocks(d)
      val getBlockUpdateRange = repo.getBlockUpdatesRange(request).futureValue.updates
      f(getBlockUpdateRange)
    }
  }

  def withNEmptyBlocksSubscription(count: Int = 2, request: SubscribeRequest = SubscribeRequest.of(1, Int.MaxValue), settings: HearthSettings)(
      f: Seq[PBBlockchainUpdated] => Unit
  ): Unit = withGenerateSubscription(request, settings)(d => for (_ <- 1 to count) d.appendBlock())(f)
}
