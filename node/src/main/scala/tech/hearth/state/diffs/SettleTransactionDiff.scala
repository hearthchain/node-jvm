package tech.hearth.state.diffs

import cats.syntax.either.*
import tech.hearth.account.{Address, PublicKey}
import tech.hearth.crypto
import tech.hearth.lang.ValidationError
import tech.hearth.state.*
import tech.hearth.state.diffs.BlockDiffer.Fraction
import tech.hearth.transaction.Asset
import tech.hearth.transaction.Asset.{Hearth, IssuedAsset}
import tech.hearth.transaction.SettleTransaction
import tech.hearth.transaction.TxValidationError.GenericError

/** SettleTransaction semantics: a miner submits an enclave-signed batch of (client, cumulative spent) settlements,
  * retiring the settled portion of what each client reserved with it (see ReserveTransactionDiff). Three checks per
  * settlement, matching the "settle" analysis in the tokenomics gist:
  *
  *   - "reservation open": the client must have a positive Blockchain.reservedAmount with this miner for the asset;
  *   - "counter non-decreasing": the new cumulative value can't be lower than what's already recorded in
  *     Blockchain.settledAmount (checked against the running total within this same batch too, in case a client
  *     appears more than once);
  *   - "counter within the total ever reserved": the new cumulative value can't exceed Blockchain.reservedAmount.
  *
  * "Settled Cred is retired" (the gist's term for destruction) needs no extra bookkeeping for the bulk of it: the
  * reserved amount was already debited from the client's spendable balance at Reserve time and credited nowhere
  * (ReserveTransactionDiff), so recording it as settled is what makes that debit permanent for whatever isn't paid
  * out below - there is nothing left to burn or move. Asset volumes themselves are never touched, matching every
  * other asset in this state model: they are fixed at issuance (see StateSnapshot.assetVolumes) and this repo has
  * no Burn/Reissue transaction type.
  *
  * hearth-tokenomics-spec S4 ("Cred retirement - the split") describes retirement as a three-way split of the
  * newly-settled amount, not a full burn: φ_b = 0.60 burned, φ_n = 0.30 to the serving node (immediate cash flow),
  * φ_v = 0.10 to a verifier pool. Only two of the three are implemented here (ServingNodeCredPart, BurnedWorkPart
  * below) - the verifier pool has no on-chain destination of any kind yet, so that 10% is, for now, folded into
  * the effectively-burned remainder rather than guessed at.
  *
  * The burned share feeds workBoost (S4.1: "only the burned fraction counts toward consensus... never the node or
  * verifier share", S7.1): "let epoch be a generation period" (per project decision), so each settlement's
  * BurnedWorkPart is attributed to the settling enclave's validator (RegisteredEnclave.validator - the consensus
  * generator this miner boosts, not the miner itself) for the *current* generation period, accumulated into
  * Blockchain.workDone. GeneratingBalanceProvider reads it back to boost that validator's generating balance the
  * *following* period - see WorkBoost and CLAUDE.md's "workBoost" section for the (deliberately simplified, see
  * that section) curve. This diff only accumulates the signal; it has no say in how it's later consumed.
  */
object SettleTransactionDiff {

  // φ_n = 0.30 (hearth-tokenomics-spec S4, a launch value marked "governance"). Hardcoded the same way
  // BlockDiffer.CurrentBlockFeePart/BlockRewardCalculator's tier fractions are: a fixed protocol constant next to
  // the logic that uses it, not a per-network BlockchainSettings field - nothing about this fraction is meant to
  // differ between mainnet/testnet/stagenet, unlike e.g. RewardsSettings' emission-curve parameters.
  val ServingNodeCredPart: Fraction = Fraction(3, 10)

  // φ_b = 0.60 (hearth-tokenomics-spec S4), same hardcoding rationale as ServingNodeCredPart above.
  val BurnedWorkPart: Fraction = Fraction(6, 10)

