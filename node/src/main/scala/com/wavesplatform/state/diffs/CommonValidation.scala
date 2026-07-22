package com.wavesplatform.state.diffs

import cats.implicits.toBifunctorOps
import com.wavesplatform.account.{Address, AddressScheme}
import com.wavesplatform.lang.ValidationError
import com.wavesplatform.settings.FunctionalitySettings
import com.wavesplatform.state.*
import com.wavesplatform.state.diffs.invoke.InvokeDiffsCommon
import com.wavesplatform.transaction.Asset.{IssuedAsset, Waves}
import com.wavesplatform.transaction.TxValidationError.*
import com.wavesplatform.transaction.transfer.*
import com.wavesplatform.transaction.{Asset, *}

import scala.util.{Left, Right}

object CommonValidation {
  def disallowSendingGreaterThanBalance[T <: Transaction](blockchain: Blockchain, blockTime: Long, tx: T): Either[ValidationError, T] =
    if (blockTime >= blockchain.settings.functionalitySettings.allowTemporaryNegativeUntil) {
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
          case Waves                => Portfolio(-amount)
        }
        val feePortfolio = feeAssetId match {
          case aid @ IssuedAsset(_) => Portfolio.build(aid -> -feeAmount)
          case Waves                => Portfolio(-feeAmount)
        }

        val checkedTx = for {
          _ <- assetId match {
            case IssuedAsset(id) => InvokeDiffsCommon.checkAsset(blockchain, id)
            case Waves           => Right(())
          }
          spendings <- amountPortfolio.combine(feePortfolio)
          oldWavesBalance = blockchain.balance(sender, Waves)

          newWavesBalance     <- safeSum(oldWavesBalance, spendings.balance, "Spendings")
          feeUncheckedBalance <- safeSum(oldWavesBalance, amountPortfolio.balance, "Transfer amount")

          overdraftFilter = allowFeeOverdraft && feeUncheckedBalance >= 0
          _ <- Either.cond(
            overdraftFilter || newWavesBalance >= 0,
            (),
            "Attempt to transfer unavailable funds: Transaction application leads to " +
              s"negative waves balance to (at least) temporary negative state, current balance equals $oldWavesBalance, " +
              s"spends equals ${spendings.balance}, result is $newWavesBalance"
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
          checkTransfer(mtx.sender.toAddress, mtx.assetId, mtx.transfers.map(_.amount.value).sum, Waves, mtx.fee.value)
        case _ => Right(tx)
      }
    } else Right(tx)

  def disallowDuplicateIds[T <: Transaction](blockchain: Blockchain, tx: T): Either[ValidationError, T] = tx match {
    case _ =>
      val id = tx.id()
      Either.cond(!blockchain.containsTransaction(tx), tx, AlreadyInTheState(id, blockchain.transactionMeta(id).get.height))
  }

  def disallowFromAnotherNetwork[T <: Transaction](tx: T, currentChainId: Byte): Either[ValidationError, T] =
    Either.cond(
      tx.chainId == currentChainId,
      tx,
      GenericError(
        s"Address belongs to another network: expected: ${AddressScheme.current.chainId}(${AddressScheme.current.chainId.toChar}), actual: ${tx.chainId}(${tx.chainId.toChar})"
      )
    )

  def disallowTxFromFuture[T <: Transaction](settings: FunctionalitySettings, time: Long, tx: T): Either[ValidationError, T] = {
    val allowTransactionsFromFutureByTimestamp = tx.timestamp < settings.allowTransactionsFromFutureUntil
    if (!allowTransactionsFromFutureByTimestamp && tx.timestamp - time > settings.maxTransactionTimeForwardOffset.toMillis)
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
