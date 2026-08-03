package tech.hearth.state.diffs

import cats.data.Chain
import tech.hearth.lang.ValidationError
import tech.hearth.state.*
import tech.hearth.transaction.*
import tech.hearth.transaction.Asset.{IssuedAsset, Waves}
import tech.hearth.transaction.TxValidationError.*
import tech.hearth.transaction.transfer.*

object FeeValidation {

  case class FeeDetails(asset: Asset, requirements: Chain[String], minFeeInAsset: Long, minFeeInWaves: Long)

  val ScriptExtraFee    = 400000L
  val FeeUnit           = 100000
  val NFTMultiplier     = 0.001
  val BlockV5Multiplier = 0.001

  val FeeConstants: Map[TransactionType, Long] = Map(
    TransactionType.Genesis            -> 0,
    TransactionType.Transfer           -> 1,
    TransactionType.MassTransfer       -> 1,
    TransactionType.Lease              -> 1,
    TransactionType.LeaseCancel        -> 1,
    TransactionType.Exchange           -> 3,
    TransactionType.CommitToGeneration -> 100 // TODO: decide
  )

  def apply(tx: Transaction): Either[ValidationError, Unit] =
    Either.cond(tx.fee > 0 || !tx.isInstanceOf[Authorized], (), GenericError(s"Fee must be positive."))

  private case class FeeInfo(assetInfo: Option[(IssuedAsset, AssetDescription)], requirements: Chain[String], wavesFee: Long)

  private def feeInUnits(tx: Transaction): Either[ValidationError, Long] = {
    FeeConstants
      .get(tx.tpe)
      .map { baseFee =>
        tx match {
          case tx: MassTransferTransaction =>
            baseFee + (tx.transfers.size + 1) / 2
          case _ => baseFee
        }
      }
      .toRight(UnsupportedTransactionType)
  }

  private def feeAfterSponsorship(tx: Transaction): Either[ValidationError, FeeInfo] =
    feeInUnits(tx).map(x => FeeInfo(None, Chain.empty, x * FeeUnit))

  def getMinFee(tx: Transaction): Either[ValidationError, FeeDetails] = {
    feeAfterSponsorship(tx)
      .map {
        case FeeInfo(Some((assetId, _)), reqs, amountInWaves) =>
          FeeDetails(assetId, reqs, Sponsorship.fromWaves(amountInWaves, 0), amountInWaves)
        case FeeInfo(None, reqs, amountInWaves) =>
          FeeDetails(Waves, reqs, amountInWaves, amountInWaves)
      }
  }
}