  def apply(blockchain: Blockchain)(tx: SettleTransaction): Either[ValidationError, StateSnapshot] = {
    val sender = tx.sender.toAddress
    for {
      registered <- blockchain
        .registeredEnclave(tx.enclavePublicKey)
        .toRight(GenericError(s"${tx.enclavePublicKey} is not a registered enclave public key"))
      _ <- Either.raiseUnless(registered.operator == sender) {
        GenericError(s"$sender is not the operator of enclave ${tx.enclavePublicKey}")
      }
      // registeredEnclave above only ever resolves when Blockchain.currentGenerationPeriod is defined (see
      // Blockchain.findRegisteredEnclave), so this can't actually fail here - kept as an explicit Either for
      // clarity rather than a partial .get, not because this branch is reachable.
      period <- blockchain.currentGenerationPeriod.toRight(GenericError("DeterministicFinality is not yet activated"))
      // The signature is rebuilt from the transaction and `period`, not from the batch, so a batch signed for a
      // different network, operator or period does not verify here.
      message = SettleTransaction.mkSettlementMessage(tx.chainId, tx.enclavePublicKey, sender, period.start.toInt, tx.settlements)
      _ <- Either.raiseUnless(crypto.verify(tx.enclaveSignature, message, PublicKey(tx.enclavePublicKey))) {
        GenericError("Invalid enclave signature over settlements")
      }
      // registeredEnclave only confirms registered.validator was a committed generator of the period the enclave
      // registered *for* (StartBoostTransactionDiff's own check) - not necessarily of `period` here, since
      // findRegisteredEnclave's current-or-next-period window lets a Settle land one period earlier than that
      // registration covers. GeneratingBalanceProvider sums totalWork only over committedGenerators(period), so
      // writing workDone for a validator outside that set would let its own `work` exceed `totalWork` - breaking
      // the bound WorkBoost relies on (see its own doc comment). Checked here, at the point the period is fixed,
      // rather than trusted from an earlier, differently-scoped check.
      _ <- Either.raiseUnless(blockchain.committedGenerators(period).exists(_.address == registered.validator)) {
        GenericError(s"${registered.validator} is not a committed generator of period $period")
      }
      accumulated <- tx.settlements.foldLeft(SettleAccumulator.empty(blockchain.workDone(registered.validator, period)).asRight[ValidationError]) {
        (acc, settlement) =>
          acc.flatMap { case SettleAccumulator(settledSoFar, nodeCreditSoFar, workDoneSoFar) =>
            val key               = (settlement.client, sender, settlement.assetId)
            val reserved          = blockchain.reservedAmount(settlement.client, sender, settlement.assetId)
            val previouslySettled = settledSoFar.getOrElse(key, blockchain.settledAmount(settlement.client, sender, settlement.assetId))
            val newCumulative     = settlement.cumulativeSpent.value
            for {
              // Defence in depth, mirroring ReserveTransactionDiff.assetIssued: reservedAmount > 0 already implies
              // the asset was issued (Reserve itself checks this), but Settle shouldn't rely solely on that to
              // reject a nonexistent asset.
              _ <- assetIssued(blockchain, settlement.assetId)
              _ <- Either.raiseUnless(reserved > 0) {
                GenericError(s"${settlement.client} has no open reservation with $sender for ${settlement.assetId}")
              }
              _ <- Either.raiseUnless(newCumulative >= previouslySettled) {
                GenericError(
                  s"Settlement counter for ${settlement.client} with $sender for ${settlement.assetId} would decrease from " +
                    s"$previouslySettled to $newCumulative"
                )
              }
              _ <- Either.raiseUnless(newCumulative <= reserved) {
                GenericError(
                  s"Settlement counter $newCumulative for ${settlement.client} with $sender for ${settlement.assetId} " +
                    s"exceeds total reserved $reserved"
                )
              }
              // newCumulative >= previouslySettled is already established above, so this delta is never negative -
              // it's exactly the portion of this settlement newly confirmed since the last one seen for this key
              // (on chain, or earlier in this same batch).
              delta = newCumulative - previouslySettled
              newNodeCredit <- nodeCreditSoFar.combine(Portfolio.build(settlement.assetId, ServingNodeCredPart(delta))).leftMap(GenericError(_))
              newWorkDone <- safeSum(workDoneSoFar, BurnedWorkPart(delta), s"workDone for ${registered.validator} in $period")
                .leftMap(GenericError(_))
            } yield SettleAccumulator(settledSoFar.updated(key, newCumulative), newNodeCredit, newWorkDone)
          }
      }
      senderPortfolio <- Portfolio(balance = -tx.fee.value).combine(accumulated.nodeCredit).leftMap(GenericError(_))
      snapshot <- StateSnapshot.build(
        blockchain,
        portfolios = Map(sender -> senderPortfolio),
        settledAmounts = accumulated.settledAmounts,
        workDone = Map((registered.validator, period) -> accumulated.workDone)
      )
    } yield snapshot
  }

  private case class SettleAccumulator(
      settledAmounts: Map[(Address, Address, Asset), Long],
      nodeCredit: Portfolio,
      workDone: Long
  )

  private object SettleAccumulator {
    def empty(initialWorkDone: Long): SettleAccumulator = SettleAccumulator(Map.empty, Portfolio.empty, initialWorkDone)
  }

  private def assetIssued(blockchain: Blockchain, asset: Asset): Either[ValidationError, Unit] = asset match {
    case Hearth                 => ().asRight
    case asset @ IssuedAsset(_) => Either.cond(blockchain.assetDescription(asset).isDefined, (), GenericError(s"Asset $asset is not issued"))
  }
}
