package tech.hearth.it.api

import com.google.protobuf.ByteString
import com.google.protobuf.empty.Empty
import tech.hearth.account.AddressScheme
import tech.hearth.api.grpc.{TransactionStatus as PBTransactionStatus, *}
import tech.hearth.common.utils.Base58
import tech.hearth.common.utils.EitherExt2.*
import tech.hearth.it.Node
import tech.hearth.it.util.*
import tech.hearth.it.util.GlobalTimer.instance as timer
import tech.hearth.protobuf.Amount
import tech.hearth.protobuf.block.PBBlocks
import tech.hearth.transaction.assets.exchange.Order
import tech.hearth.utils.Schedulers
import io.grpc.stub.StreamObserver
import monix.eval.Task
import monix.execution.ExecutionModel.SynchronousExecution
import monix.execution.Scheduler
import monix.reactive.subjects.ConcurrentSubject
import tech.hearth.crypto.SigningKey

import scala.concurrent.Future
import scala.concurrent.duration.*

object AsyncGrpcApi {
  implicit class NodeAsyncGrpcApi(val n: Node) {

    import tech.hearth.protobuf.transaction.{Transaction as PBTransaction, *}

    private given scheduler: Scheduler = Schedulers.singleThread("grpc", executionModel = SynchronousExecution)

    private lazy val assets       = AssetsApiGrpc.stub(n.grpcChannel)
    private lazy val accounts     = AccountsApiGrpc.stub(n.grpcChannel)
    private lazy val blocks       = BlocksApiGrpc.stub(n.grpcChannel)
    private lazy val transactions = TransactionsApiGrpc.stub(n.grpcChannel)

    val chainId: Byte = AddressScheme.current.chainId

    def blockAt(height: Int): Future[Block] = {
      blocks
        .getBlock(BlockRequest.of(BlockRequest.Request.Height(height), includeTransactions = true))
        .map(r => PBBlocks.vanilla(r.getBlock).get.json().as[Block])
    }

    def broadcastTransfer(
        source: SigningKey,
        recipient: Recipient,
        amount: Long,
        fee: Long,
        assetId: String = "WAVES",
        feeAssetId: String = "WAVES",
        attachment: ByteString = ByteString.EMPTY,
        timestamp: Long = System.currentTimeMillis
    ): Future[PBSignedTransaction] = {
      val unsigned = PBTransaction(
        chainId,
        ByteString.copyFrom(source.publicKey()),
        Some(Amount.of(if (feeAssetId == "WAVES") ByteString.EMPTY else ByteString.copyFrom(Base58.decode(feeAssetId)), fee)),
        timestamp,
        PBTransaction.Data.Transfer(
          TransferTransactionData.of(
            Some(recipient),
            Some(Amount.of(if (assetId == "WAVES") ByteString.EMPTY else ByteString.copyFrom(Base58.decode(assetId)), amount)),
            attachment
          )
        )
      )
      try {
        val proofs = source.sign(PBTransactions.vanilla(SignedTransaction(Some(unsigned))).explicitGet().bodyBytes())
        transactions.broadcast(SignedTransaction.of(Some(unsigned), Seq(ByteString.copyFrom(proofs))))
      } catch {
        case _: IllegalArgumentException => transactions.broadcast(SignedTransaction.of(Some(unsigned), Seq(ByteString.EMPTY)))
      }
    }

    def exchange(
        matcher: SigningKey,
        buyOrder: Order,
        sellOrder: Order,
        amount: Long,
        price: Long,
        buyMatcherFee: Long,
        sellMatcherFee: Long,
        fee: Long,
        timestamp: Long,
        matcherFeeAssetId: String = "WAVES"
    ): Future[PBSignedTransaction] = {

      val unsigned = PBTransaction(
        chainId,
        ByteString.copyFrom(matcher.publicKey()),
        Some(Amount.of(if (matcherFeeAssetId == "WAVES") ByteString.EMPTY else ByteString.copyFrom(Base58.decode(matcherFeeAssetId)), fee)),
        timestamp,
        PBTransaction.Data.Exchange(
          ExchangeTransactionData.of(
            amount,
            price,
            buyMatcherFee,
            sellMatcherFee,
            Seq(PBOrders.protobuf(buyOrder), PBOrders.protobuf(sellOrder))
          )
        )
      )

      val proofs      = matcher.sign(PBTransactions.vanilla(SignedTransaction(Some(unsigned))).explicitGet().bodyBytes())
      val transaction = SignedTransaction.of(Some(unsigned), Seq(ByteString.copyFrom(proofs)))

      transactions.broadcast(transaction)
    }

    def getTransaction(id: String, sender: ByteString = ByteString.EMPTY, recipient: Option[Recipient] = None): Future[PBSignedTransaction] =
      getTransactionInfo(ByteString.copyFrom(Base58.decode(id)), sender, recipient).map(_.getTransaction)

    def getTransactionInfo(
        id: ByteString,
        sender: ByteString = ByteString.EMPTY,
        recipient: Option[Recipient] = None
    ): Future[TransactionResponse] = {
      val (obs, result) = createCallObserver[TransactionResponse]
      val req           = TransactionsRequest(transactionIds = Seq(id), sender = sender, recipient = recipient)
      transactions.getTransactions(req, obs)
      result.map(_.headOption.getOrElse(throw new NoSuchElementException("Transaction not found"))).runToFuture
    }

    def waitFor[A](desc: String)(f: this.type => Future[A], cond: A => Boolean, retryInterval: FiniteDuration): Future[A] = {
      n.log.debug(s"Awaiting condition '$desc'")
      timer
        .retryUntil(f(this), cond, retryInterval)
        .map(a => {
          n.log.debug(s"Condition '$desc' met")
          a
        })
    }

    def waitForTransaction(txId: String, retryInterval: FiniteDuration = 1.second): Future[PBSignedTransaction] = {
      val condition = waitFor[Option[PBSignedTransaction]](s"transaction $txId")(
        _.getTransaction(txId)
          .map(Option(_))
          .recover { case _: NoSuchElementException => None },
        tOpt => tOpt.exists(t => PBTransactions.vanilla(t).explicitGet().id().toString == txId),
        retryInterval
      ).map(_.get)

      condition
    }

    def height: Future[Int] = blocks.getCurrentHeight(Empty.of())

    def waitForHeight(expectedHeight: Int): Future[Int] = {
      waitFor[Int](s"height >= $expectedHeight")(_.height, h => h >= expectedHeight, 5.seconds)
    }

    def wavesBalance(address: ByteString): Future[BalanceResponse.WavesBalances] = {
      val (obs, result) = createCallObserver[BalanceResponse]
      val req           = BalancesRequest.of(address, Seq(ByteString.EMPTY))
      accounts.getBalances(req, obs)
      result.map(_.headOption.getOrElse(throw new NoSuchElementException("Balances not found for address")).getWaves).runToFuture
    }

    def broadcast(unsignedTx: PBTransaction, proofs: Seq[ByteString]): Future[PBSignedTransaction] =
      transactions.broadcast(SignedTransaction(Some(unsignedTx), proofs))

    def broadcastMassTransfer(
        sender: SigningKey,
        assetId: Option[String] = None,
        transfers: Seq[MassTransferTransactionData.Transfer],
        attachment: ByteString = ByteString.EMPTY,
        fee: Long
    ): Future[PBSignedTransaction] = {
      val unsigned = PBTransaction(
        chainId,
        ByteString.copyFrom(sender.publicKey()),
        Some(Amount.of(ByteString.EMPTY, fee)),
        System.currentTimeMillis(),
        PBTransaction.Data.MassTransfer(
          MassTransferTransactionData.of(
            if (assetId.isDefined) ByteString.copyFrom(Base58.decode(assetId.get)) else ByteString.EMPTY,
            transfers,
            attachment
          )
        )
      )
      val proofs = sender.sign(PBTransactions.vanilla(SignedTransaction(Some(unsigned))).explicitGet().bodyBytes())
      transactions.broadcast(SignedTransaction.of(Some(unsigned), Seq(ByteString.copyFrom(proofs))))
    }

    def broadcastLease(source: SigningKey, recipient: Recipient, amount: Long, fee: Long): Future[PBSignedTransaction] = {
      val unsigned = PBTransaction(
        chainId,
        ByteString.copyFrom(source.publicKey()),
        Some(Amount.of(ByteString.EMPTY, fee)),
        System.currentTimeMillis,
        PBTransaction.Data.Lease(LeaseTransactionData.of(Some(recipient), amount))
      )
      val proofs = source.sign(PBTransactions.vanilla(SignedTransaction(Some(unsigned))).explicitGet().bodyBytes())
      transactions.broadcast(SignedTransaction.of(Some(unsigned), Seq(ByteString.copyFrom(proofs))))
    }

    def broadcastLeaseCancel(source: SigningKey, leaseId: String, fee: Long): Future[PBSignedTransaction] = {
      val unsigned = PBTransaction(
        chainId,
        ByteString.copyFrom(source.publicKey()),
        Some(Amount.of(ByteString.EMPTY, fee)),
        System.currentTimeMillis,
        PBTransaction.Data.LeaseCancel(LeaseCancelTransactionData.of(ByteString.copyFrom(Base58.decode(leaseId))))
      )
      val proofs = source.sign(PBTransactions.vanilla(SignedTransaction(Some(unsigned))).explicitGet().bodyBytes())
      transactions.broadcast(SignedTransaction.of(Some(unsigned), Seq(ByteString.copyFrom(proofs))))
    }

    def assetInfo(assetId: String): Future[AssetInfoResponse] = assets.getInfo(AssetRequest(ByteString.copyFrom(Base58.decode(assetId))))

    def getStatuses(request: TransactionsByIdRequest): Future[Seq[PBTransactionStatus]] = {
      val (obs, result) = createCallObserver[PBTransactionStatus]
      transactions.getStatuses(request, obs)
      result.runToFuture
    }
  }

  private def createCallObserver[T](implicit s: Scheduler): (StreamObserver[T], Task[List[T]]) = {
    val subj = ConcurrentSubject.replay[T]

    val observer = new StreamObserver[T] {
      override def onNext(value: T): Unit      = subj.onNext(value)
      override def onError(t: Throwable): Unit = subj.onError(t)
      override def onCompleted(): Unit         = subj.onComplete()
    }

    (observer, subj.toListL)
  }
}
