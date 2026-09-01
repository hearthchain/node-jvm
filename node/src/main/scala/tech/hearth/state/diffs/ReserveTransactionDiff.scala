package tech.hearth.state.diffs

import cats.syntax.either.*
import tech.hearth.lang.ValidationError
import tech.hearth.state.*
import tech.hearth.transaction.Asset.IssuedAsset
import tech.hearth.transaction.ReserveTransaction
import tech.hearth.transaction.TxValidationError.GenericError

/** ReserveTransaction semantics: lock `amount` of `assetId` from the sender's balance against a registered miner,
  * accumulating into Blockchain.reservedAmount(sender, miner, assetId). Accumulate-only by design: SettleTransaction
  * only ever compares against this total (see SettleTransactionDiff), and WithdrawTransaction - the counterpart that
  * would return the unsettled remainder - is still an unimplemented stub.
  *
  * The reserved amount is debited from the sender's portfolio balance (like CommitToGeneration's deposit is *not*,
  * see Portfolio.generationDeposit) and credited nowhere else - it is not moved to the miner or to any other
  * address, only recorded in reservedAmounts. Until Settle/Withdraw exist, a reserved amount is unspendable and
  * unrecoverable by design; this is not yet safe to expose on a network carrying real value.
  */
object ReserveTransactionDiff {
  def apply(blockchain: Blockchain)(tx: ReserveTransaction): Either[ValidationError, StateSnapshot] = {
    val sender = tx.sender.toAddress
    for {
      // tx.feeAssetId's existence is already checked upstream, by TransactionDiffer.feePortfolios (driven by
      // TxWithFee.InCustomAsset), before this diff ever runs - only the reserved asset itself needs checking here.
      _ <- assetIssued(blockchain, tx.assetId)
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

  private def assetIssued(blockchain: Blockchain, asset: IssuedAsset): Either[ValidationError, Unit] =
    Either.cond(blockchain.assetDescription(asset).isDefined, (), GenericError(s"Asset $asset is not issued"))
}
