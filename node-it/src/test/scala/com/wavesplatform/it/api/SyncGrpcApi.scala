package com.wavesplatform.it.api

import com.google.protobuf.ByteString
import com.wavesplatform.account.Address
import com.wavesplatform.api.grpc.BalanceResponse.WavesBalances
import com.wavesplatform.api.grpc.{TransactionStatus as PBTransactionStatus, *}
import com.wavesplatform.common.utils.Base58
import com.wavesplatform.common.utils.EitherExt2.*
import com.wavesplatform.it.Node
import com.wavesplatform.it.api.SyncHttpApi.RequestAwaitTime
import com.wavesplatform.it.sync.*
import com.wavesplatform.protobuf.block.Block.Header
import com.wavesplatform.protobuf.block.{PBBlocks, VanillaBlock}
import com.wavesplatform.protobuf.transaction.*
import com.wavesplatform.transaction.assets.exchange.Order
import io.grpc.Status.Code
import io.grpc.StatusRuntimeException
import org.scalatest.{Assertion, Assertions}
import tech.hearth.crypto.SigningKey

import java.util.concurrent.TimeoutException
import scala.annotation.tailrec
import scala.concurrent.duration.*
import scala.concurrent.{Await, Awaitable, Future}
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

object SyncGrpcApi extends Assertions {

  def assertGrpcError[R](f: => R, errorRegex: String = "", expectedCode: Code = Code.INVALID_ARGUMENT): Assertion = Try(f) match {
    case Failure(GrpcStatusRuntimeException(status, _)) =>
      Assertions.assert(
        status.getCode == expectedCode
          && status.getDescription.matches(s".*$errorRegex.*"),
        s"\nexpected '$errorRegex'\nactual '${status.getDescription}'"
      )
    case Failure(e) => Assertions.fail(e)
    case Success(s) => Assertions.fail(s"Expecting bad request but handle $s")
  }

  implicit def stringAsBytes(str: String): ByteString = {
    ByteString.copyFrom(Base58.decode(str))
  }

  implicit def keyPairAsBytes(kp: SigningKey): ByteString = {
    ByteString.copyFrom(kp.toAddress.toBytes())
  }

  implicit class PBTransactionOps(tx: PBSignedTransaction) {
    def id: String = PBTransactions.vanilla(tx).explicitGet().id().toString
  }

