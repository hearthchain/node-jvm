package tech.hearth.api.common

import tech.hearth.account.Address
import tech.hearth.api.{BlockMeta, common}
import tech.hearth.block
import tech.hearth.block.Block.TransactionProof
import tech.hearth.common.state.ByteStr
import tech.hearth.database.RDB
import tech.hearth.lang.ValidationError
import tech.hearth.mining.BlockChallenger
import tech.hearth.state.diffs.FeeValidation
import tech.hearth.state.diffs.FeeValidation.FeeDetails
import tech.hearth.state.{Blockchain, Height, StateSnapshot, TxMeta}
import tech.hearth.transaction.smart.script.trace.TracedResult
import tech.hearth.transaction.{Asset, Transaction, TransactionType}
import tech.hearth.utx.UtxPool
import monix.reactive.Observable

import scala.concurrent.Future

trait CommonTransactionsApi {

  def transactionById(txId: ByteStr): Option[TransactionMeta]

  def unconfirmedTransactions: Seq[Transaction]

  def unconfirmedTransactionById(txId: ByteStr): Option[Transaction]

  def calculateFee(tx: Transaction): Either[ValidationError, (Asset, Long, Long)]

  def broadcastTransaction(tx: Transaction): Future[TracedResult[ValidationError, Boolean]]

  def transactionsByAddress(
      subject: Address,
      sender: Option[Address],
      transactionTypes: Set[TransactionType],
      fromId: Option[ByteStr] = None
  ): Observable[TransactionMeta]

  def transactionProofs(transactionIds: List[ByteStr]): List[TransactionProof]
}

object CommonTransactionsApi {
  def apply(
      maybeDiff: => Option[(Height, StateSnapshot)],
      rdb: RDB,
      blockchain: Blockchain,
      utx: UtxPool,
      blockChallenger: Option[BlockChallenger],
      publishTransaction: Transaction => Future[TracedResult[ValidationError, Boolean]],
      blockAt: Height => Option[(BlockMeta, Seq[(TxMeta, Transaction)])]
  ): CommonTransactionsApi = new CommonTransactionsApi {

    override def transactionsByAddress(
        subject: Address,
        sender: Option[Address],
        transactionTypes: Set[TransactionType],
        fromId: Option[ByteStr] = None
    ): Observable[TransactionMeta] =
      common.addressTransactions(rdb, maybeDiff, subject, sender, transactionTypes, fromId)

    override def transactionById(transactionId: ByteStr): Option[TransactionMeta] =
      blockchain.transactionInfo(transactionId).map(common.loadTransactionMeta)

    override def unconfirmedTransactions: Seq[Transaction] =
      utx.all ++ blockChallenger.fold(Seq.empty[Transaction])(_.allProcessingTxs)

    override def unconfirmedTransactionById(transactionId: ByteStr): Option[Transaction] =
      utx.transactionById(transactionId).orElse(blockChallenger.flatMap(_.getProcessingTx(transactionId)))

    override def calculateFee(tx: Transaction): Either[ValidationError, (Asset, Long, Long)] =
      FeeValidation
        .getMinFee(tx)
        .map { case FeeDetails(asset, _, feeInAsset, feeInHearth) =>
          (asset, feeInAsset, feeInHearth)
        }

    override def broadcastTransaction(tx: Transaction): Future[TracedResult[ValidationError, Boolean]] = publishTransaction(tx)

    override def transactionProofs(transactionIds: List[ByteStr]): List[TransactionProof] =
      for {
        transactionId        <- transactionIds
        (txm, tx)            <- blockchain.transactionInfo(transactionId)
        (_, allTransactions) <- blockAt(txm.height)
        transactionProof     <- block.transactionProof(tx, allTransactions.map(_._2))
      } yield transactionProof
  }
}
