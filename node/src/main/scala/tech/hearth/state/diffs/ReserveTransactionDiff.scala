package tech.hearth.state.diffs

import cats.syntax.either.*
import tech.hearth.lang.ValidationError
import tech.hearth.state.*
import tech.hearth.transaction.Asset
import tech.hearth.transaction.Asset.{Hearth, IssuedAsset}
import tech.hearth.transaction.ReserveTransaction
import tech.hearth.transaction.TxValidationError.GenericError

/** ReserveTransaction semantics: lock `amount` of `assetId` from the sender's balance against a registered miner,
  * accumulating into Blockchain.reservedAmount(sender, miner, assetId). Accumulate-only for now - there is no
  * unreserve/settlement transaction yet (see the Reserve/BindApiKey consensus plan).
  */
object ReserveTransactionDiff {
  def apply(blockchain: Blockchain)(tx: ReserveTransaction): Either[ValidationError, StateSnapshot] = {
    val sender = tx.sender.toAddress
    for {
      _ <- assetIssued(blockchain, tx.assetId)
      _ <- assetIssued(blockchain, tx.feeAssetId)
      _ <- Either.raiseUnless(blockchain.isRegisteredMiner(tx.miner))(GenericError(s"${tx.miner} is not a registered miner"))
      portfolios <- Portfolio
        .combine(
          Map(sender -> Portfolio.build(tx.assetId, -tx.amount.value)),
          Map(sender -> Portfolio.build(tx.feeAssetId, -tx.fee.value))
        )
        .leftMap(GenericError(_))
      newReservedAmount <- safeSum(
        blockchain.reservedAmount(sender, tx.miner, tx.assetId),
        tx.amount.value,
        s"$sender -> ${tx.miner} reserved ${tx.assetId}"
      ).leftMap(GenericError(_))
      snapshot <- StateSnapshot.build(
        blockchain,
        portfolios = portfolios,
        reservedAmounts = Map((sender, tx.miner, tx.assetId) -> newReservedAmount)
      )
    } yield snapshot
  }

  private def assetIssued(blockchain: Blockchain, asset: Asset): Either[ValidationError, Unit] = asset match {
    case Hearth                 => ().asRight
    case asset @ IssuedAsset(_) => Either.cond(blockchain.assetDescription(asset).isDefined, (), GenericError(s"Asset $asset is not issued"))
  }
}
