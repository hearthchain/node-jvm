package com.wavesplatform.state.diffs

import cats.implicits.catsSyntaxSemigroup
import cats.instances.either.*
import cats.syntax.either.*
import cats.syntax.functor.*
import com.wavesplatform.account.{Address, AddressScheme}
import com.wavesplatform.lang.ValidationError
import com.wavesplatform.metrics.TxProcessingStats
import com.wavesplatform.metrics.TxProcessingStats.measureForType
import com.wavesplatform.state.TxMeta.Status
import com.wavesplatform.state.{Blockchain, NewTransactionInfo, Portfolio, Sponsorship, StateSnapshot}
import com.wavesplatform.transaction.*
import com.wavesplatform.transaction.Asset.{IssuedAsset, Waves}
import com.wavesplatform.transaction.TxValidationError.*
import com.wavesplatform.transaction.assets.*
import com.wavesplatform.transaction.assets.exchange.{ExchangeTransaction, Order}
import com.wavesplatform.transaction.lease.{LeaseCancelTransaction, LeaseTransaction}
import com.wavesplatform.transaction.smart.script.trace.TracedResult
import com.wavesplatform.transaction.transfer.{MassTransferTransaction, TransferTransaction}
import play.api.libs.json.Json

object TransactionDiffer {
  def apply(prevBlockTs: Option[Long], currentBlockTs: Long, verify: Boolean = true)(
      blockchain: Blockchain,
      tx: Transaction
  ): TracedResult[ValidationError, StateSnapshot] =
    validate(prevBlockTs, currentBlockTs, verify)(blockchain, tx)

  def forceValidate(prevBlockTs: Option[Long], currentBlockTs: Long)(
      blockchain: Blockchain,
      tx: Transaction
  ): TracedResult[ValidationError, StateSnapshot] =
    validate(prevBlockTs, currentBlockTs, verify = true)(blockchain, tx)

  def limitedExecution(
      prevBlockTimestamp: Option[Long],
      currentBlockTimestamp: Long,
      verify: Boolean = true
  )(
      blockchain: Blockchain,
      tx: Transaction
  ): TracedResult[ValidationError, StateSnapshot] = {
    validate(
      prevBlockTimestamp,
      currentBlockTimestamp,
      verify = verify
    )(blockchain, tx)
  }

  /** Validates transaction.
    * @param limitedExecution
    *   skip execution of the DApp and asset scripts
    * @param verify
    *   validate common checks, proofs and asset scripts execution. If `skipFailing` is true asset scripts will not be executed
    */
  private def validate(
      prevBlockTimestamp: Option[Long],
      currentBlockTimestamp: Long,
      verify: Boolean
  )(
      blockchain: Blockchain,
      tx: Transaction
  ): TracedResult[ValidationError, StateSnapshot] = {
    val result = for {
      _                   <- validateCommon(blockchain, tx, prevBlockTimestamp, currentBlockTimestamp, verify).traced
      _                   <- validateFunds(blockchain, tx).traced
      verifierSnapshot    <- TracedResult.wrapValue(StateSnapshot.empty)
      transactionSnapshot <- transactionSnapshot(blockchain, tx, verifierSnapshot)
      _                   <- validateBalance(blockchain, tx.tpe, transactionSnapshot).traced
    } yield transactionSnapshot

    result.leftMap(TransactionValidationError(_, tx))
  }

  // validation related
  private def validateCommon(
      blockchain: Blockchain,
      tx: Transaction,
      prevBlockTs: Option[Long],
      currentBlockTs: Long,
      verify: Boolean
  ): Either[ValidationError, Unit] =
    if (verify)
      stats.commonValidation
        .measureForType(tx.tpe) {
          for {
            _ <- CommonValidation.disallowFromAnotherNetwork(tx, AddressScheme.current.chainId)
            _ <- CommonValidation.disallowTxFromFuture(blockchain.settings.functionalitySettings, currentBlockTs, tx)
            _ <- CommonValidation.disallowTxFromPast(blockchain.settings.functionalitySettings, prevBlockTs, tx)
            _ <- CommonValidation.disallowDuplicateIds(blockchain, tx)
            _ <- CommonValidation.disallowSendingGreaterThanBalance(blockchain, tx)
            _ <- FeeValidation(tx)
          } yield ()
        }
    else Right(())

  private def validateFunds(blockchain: Blockchain, tx: Transaction): Either[ValidationError, Unit] =
    if (skipFundsSufficiency(tx)) Right(())
    else
      for {
        _ <- validateFee(blockchain, tx)
        _ <- tx match {
          case etx: ExchangeTransaction =>
            for {
              _ <- validateOrder(blockchain, etx.buyOrder, etx.buyMatcherFee.value)
              _ <- validateOrder(blockchain, etx.sellOrder, etx.sellMatcherFee.value)

              // Balance overflow check
              _ <- for {
                portfolios <- ExchangeTransactionDiff.getPortfolios(blockchain, etx)
                snapshot   <- StateSnapshot.build(blockchain, portfolios)
                _          <- validateBalance(blockchain, etx.tpe, snapshot)
              } yield portfolios
            } yield ()
          case _ => Right(())
        }
      } yield ()

