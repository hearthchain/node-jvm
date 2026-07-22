package com.wavesplatform.state.diffs

import cats.data.Chain
import cats.syntax.foldable.*
import com.wavesplatform.lang.ValidationError
import com.wavesplatform.state.*
import com.wavesplatform.transaction.*
import com.wavesplatform.transaction.Asset.{IssuedAsset, Waves}
import com.wavesplatform.transaction.TxValidationError.*
import com.wavesplatform.transaction.transfer.*

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

  def apply(blockchain: Blockchain, tx: Transaction): Either[ValidationError, Unit] = {
    if (Height(blockchain.height) >= Sponsorship.sponsoredFeesSwitchHeight(blockchain)) {
      for {
        feeDetails <- getMinFee(blockchain, tx)
        _ <- Either.cond(
          feeDetails.minFeeInAsset <= tx.fee,
          (),
          notEnoughFeeError(tx.tpe, feeDetails, tx.fee)
        )
      } yield ()
    } else {
      Either.cond(tx.fee > 0 || !tx.isInstanceOf[Authorized], (), GenericError(s"Fee must be positive."))
    }
  }

  private def notEnoughFeeError(txType: TransactionType, feeDetails: FeeDetails, feeAmount: Long): ValidationError = {
    val actualFee   = s"$feeAmount in ${feeDetails.asset.fold("WAVES")(_.id.toString)}"
    val requiredFee = s"${feeDetails.minFeeInWaves} WAVES${feeDetails.asset.fold("")(id => s" or ${feeDetails.minFeeInAsset} ${id.id.toString}")}"

    val errorMessage = s"Fee for ${txType.transactionName} ($actualFee) does not exceed minimal value of $requiredFee."

    GenericError((if (feeDetails.requirements.nonEmpty) (feeDetails.requirements mkString_ ". ") ++ ". " else "") ++ errorMessage)
  }

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

  private def feeAfterSponsorship(txAsset: Asset, blockchain: Blockchain, tx: Transaction): Either[ValidationError, FeeInfo] = {
    if (Height(blockchain.height) < Sponsorship.sponsoredFeesSwitchHeight(blockchain)) {
      // This could be true for private blockchains
      feeInUnits(tx).map(x => FeeInfo(None, Chain.empty, x * FeeUnit))
    } else {
      for {
        feeInUnits <- feeInUnits(tx)
        r <- txAsset match {
          case Waves => Right(FeeInfo(None, Chain.empty, feeInUnits * FeeUnit))
          case assetId @ IssuedAsset(_) =>
            for {
              assetInfo <- blockchain
                .assetDescription(assetId)
                .toRight(GenericError(s"Asset ${assetId.id.toString} does not exist, cannot be used to pay fees"))
              wavesFee <- Either.cond(
                false, // assetInfo.sponsorship > 0,
                feeInUnits * FeeUnit,
                GenericError(s"Asset ${assetId.id.toString} is not sponsored, cannot be used to pay fees")
              )
            } yield FeeInfo(Some((assetId, assetInfo)), Chain.empty, wavesFee)
        }
      } yield r
    }
  }

  def getMinFee(blockchain: Blockchain, tx: Transaction): Either[ValidationError, FeeDetails] = {
    feeAfterSponsorship(tx.feeAssetId, blockchain, tx)
      .map {
        case FeeInfo(Some((assetId, _)), reqs, amountInWaves) =>
          FeeDetails(assetId, reqs, Sponsorship.fromWaves(amountInWaves, 0), amountInWaves)
        case FeeInfo(None, reqs, amountInWaves) =>
          FeeDetails(Waves, reqs, amountInWaves, amountInWaves)
      }
  }
}
