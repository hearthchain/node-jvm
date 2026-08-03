package tech.hearth.transaction.validation.impl

import cats.data.ValidatedNel
import tech.hearth.lang.ValidationError
import tech.hearth.transaction.TxValidationError.{GenericError, OrderValidationError}
import tech.hearth.transaction.assets.exchange.{ExchangeTransaction, Order, OrderType}
import tech.hearth.transaction.validation.TxValidator

object ExchangeTxValidator extends TxValidator[ExchangeTransaction] {
  override def validate(tx: ExchangeTransaction): ValidatedNel[ValidationError, ExchangeTransaction] = {
    import tx.*

    // No bounds check on the matcher fees: TxMatcherFee refines them to (0; Order.MaxAmount), so an out-of-range fee
    // cannot be constructed in the first place - ExchangeTransaction.create rejects it with TxMatcherFee.errMsg.
    V.seq(tx)(
      V.cond(fee.value <= Order.MaxAmount, GenericError("fee too large")),
      V.cond(buyOrder.orderType == OrderType.BUY, GenericError("buyOrder should has OrderType.BUY")),
      V.cond(sellOrder.orderType == OrderType.SELL, GenericError("sellOrder should has OrderType.SELL")),
      V.cond(buyOrder.matcherPublicKey == sellOrder.matcherPublicKey, GenericError("buyOrder.matcher should be the same as sellOrder.matcher")),
      V.cond(buyOrder.assetPair == sellOrder.assetPair, GenericError("Both orders should have same AssetPair")),
      V.cond(buyOrder.isValid(timestamp), OrderValidationError(buyOrder, buyOrder.isValid(timestamp).messages())),
      V.cond(sellOrder.isValid(timestamp), OrderValidationError(sellOrder, sellOrder.isValid(timestamp).messages()))
    )
  }
}
