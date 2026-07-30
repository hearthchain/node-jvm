package com.wavesplatform.api

import com.typesafe.scalalogging.Logger
import com.wavesplatform.api.http.ApiError
import com.wavesplatform.block as vb
import com.wavesplatform.lang.ValidationError
import com.wavesplatform.protobuf.block.{PBBlock, PBBlocks}
import com.wavesplatform.protobuf.transaction.{PBSignedTransaction, PBTransactions, VanillaTransaction}
import io.grpc.stub.{ServerCallStreamObserver, StreamObserver}
import monix.execution.atomic.AtomicAny
import monix.execution.{Ack, Scheduler}
import monix.reactive.Observable
import org.slf4j.LoggerFactory

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.control.NonFatal

package object grpc {
  // The generated gRPC service stubs and message DTOs live in tech.hearth.api.grpc; re-exported so the impl
  // classes in this package can keep naming them directly, as they did when the generated code shared this package
  type AccountsApiGrpc = tech.hearth.api.grpc.AccountsApiGrpc.type
  val AccountsApiGrpc = tech.hearth.api.grpc.AccountsApiGrpc
  type AccountRequest = tech.hearth.api.grpc.AccountRequest
  val AccountRequest = tech.hearth.api.grpc.AccountRequest
  type BalancesRequest = tech.hearth.api.grpc.BalancesRequest
  val BalancesRequest = tech.hearth.api.grpc.BalancesRequest
  type BalanceResponse = tech.hearth.api.grpc.BalanceResponse
  val BalanceResponse = tech.hearth.api.grpc.BalanceResponse
  type LeaseResponse = tech.hearth.api.grpc.LeaseResponse
  val LeaseResponse = tech.hearth.api.grpc.LeaseResponse

  type AssetsApiGrpc = tech.hearth.api.grpc.AssetsApiGrpc.type
  val AssetsApiGrpc = tech.hearth.api.grpc.AssetsApiGrpc
  type AssetRequest = tech.hearth.api.grpc.AssetRequest
  val AssetRequest = tech.hearth.api.grpc.AssetRequest
  type AssetInfoResponse = tech.hearth.api.grpc.AssetInfoResponse
  val AssetInfoResponse = tech.hearth.api.grpc.AssetInfoResponse
  type NFTRequest = tech.hearth.api.grpc.NFTRequest
  val NFTRequest = tech.hearth.api.grpc.NFTRequest
  type NFTResponse = tech.hearth.api.grpc.NFTResponse
  val NFTResponse = tech.hearth.api.grpc.NFTResponse

  type BlockchainApiGrpc = tech.hearth.api.grpc.BlockchainApiGrpc.type
  val BlockchainApiGrpc = tech.hearth.api.grpc.BlockchainApiGrpc
  type ActivationStatusRequest = tech.hearth.api.grpc.ActivationStatusRequest
  val ActivationStatusRequest = tech.hearth.api.grpc.ActivationStatusRequest
  type ActivationStatusResponse = tech.hearth.api.grpc.ActivationStatusResponse
  val ActivationStatusResponse = tech.hearth.api.grpc.ActivationStatusResponse
  type FeatureActivationStatus = tech.hearth.api.grpc.FeatureActivationStatus
  val FeatureActivationStatus = tech.hearth.api.grpc.FeatureActivationStatus
  type BaseTargetResponse = tech.hearth.api.grpc.BaseTargetResponse
  val BaseTargetResponse = tech.hearth.api.grpc.BaseTargetResponse
  type ScoreResponse = tech.hearth.api.grpc.ScoreResponse
  val ScoreResponse = tech.hearth.api.grpc.ScoreResponse

  type TransactionsApiGrpc = tech.hearth.api.grpc.TransactionsApiGrpc.type
  val TransactionsApiGrpc = tech.hearth.api.grpc.TransactionsApiGrpc
  type TransactionsRequest = tech.hearth.api.grpc.TransactionsRequest
  val TransactionsRequest = tech.hearth.api.grpc.TransactionsRequest
  type TransactionResponse = tech.hearth.api.grpc.TransactionResponse
  val TransactionResponse = tech.hearth.api.grpc.TransactionResponse
  type TransactionSnapshotsRequest = tech.hearth.api.grpc.TransactionSnapshotsRequest
  val TransactionSnapshotsRequest = tech.hearth.api.grpc.TransactionSnapshotsRequest
  type TransactionSnapshotResponse = tech.hearth.api.grpc.TransactionSnapshotResponse
  val TransactionSnapshotResponse = tech.hearth.api.grpc.TransactionSnapshotResponse
  type TransactionsByIdRequest = tech.hearth.api.grpc.TransactionsByIdRequest
  val TransactionsByIdRequest = tech.hearth.api.grpc.TransactionsByIdRequest
  type TransactionStatus = tech.hearth.api.grpc.TransactionStatus
  val TransactionStatus = tech.hearth.api.grpc.TransactionStatus
  type ApplicationStatus = tech.hearth.api.grpc.ApplicationStatus
  val ApplicationStatus = tech.hearth.api.grpc.ApplicationStatus
  type SignRequest = tech.hearth.api.grpc.SignRequest
  val SignRequest = tech.hearth.api.grpc.SignRequest
  type InvokeScriptResultResponse = tech.hearth.api.grpc.InvokeScriptResultResponse
  val InvokeScriptResultResponse = tech.hearth.api.grpc.InvokeScriptResultResponse

  type BlocksApiGrpc = tech.hearth.api.grpc.BlocksApiGrpc.type
  val BlocksApiGrpc = tech.hearth.api.grpc.BlocksApiGrpc
  type BlockRequest = tech.hearth.api.grpc.BlockRequest
  val BlockRequest = tech.hearth.api.grpc.BlockRequest
  type BlockRangeRequest = tech.hearth.api.grpc.BlockRangeRequest
  val BlockRangeRequest = tech.hearth.api.grpc.BlockRangeRequest
  type BlockWithHeight = tech.hearth.api.grpc.BlockWithHeight
  val BlockWithHeight = tech.hearth.api.grpc.BlockWithHeight

  implicit class VanillaTransactionConversions(val tx: VanillaTransaction) extends AnyVal {
    def toPB: PBSignedTransaction = PBTransactions.protobuf(tx)
  }

  implicit class PBSignedTransactionConversions(val tx: PBSignedTransaction) extends AnyVal {
    def toVanilla: Either[ValidationError, VanillaTransaction] = PBTransactions.vanilla(tx)
  }

  implicit class VanillaHeaderConversionOps(val header: vb.BlockHeader) extends AnyVal {
    def toPBHeader: PBBlock.Header = PBBlocks.protobuf(header)
  }

  protected lazy val logger: Logger =
    Logger(LoggerFactory.getLogger(this.getClass.getName))

  implicit class StreamObserverMonixOps[T](val streamObserver: StreamObserver[T]) extends AnyVal {
    def id: String =
      Integer.toHexString(System.identityHashCode(streamObserver))

    def completeWith(obs: Observable[T])(implicit sc: Scheduler): Unit =
      wrapObservable(obs, streamObserver)

    def failWith(error: Throwable): Unit = {
      error match {
        case _: IllegalArgumentException => logger.warn(s"[${streamObserver.id}] gRPC call completed with error", error)
        case _                           => logger.error(s"[${streamObserver.id}] gRPC call completed with error", error)
      }

      streamObserver.onError(GRPCErrors.toStatusException(error))
    }

    def interceptErrors(f: => Unit): Unit =
      try f
      catch { case NonFatal(e) => streamObserver.failWith(e) }
  }

  implicit class EitherVEExt[T](val e: Either[ValidationError, T]) extends AnyVal {
    def explicitGetErr(): T = e.fold(e => throw GRPCErrors.toStatusException(e), identity)
    def toFuture: Future[T] = Future.fromTry(e.left.map(err => GRPCErrors.toStatusException(err)).toTry)
  }

  implicit class OptionErrExt[T](val e: Option[T]) extends AnyVal {
    def explicitGetErr(err: ApiError): T = e.getOrElse(throw GRPCErrors.toStatusException(err))
  }

  implicit class FutureExt[T](val f: Future[T]) extends AnyVal {
    def wrapErrors(implicit ec: ExecutionContext): Future[T] = f.recoverWith { case err =>
      Future.failed(GRPCErrors.toStatusException(err))
    }
  }

  private def wrapObservable[A](source: Observable[A], dest: StreamObserver[A])(implicit s: Scheduler): Unit = dest match {
    case cso: ServerCallStreamObserver[A] @unchecked =>
      val nextItem = AtomicAny(Option.empty[(Promise[Ack], A)])

      def sendNextItem(): Unit =
        for ((p, elem) <- nextItem.getAndSet(None)) try {
          cso.onNext(elem)
          p.trySuccess(Ack.Continue)
        } catch {
          case NonFatal(t) =>
            cso.onError(t)
            p.tryFailure(t)
        }

      cso.setOnReadyHandler(() => sendNextItem())

      val cancelable = source.subscribe(
        (elem: A) =>
          if (cso.isCancelled) {
            Ack.Stop
          } else {
            val p = Promise[Ack]()
            if (nextItem.compareAndSet(None, Some(p -> elem))) {
              if (cso.isReady)
                sendNextItem()

              p.future
            } else Future.failed(new IllegalStateException(s"An element ${nextItem()} is pending"))
          },
        err => cso.onError(err),
        { () =>
          logger.debug("Source observer completed")
          cso.onCompleted()
        }
      )
      cso.setOnCancelHandler { () =>
        logger.warn("Stream cancelled")
        cancelable.cancel()
      }

    case _ =>
      logger.warn(s"Unsupported StreamObserver type: $dest")
      source.subscribe(
        { (elem: A) =>
          dest.onNext(elem)
          Ack.Continue
        },
        dest.failWith,
        () => dest.onCompleted()
      )
  }
}
