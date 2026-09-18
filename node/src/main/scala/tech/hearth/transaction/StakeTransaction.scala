package tech.hearth.transaction

import tech.hearth.account.*
import tech.hearth.common.state.ByteStr
import tech.hearth.lang.ValidationError
import tech.hearth.state.Height
import tech.hearth.transaction.serialization.impl.StakeTxSerializer
import tech.hearth.transaction.validation.TxValidator
import tech.hearth.transaction.validation.impl.StakeTxValidator
import monix.eval.Coeval
import play.api.libs.json.JsObject

/** Stakes HRTH, earning a share of each generation period's Cred issuance. See StakeTransactionDiff for the real
  * semantics and `docs/notes/cred-economy.md` for the economy this feeds.
  *
  * @param periodStart
  *   The start height of the generation period this stake takes effect in, which must be the period *after* the one
  *   the transaction is applied in - a stake never earns in the epoch it is sent in. Named like
  *   CommitToGenerationTransaction.generationPeriodStart and checked the same way, against
  *   `blockchain.currentGenerationPeriod.next.start`. It is carried explicitly rather than derived so that what the
  *   sender signed pins which period they meant, instead of depending on where in the chain the transaction lands.
  * @param amount
  *   The new total staked, not a delta, in embers. TxNonNegativeAmount rather than TxPositiveAmount because 0 is
  *   meaningful: it releases everything currently staked, at the start of `periodStart`. The HRTH is locked as soon
  *   as this transaction is applied when `amount` raises the stake, since it already counts for the next period; a
  *   reduction only frees funds at `periodStart` (see Blockchain.lockedStake).
  */
final case class StakeTransaction(
    sender: PublicKey,
    periodStart: Height,
    amount: TxNonNegativeAmount,
    fee: TxPositiveAmount,
    timestamp: TxTimestamp,
    proofs: Proofs,
    networkId: NetworkId
) extends Transaction(TransactionType.Stake),
      ProvenTransaction,
      TxWithFee.InHearth,
      FastHashId {
  override type T = StakeTransaction

  override val json: Coeval[JsObject] = Coeval.evalOnce(StakeTxSerializer.toJson(this))

  override def addProof(proof: ByteStr): StakeTransaction = copy(proofs = this.proofs.add(proof))
}

object StakeTransaction {
  implicit val validator: TxValidator[StakeTransaction] = StakeTxValidator

  def create(
      sender: PublicKey,
      periodStart: Height,
      amount: Long,
      fee: Long,
      timestamp: TxTimestamp,
      proofs: Proofs,
      networkId: NetworkId = NetworkId.current
  ): Either[ValidationError, StakeTransaction] =
    for {
      amount <- TxNonNegativeAmount(amount)(TxValidationError.NegativeAmount(amount, "hearth"))
      fee    <- TxPositiveAmount(fee)(TxValidationError.InsufficientFee)
      tx     <- StakeTransaction(sender, periodStart, amount, fee, timestamp, proofs, networkId).validatedEither
    } yield tx
}