  def validateBalance(blockchain: Blockchain, txType: TransactionType, s: StateSnapshot): Either[ValidationError, Unit] =
    stats.balanceValidation.measureForType(txType)(BalanceDiffValidation(blockchain)(s).as(()))

  private def transactionSnapshot(
      blockchain: Blockchain,
      tx: Transaction,
      initSnapshot: StateSnapshot
  ): TracedResult[ValidationError, StateSnapshot] =
    stats.transactionDiffValidation
      .measureForType(tx.tpe) {
        tx match {
          case etx: ExchangeTransaction            => ExchangeTransactionDiff(blockchain)(etx).traced
          case ttx: TransferTransaction            => TransferTransactionDiff(blockchain)(ttx).traced
          case mtx: MassTransferTransaction        => MassTransferTransactionDiff(blockchain)(mtx).traced
          case ltx: LeaseTransaction               => LeaseTransactionsDiff.lease(blockchain)(ltx).traced
          case ltx: LeaseCancelTransaction         => LeaseTransactionsDiff.leaseCancel(blockchain)(ltx).traced
          case cgtx: CommitToGenerationTransaction => CommitToGenerationTransactionDiff(blockchain)(cgtx).traced
          case _                                   => UnsupportedTransactionType.asLeft.traced
        }
      }
      .map(txSnapshot => initSnapshot |+| txSnapshot.withTransaction(NewTransactionInfo.create(tx, Status.Succeeded, txSnapshot, blockchain)))

  // insufficient funds related
  private def skipFundsSufficiency(tx: Transaction): Boolean =
    tx match {
      case _: LeaseCancelTransaction => true
      case _                         => false
    }

  private def validateFee(blockchain: Blockchain, tx: Transaction): Either[ValidationError, Unit] =
    for {
      fee      <- feePortfolios(blockchain, tx)
      snapshot <- StateSnapshot.build(blockchain, fee)
      _        <- validateBalance(blockchain, tx.tpe, snapshot)
    } yield ()

  private def validateOrder(blockchain: Blockchain, order: Order, matcherFee: Long): Either[ValidationError, Unit] =
    for {
      _ <- order.matcherFeeAssetId match {
        case Waves => Right(())
        case asset @ IssuedAsset(_) =>
          blockchain
            .assetDescription(asset)
            .toRight(GenericError(s"Asset $asset should be issued before it can be traded"))
      }
      portfolios = Map(order.sender.toAddress -> Portfolio.build(order.matcherFeeAssetId, -matcherFee))
      snapshot <- StateSnapshot.build(blockchain, portfolios)
      _        <- validateBalance(blockchain, TransactionType.Exchange, snapshot)
    } yield ()

  // helpers
  private def feePortfolios(blockchain: Blockchain, tx: Transaction): Either[ValidationError, Map[Address, Portfolio]] =
    tx match {
      case ptx: ProvenTransaction =>
        ptx.assetFee match {
          case (Waves, fee) => Map[Address, Portfolio](ptx.sender.toAddress -> Portfolio(-fee)).asRight
          case (asset @ IssuedAsset(_), fee) =>
            for {
              assetInfo <- blockchain
                .assetDescription(asset)
                .toRight(GenericError(s"Asset $asset does not exist, cannot be used to pay fees"))
              wavesFee <- Either.cond(
                false,
                Sponsorship.toWaves(fee, 0),
                GenericError(s"Asset $asset is not sponsored, cannot be used to pay fees")
              )
              portfolios <- Portfolio
                .combine(
                  Map(ptx.sender.toAddress       -> Portfolio.build(asset, -fee)),
                  Map(assetInfo.issuer.toAddress -> Portfolio.build(-wavesFee, asset, fee))
                )
                .leftMap(GenericError(_))
            } yield portfolios
        }
      case _ => UnsupportedTransactionType.asLeft
    }

  private implicit final class EitherOps[E, A](val ei: Either[E, A]) extends AnyVal {
    // Not really traced, just wraps value with an empty trace value
    def traced: TracedResult[E, A] = TracedResult.wrapE(ei)
  }

  case class TransactionValidationError(cause: ValidationError, tx: Transaction) extends ValidationError {
    override def toString: String = s"TransactionValidationError(cause = $cause,\ntx = ${Json.prettyPrint(tx.json())})"
  }

  private val stats = TxProcessingStats
}
