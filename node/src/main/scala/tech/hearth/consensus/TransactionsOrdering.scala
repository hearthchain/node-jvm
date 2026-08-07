package tech.hearth.consensus

import tech.hearth.state.Blockchain
import tech.hearth.state.diffs.FeeValidation
import tech.hearth.transaction.Asset.{IssuedAsset, Waves}
import tech.hearth.transaction.assets.exchange.ExchangeTransaction
import tech.hearth.transaction.{Authorized, Transaction}

object TransactionsOrdering {
  trait WavesOrdering extends Ordering[Transaction] {
    def isWhitelisted(t: Transaction): Boolean = false
    def transactionSize(tx: Transaction): Int  = tx.bytesSize
    def txTimestampOrder(ts: Long): Long
    protected def feeInWaves(t: Transaction): Long = t.assetFee match {
      case (Waves, fee) => fee
      case _            => 0
    }
    private def orderBy(t: Transaction): (Boolean, Double, Long, Long) = {
      val byWhiteList = !isWhitelisted(t) // false < true
      val size        = transactionSize(t)
      val byFee       = -feeInWaves(t)
      val byTimestamp = txTimestampOrder(t.timestamp)

      (byWhiteList, byFee.toDouble / size.toDouble, byFee, byTimestamp)
    }
    override def compare(first: Transaction, second: Transaction): Int = {
      import Ordering.Double.TotalOrdering
      implicitly[Ordering[(Boolean, Double, Long, Long)]].compare(orderBy(first), orderBy(second))
    }
  }

  object InBlock extends WavesOrdering {
    // sorting from network start
    override def txTimestampOrder(ts: Long): Long = -ts
  }

  case class InUTXPool(whitelistAddresses: Set[String], blockchain: Blockchain) extends WavesOrdering {

    override def transactionSize(tx: Transaction): Int = tx match {
      case _: ExchangeTransaction => 676 // order v3 with matcher fee in custom assets, tx V2
      case _                      => super.transactionSize(tx)
    }

    override def isWhitelisted(t: Transaction): Boolean =
      t match {
        case _ if whitelistAddresses.isEmpty                                           => false
        case a: Authorized if whitelistAddresses.contains(a.sender.toAddress.toString) => true
        case _                                                                         => false
      }
    override def txTimestampOrder(ts: Long): Long = ts

    override protected def feeInWaves(t: Transaction): Long = t.assetFee match {
      case (Waves, fee)              => fee
      case (asset: IssuedAsset, fee) =>
        // defensive fallback only - a tx admitted into the pool already passed feePortfolios, so the asset
        // necessarily exists and has a minAssetFee; None here would mean the asset vanished after admission
        blockchain.assetDescription(asset).fold(0L) { info =>
          (BigInt(fee) * FeeValidation.FeeUnit / info.minAssetFee.value).bigInteger.longValueExact()
        }
    }
  }
}
