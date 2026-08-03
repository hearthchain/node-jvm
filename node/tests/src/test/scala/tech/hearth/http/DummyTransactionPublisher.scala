package tech.hearth.http

import tech.hearth.lang.ValidationError
import tech.hearth.network.TransactionPublisher
import tech.hearth.transaction.Transaction
import tech.hearth.transaction.smart.script.trace.TracedResult

import scala.concurrent.Future

object DummyTransactionPublisher {
  val accepting: TransactionPublisher = { (_, _) =>
    Future.successful(TracedResult(Right(true)))
  }

  def rejecting(error: Transaction => ValidationError): TransactionPublisher = { (tx, _) =>
    Future.successful(TracedResult(Left(error(tx))))
  }
}
