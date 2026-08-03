package tech.hearth.generator

import cats.Show
import tech.hearth.generator.WideTransactionGenerator.Settings
import tech.hearth.generator.utils.Gen
import tech.hearth.transaction.Transaction
import pureconfig.ConfigReader
import tech.hearth.crypto.SigningKey

class WideTransactionGenerator(settings: Settings, accounts: Seq[SigningKey]) extends TransactionGenerator {
  require(accounts.nonEmpty)

  private val limitedRecipientGen = Gen.address(settings.limitDestAccounts)

  override def next(): Iterator[Transaction] = {
    Gen.txs(settings.minFee, settings.maxFee, accounts, limitedRecipientGen).take(settings.transactions)
  }

}

object WideTransactionGenerator {

  case class Settings(transactions: Int, limitDestAccounts: Option[Int], minFee: Long, maxFee: Long) derives ConfigReader {
    require(transactions > 0)
    require(limitDestAccounts.forall(_ > 0))
  }

  object Settings {
    implicit val toPrintable: Show[Settings] = { x =>
      import x.*
      s"""transactions per iteration: $transactions
         |number of recipients is ${limitDestAccounts.map(x => s"limited by $x").getOrElse("not limited")}
         |min fee: $minFee
         |max fee: $maxFee""".stripMargin
    }
  }

}
