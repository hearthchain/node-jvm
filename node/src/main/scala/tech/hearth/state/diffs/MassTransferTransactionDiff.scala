package tech.hearth.state.diffs

import cats.implicits.{toBifunctorOps, toFoldableOps}
import cats.instances.list.*
import cats.syntax.traverse.*
import tech.hearth.account.Address
import tech.hearth.lang.ValidationError
import tech.hearth.state.*
import tech.hearth.transaction.Asset.{IssuedAsset, Hearth}
import tech.hearth.transaction.TxValidationError.{GenericError, Validation}
import tech.hearth.transaction.transfer.*
import tech.hearth.transaction.transfer.MassTransferTransaction.ParsedTransfer

object MassTransferTransactionDiff {

  def apply(blockchain: Blockchain)(tx: MassTransferTransaction): Either[ValidationError, StateSnapshot] = {
    def parseTransfer(xfer: ParsedTransfer): Validation[(Map[Address, Portfolio], Long)] =
      Right(
        (
          tx.assetId
            .fold(Map[Address, Portfolio](xfer.address -> Portfolio(xfer.amount.value))) { asset =>
              Map(xfer.address -> Portfolio.build(asset, xfer.amount.value))
            },
          xfer.amount.value
        )
      )

    val portfoliosEi = tx.transfers.toList.traverse(parseTransfer)

    portfoliosEi.flatMap { (list: List[(Map[Address, Portfolio], Long)]) =>
      val sender   = Address.fromPublicKey(tx.sender)
      val foldInit = (Map[Address, Portfolio](sender -> Portfolio(-tx.fee.value)), 0L)
      list
        .foldM(foldInit) { case ((totalPortfolios, totalTransferAmount), (portfolios, transferAmount)) =>
          Portfolio.combine(totalPortfolios, portfolios).map((_, totalTransferAmount + transferAmount))
        }
        .flatMap { case (recipientPortfolios, totalAmount) =>
          Portfolio.combine(
            recipientPortfolios,
            tx.assetId
              .fold(Map[Address, Portfolio](sender -> Portfolio(-totalAmount))) { asset =>
                Map[Address, Portfolio](sender -> Portfolio.build(asset, -totalAmount))
              }
          )
        }
        .leftMap(GenericError(_))
        .flatMap { completePortfolio =>
          val assetIssued =
            tx.assetId match {
              case Hearth                 => true
              case asset @ IssuedAsset(_) => blockchain.assetDescription(asset).isDefined
            }
          Either
            .cond(
              assetIssued,
              StateSnapshot.build(blockchain, portfolios = completePortfolio),
              GenericError(s"Attempt to transfer a nonexistent asset")
            )
            .flatten
        }
    }
  }
}
