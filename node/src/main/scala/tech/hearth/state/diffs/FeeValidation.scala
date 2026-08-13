package tech.hearth.state.diffs

import cats.data.Chain
import tech.hearth.lang.ValidationError
import tech.hearth.state.*
import tech.hearth.transaction.*
import tech.hearth.transaction.Asset.Hearth
import tech.hearth.transaction.TxValidationError.*
import tech.hearth.transaction.transfer.*

object FeeValidation {

  case class FeeDetails(asset: Asset, requirements: Chain[String], minFeeInAsset: Long, minFeeInHearth: Long)

  val ScriptExtraFee    = 400000L
  val FeeUnit           = 100000
  val NFTMultiplier     = 0.001
  val BlockV5Multiplier = 0.001

  val FeeConstants: Map[TransactionType, Long] = Map(
    TransactionType.Genesis            -> 0,
    TransactionType.Transfer           -> 1,
    TransactionType.Lease              -> 1,
    TransactionType.LeaseCancel        -> 1,
    TransactionType.Exchange           -> 3,
    TransactionType.CommitToGeneration -> 100, // TODO: decide
    TransactionType.StartBoost         -> 1,   // TODO: decide
    TransactionType.Reserve            -> 1,   // TODO: decide
    TransactionType.BindApiKey         -> 1,   // TODO: decide
    TransactionType.Settle             -> 1,   // TODO: decide
    TransactionType.Withdraw           -> 1,   // TODO: decide
    TransactionType.UpdateCollateral   -> 1    // TODO: decide
  )

  def apply(tx: Transaction): Either[ValidationError, Unit] =
    Either.cond(tx.fee > 0 || !tx.isInstanceOf[Authorized], (), GenericError(s"Fee must be positive."))

  private def feeInUnits(tx: Transaction): Either[ValidationError, Long] = {
    FeeConstants
      .get(tx.tpe)
      .map { baseFee =>
        tx match {
          case tx: TransferTransaction =>
            baseFee + (tx.transfers.size + 1) / 2
          case _ => baseFee
        }
      }
      .toRight(UnsupportedTransactionType)
  }

  def getMinFee(tx: Transaction): Either[ValidationError, FeeDetails] =
    feeInUnits(tx).map { units =>
      val amountInHearth = units * FeeUnit
      FeeDetails(Hearth, Chain.empty, amountInHearth, amountInHearth)
    }
}
