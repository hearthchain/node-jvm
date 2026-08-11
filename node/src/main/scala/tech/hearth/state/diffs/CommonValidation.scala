package tech.hearth.state.diffs

import cats.implicits.toBifunctorOps
import tech.hearth.account.{Address, AddressScheme}
import tech.hearth.lang.ValidationError
import tech.hearth.settings.FunctionalitySettings
import tech.hearth.state.*
import tech.hearth.state.diffs.invoke.InvokeDiffsCommon
import tech.hearth.transaction.Asset.{IssuedAsset, Hearth}
import tech.hearth.transaction.TxValidationError.*
import tech.hearth.transaction.transfer.*
import tech.hearth.transaction.{Asset, *}

import scala.util.{Left, Right}

object CommonValidation {
  def disallowSendingGreaterThanBalance[T <: Transaction](blockchain: Blockchain, tx: T): Either[ValidationError, T] = {
    def checkTransfer(
        sender: Address,
        assetId: Asset,
        amount: Long,
        feeAssetId: Asset,
        feeAmount: Long,
        allowFeeOverdraft: Boolean = false
    ): Either[ValidationError, T] = {
      val amountPortfolio = assetId match {
        case aid @ IssuedAsset(_) => Portfolio.build(aid -> -amount)
        case Hearth               => Portfolio(-amount)
      }
      val feePortfolio = feeAssetId match {
        case aid @ IssuedAsset(_) => Portfolio.build(aid -> -feeAmount)
        case Hearth               => Portfolio(-feeAmount)
      }

      val checkedTx = for {
        _ <- assetId match {
          case IssuedAsset(id) => InvokeDiffsCommon.checkAsset(blockchain, id)
          case Hearth          => Right(())
        }
        spendings <- amountPortfolio.combine(feePortfolio)
        oldHearthBalance = blockchain.balance(sender, Hearth)

        newHearthBalance    <- safeSum(oldHearthBalance, spendings.balance, "Spendings")
        feeUncheckedBalance <- safeSum(oldHearthBalance, amountPortfolio.balance, "Transfer amount")

        overdraftFilter = allowFeeOverdraft && feeUncheckedBalance >= 0
        _ <- Either.cond(
          overdraftFilter || newHearthBalance >= 0,
          (),
          "Attempt to transfer unavailable funds: Transaction application leads to " +
            s"negative hearth balance to (at least) temporary negative state, current balance equals $oldHearthBalance, " +
            s"spends equals ${spendings.balance}, result is $newHearthBalance"
        )
        _ <- spendings.assets
          .collectFirst {
            case (aid, delta) if delta < 0 && blockchain.balance(sender, aid) + delta < 0 =>
              val availableBalance = blockchain.balance(sender, aid)
              "Attempt to transfer unavailable funds: Transaction application leads to negative asset " +
                s"'$aid' balance to (at least) temporary negative state, current balance is $availableBalance, " +
                s"spends equals $delta, result is ${availableBalance + delta}"
          }
          .toLeft(())
      } yield tx

      checkedTx.leftMap(GenericError(_))
    }

    tx match {
      case ttx: TransferTransaction => checkTransfer(ttx.sender.toAddress, ttx.assetId, ttx.amount.value, ttx.feeAssetId, ttx.fee.value)
      case mtx: MassTransferTransaction =>
        checkTransfer(mtx.sender.toAddress, mtx.assetId, mtx.transfers.map(_.amount.value).sum, Hearth, mtx.fee.value)
      case _ => Right(tx)
    }
  }

  def disallowDuplicateIds[T <: Transaction](blockchain: Blockchain, tx: T): Either[ValidationError, T] = tx match {
    case _ =>
      val id = tx.id()
      Either.cond(!blockchain.containsTransaction(tx), tx, AlreadyInTheState(id, blockchain.transactionMeta(id).get.height))
  }

  /** Rejects a transaction whose proof was not made by its sender.
    *
    * This is the only place the check is enforced: `Proven.firstProofIsValidSignatureAfterV6` computes it, but its
    * other callers deliberately discard the result - `ParSignatureChecker` only warms the lazy val on a parallel pool.
    * Since every admission path (block application, UTX, state hash) goes through `TransactionDiffer.validateCommon`,
    * putting it here covers all of them at once.
    */
  def disallowInvalidProofs[T <: Transaction](tx: T): Either[ValidationError, T] =
    tx match {
      case p: ProvenTransaction => p.firstProofIsValidSignatureAfterV6.map(_ => tx)
      case _                    => Right(tx)
    }

  def disallowFromAnotherNetwork[T <: Transaction](tx: T, currentChainId: Byte): Either[ValidationError, T] =
    Either.cond(
      tx.chainId == currentChainId,
      tx,
      GenericError(
        s"Transaction from another network, expected: ${AddressScheme.current.chainId}(${AddressScheme.current.chainId.toChar}), actual: ${tx.chainId}(${tx.chainId.toChar})"
      )
    )

  def disallowTxFromFuture[T <: Transaction](settings: FunctionalitySettings, time: Long, tx: T): Either[ValidationError, T] = {
    if (tx.timestamp - time > settings.maxTransactionTimeForwardOffset.toMillis)
      Left(
        Mistiming(
          s"""Transaction timestamp ${tx.timestamp}
             |is more than ${settings.maxTransactionTimeForwardOffset.toMillis}ms in the future
             |relative to block timestamp $time""".stripMargin
            .replaceAll("\n", " ")
            .replaceAll("\r", "")
        )
      )
    else Right(tx)
  }

  def disallowTxFromPast[T <: Transaction](settings: FunctionalitySettings, prevBlockTime: Option[Long], tx: T): Either[ValidationError, T] =
    prevBlockTime match {
      case Some(t) if (t - tx.timestamp) > settings.maxTransactionTimeBackOffset.toMillis =>
        Left(
          Mistiming(
            s"""Transaction timestamp ${tx.timestamp}
               |is more than ${settings.maxTransactionTimeBackOffset.toMillis}ms in the past
               |relative to previous block timestamp $prevBlockTime""".stripMargin
              .replaceAll("\n", " ")
              .replaceAll("\r", "")
          )
        )
      case _ => Right(tx)
    }
}