  implicit class NodeExtGrpc(n: Node) {
    def grpc: NodeExtGrpc = this
    import com.wavesplatform.it.api.AsyncGrpcApi.NodeAsyncGrpcApi as async

    private lazy val accounts     = AccountsApiGrpc.blockingStub(n.grpcChannel)
    private lazy val assets       = AssetsApiGrpc.blockingStub(n.grpcChannel)
    private lazy val transactions = TransactionsApiGrpc.blockingStub(n.grpcChannel)
    private lazy val blocks       = BlocksApiGrpc.blockingStub(n.grpcChannel)

    def sync[A](awaitable: Awaitable[A], atMost: Duration = RequestAwaitTime): A =
      try Await.result(awaitable, atMost)
      catch {
        case gsre: StatusRuntimeException => throw GrpcStatusRuntimeException(gsre.getStatus, gsre.getTrailers)
        case te: TimeoutException         => throw te
        case NonFatal(cause)              => throw new Exception(cause)
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
        version: Byte,
        matcherFeeAssetId: String = "WAVES",
        waitForTx: Boolean = false
    ): PBSignedTransaction = {
      maybeWaitForTransaction(
        sync(
          async(n).exchange(matcher, buyOrder, sellOrder, amount, price, buyMatcherFee, sellMatcherFee, fee, timestamp, version, matcherFeeAssetId)
        ),
        waitForTx
      )
    }

    def broadcastTransfer(
        source: SigningKey,
        recipient: Recipient,
        amount: Long,
        fee: Long,
        version: Int = 2,
        assetId: String = "WAVES",
        feeAssetId: String = "WAVES",
        attachment: ByteString = ByteString.EMPTY,
        timestamp: Long = System.currentTimeMillis(),
        waitForTx: Boolean = false
    ): PBSignedTransaction = {
      maybeWaitForTransaction(
        sync(async(n).broadcastTransfer(source, recipient, amount, fee, version, assetId, feeAssetId, attachment, timestamp)),
        waitForTx
      )
    }

    def assetsBalance(address: ByteString, assetIds: Seq[String] = Nil): Map[String, Long] = {
      val pbAssetIds = assetIds.map(a => ByteString.copyFrom(Base58.decode(a)))
      val balances   = accounts.getBalances(BalancesRequest.of(address, pbAssetIds))
      balances.map(b => Base58.encode(b.getAsset.assetId.toByteArray) -> b.getAsset.amount).toMap
    }

    def nftList(address: ByteString, limit: Int, after: ByteString = ByteString.EMPTY): Seq[NFTResponse] = {
      assets.getNFTList(NFTRequest.of(address, limit, after)).toList
    }

    def assertAssetBalance(acc: ByteString, assetIdString: String, balance: Long): Unit = {
      val actual = assetsBalance(acc, Seq(assetIdString)).getOrElse(assetIdString, 0L)
      assert(actual == balance, s"Asset balance mismatch, required=$balance, actual=$actual")
    }

    def wavesBalance(address: ByteString): WavesBalances = {
      accounts.getBalances(BalancesRequest.of(address, Seq(ByteString.EMPTY))).next().getWaves
    }

    def getTransaction(id: String, sender: ByteString = ByteString.EMPTY, recipient: Option[Recipient] = None): PBSignedTransaction = {
      sync(async(n).getTransaction(id, sender, recipient))
    }

    def getTransactionInfo(id: ByteString, sender: ByteString = ByteString.EMPTY, recipient: Option[Recipient] = None): TransactionResponse = {
      sync(async(n).getTransactionInfo(id, sender, recipient))
    }

    def getTransactionSeq(ids: Seq[String], sender: ByteString = ByteString.EMPTY, recipient: Option[Recipient] = None): List[TransactionResponse] = {
      transactions.getTransactions(TransactionsRequest(sender, recipient, ids.map(id => ByteString.copyFrom(Base58.decode(id))))).toList
    }

    def waitForTransaction(txId: String): PBSignedTransaction =
      sync(async(n).waitForTransaction(txId))

    def waitForTxAndHeightArise(txId: String): Unit = {
      @tailrec
      def recWait(): Unit = {
        val status        = getStatuses(TransactionsByIdRequest.of(Seq(ByteString.copyFrom(Base58.decode(txId))))).head
        val currentHeight = this.height

        if (status.status.isConfirmed && currentHeight > status.height)
          ()
        else if (status.status.isUnconfirmed || status.status.isNotExists) {
          waitForTransaction(txId)
          recWait()
        } else {
          waitForHeight(status.height.toInt + 1)
          recWait()
        }
      }

      recWait()
    }

    private def maybeWaitForTransaction(tx: PBSignedTransaction, wait: Boolean): PBSignedTransaction = {
      if (wait) waitForTxAndHeightArise(tx.id)
      tx
    }

    def height: Int = sync(async(n).height)

    def waitForHeight(expectedHeight: Int, requestAwaitTime: FiniteDuration = RequestAwaitTime): Int =
      sync(async(n).waitForHeight(expectedHeight), requestAwaitTime)

    def waitForHeightArise(requestAwaitTime: FiniteDuration = RequestAwaitTime): Int =
      sync(async(n).waitForHeight(this.height + 1), requestAwaitTime)

    def waitFor[A](desc: String)(f: Node => A, cond: A => Boolean, retryInterval: FiniteDuration): A =
      sync(async(n).waitFor(desc)(x => Future.successful(f(x.n)), cond, retryInterval))

    def broadcastMassTransfer(
        sender: SigningKey,
        assetId: Option[String] = None,
        transfers: Seq[MassTransferTransactionData.Transfer],
        attachment: ByteString = ByteString.EMPTY,
        fee: Long,
        version: Int = 1,
        waitForTx: Boolean = false
    ): PBSignedTransaction = {
      maybeWaitForTransaction(sync(async(n).broadcastMassTransfer(sender, assetId, transfers, attachment, fee, version)), waitForTx)
    }

    def signedBroadcast(tx: PBSignedTransaction, waitForTx: Boolean = false): PBSignedTransaction = {
      maybeWaitForTransaction(sync(async(n).broadcast(tx.getWavesTransaction, tx.proofs)), waitForTx)
    }

    def broadcast(tx: PBTransaction, proofs: Seq[ByteString], waitForTx: Boolean = false): PBSignedTransaction = {
      maybeWaitForTransaction(sync(async(n).broadcast(tx, proofs)), waitForTx)
    }

    def broadcastLease(
        source: SigningKey,
        recipient: Recipient,
        amount: Long,
        fee: Long,
        version: Int = 2,
        waitForTx: Boolean = false
    ): PBSignedTransaction = {
      maybeWaitForTransaction(sync(async(n).broadcastLease(source, recipient, amount, fee, version)), waitForTx)
    }

    def broadcastLeaseCancel(source: SigningKey, leaseId: String, fee: Long, version: Int = 2, waitForTx: Boolean = false): PBSignedTransaction = {
      maybeWaitForTransaction(sync(async(n).broadcastLeaseCancel(source, leaseId, fee, version)), waitForTx)
    }

    def getActiveLeases(address: ByteString): List[LeaseResponse] = {
      accounts.getActiveLeases(AccountRequest.of(address)).toList
    }

    def assetInfo(assetId: String): AssetInfoResponse = sync(async(n).assetInfo(assetId))

    def blockAt(height: Int): VanillaBlock = {
      val block = blocks.getBlock(BlockRequest.of(BlockRequest.Request.Height.apply(height), includeTransactions = true)).getBlock
      PBBlocks.vanilla(block).toEither.explicitGet()
    }

    def blockHeaderAt(height: Int): Header = {
      blocks.getBlock(BlockRequest.of(BlockRequest.Request.Height.apply(height), includeTransactions = true)).getBlock.getHeader
    }

    def blockById(blockId: ByteString): VanillaBlock = {
      val block = blocks.getBlock(BlockRequest.of(BlockRequest.Request.BlockId.apply(blockId), includeTransactions = true)).getBlock
      PBBlocks.vanilla(block).toEither.explicitGet()
    }

    def blockSeq(fromHeight: Int, toHeight: Int, filter: BlockRangeRequest.Filter = BlockRangeRequest.Filter.Empty): Seq[VanillaBlock] = {
      val blockIter = blocks.getBlockRange(BlockRangeRequest.of(fromHeight, toHeight, filter, includeTransactions = true))
      blockIter.map(blockWithHeight => PBBlocks.vanilla(blockWithHeight.getBlock).toEither.explicitGet()).toSeq
    }

    def blockSeqByAddress(address: String, fromHeight: Int, toHeight: Int): Seq[VanillaBlock] = {
      // Addresses are bech32m now (see CLAUDE.md's node-it fixtures notes), not base58 - Base58.decode(address)
      // threw on the first non-base58 character.
      val filter = BlockRangeRequest.Filter.GeneratorAddress(ByteString.copyFrom(Address.fromString(address).explicitGet().toBytes()))
      blockSeq(fromHeight, toHeight, filter)
    }

    def getStatuses(request: TransactionsByIdRequest): Seq[PBTransactionStatus] = sync(async(n).getStatuses(request))

    def getStatus(txId: String): PBTransactionStatus = {
      val request = TransactionsByIdRequest.of(Seq(ByteString.copyFrom(Base58.decode(txId))))
      sync(async(n).getStatuses(request)).head
    }
  }
}
