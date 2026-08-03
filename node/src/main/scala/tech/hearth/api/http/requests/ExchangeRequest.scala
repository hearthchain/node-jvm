package tech.hearth.api.http.requests

import tech.hearth.common.state.ByteStr
import tech.hearth.lang.ValidationError
import tech.hearth.transaction.assets.exchange.{ExchangeTransaction, Order}
import tech.hearth.transaction.{Proofs, TxTimestamp}
import play.api.libs.json.{Format, Json}

case class ExchangeRequest(
    order1: Order,
    order2: Order,
    amount: Long,
    price: Long,
    buyMatcherFee: Long,
    sellMatcherFee: Long,
    sender: Option[String] = None,
    fee: Option[Long] = None,
    timestamp: Option[TxTimestamp] = None,
    signature: Option[ByteStr] = None,
    proofs: Option[Proofs] = None
) extends TxBroadcastRequest[ExchangeTransaction] {
  def toTx: Either[ValidationError, ExchangeTransaction] =
    for {
      validProofs <- toProofs(signature, proofs)
      tx <- ExchangeTransaction.create(
        order1,
        order2,
        amount,
        price,
        buyMatcherFee,
        sellMatcherFee,
        fee.getOrElse(0L),
        timestamp.getOrElse(0L),
        validProofs
      )
    } yield tx
}

object ExchangeRequest {
  given Format[ExchangeRequest] = Json.format
}
