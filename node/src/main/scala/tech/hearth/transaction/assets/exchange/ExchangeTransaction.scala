package tech.hearth.transaction.assets.exchange

import tech.hearth.account.{AddressScheme, PrivateKey, PublicKey}
import tech.hearth.common.state.ByteStr
import tech.hearth.crypto
import tech.hearth.lang.ValidationError
import tech.hearth.transaction.*
import tech.hearth.transaction.TxValidationError.GenericError
import tech.hearth.transaction.serialization.impl.ExchangeTxSerializer
import tech.hearth.transaction.validation.TxValidator
import tech.hearth.transaction.validation.impl.ExchangeTxValidator
import monix.eval.Coeval
import play.api.libs.json.JsObject

case class ExchangeTransaction(
    order1: Order,
    order2: Order,
    amount: TxExchangeAmount,
    price: TxExchangePrice,
    buyMatcherFee: TxMatcherFee,
    sellMatcherFee: TxMatcherFee,
    fee: TxPositiveAmount,
    timestamp: Long,
    proofs: Proofs,
    chainId: Byte
) extends Transaction(TransactionType.Exchange),
      ProvenTransaction,
      TxWithFee.InWaves,
      FastHashId {

  override type T = ExchangeTransaction

  override def addProof(proof: ByteStr): ExchangeTransaction = copy(proofs = proofs.add(proof))

  val (buyOrder, sellOrder) = if (order1.orderType == OrderType.BUY) (order1, order2) else (order2, order1)

  /** The matcher's own proof plus both orders': `tap` used to discard the orders' results, so an order signed by the
    * wrong key was accepted.
    */
  override protected def verifyFirstProof(): Either[GenericError, Unit] =
    super
      .verifyFirstProof()
      .flatMap(_ => order1.firstProofIsValidSignatureAfterV6)
      .flatMap(_ => order2.firstProofIsValidSignatureAfterV6)

  override val sender: PublicKey = buyOrder.matcherPublicKey

  override val json: Coeval[JsObject] = Coeval.evalOnce(ExchangeTxSerializer.toJson(this))
}

object ExchangeTransaction {
  type TransactionT = ExchangeTransaction

  implicit val validator: TxValidator[ExchangeTransaction] = ExchangeTxValidator

  implicit def sign(tx: ExchangeTransaction, privateKey: PrivateKey): ExchangeTransaction =
    tx.copy(proofs = Proofs(crypto.sign(privateKey, tx.bodyBytes())))

  val typeId: TxType = 7: Byte

  def create(
      order1: Order,
      order2: Order,
      amount: Long,
      price: Long,
      buyMatcherFee: Long,
      sellMatcherFee: Long,
      fee: Long,
      timestamp: Long,
      proofs: Proofs = Proofs.empty,
      chainId: Byte = AddressScheme.current.chainId
  ): Either[ValidationError, ExchangeTransaction] =
    for {
      fee    <- TxPositiveAmount(fee)(TxValidationError.InsufficientFee)
      amount <- TxExchangeAmount(amount)(GenericError(TxExchangeAmount.errMsg))
      price  <- TxExchangePrice(price)(GenericError(TxExchangePrice.errMsg))
      bmf    <- TxMatcherFee(buyMatcherFee)(GenericError(TxMatcherFee.errMsg))
      smf    <- TxMatcherFee(sellMatcherFee)(GenericError(TxMatcherFee.errMsg))
      tx <- ExchangeTransaction(
        order1,
        order2,
        amount,
        price,
        bmf,
        smf,
        fee,
        timestamp,
        proofs,
        chainId
      ).validatedEither
    } yield tx
}
