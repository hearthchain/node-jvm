package com.wavesplatform.state.diffs

import cats.syntax.either.*
import com.wavesplatform.consensus.GeneratingBalanceProvider
import com.wavesplatform.crypto
import com.wavesplatform.lang.ValidationError
import com.wavesplatform.state.*
import com.wavesplatform.transaction.CommitToGenerationTransaction
import com.wavesplatform.transaction.TxValidationError.{ActivationError, GenericError}

object CommitToGenerationTransactionDiff {
  def apply(blockchain: Blockchain)(tx: CommitToGenerationTransaction): Either[ValidationError, StateSnapshot] = {
    val sender = tx.sender.toAddress

    for {
      current <- blockchain.currentGenerationPeriod.toRight(ActivationError("DeterministicFinality is not yet activated"))
      next = current.next
      _ <- Either.raiseUnless(tx.generationPeriodStart == next.start) {
        GenericError(s"Expected the next period start height ${next.start}, got ${tx.generationPeriodStart}")
      }
      _ <- tx.endorserPublicKey.validated.leftMap(e => GenericError(s"Invalid endorser public key: $e"))
      _ <- tx.commitmentSignature
        .verifyBasic(tx.popMessage, tx.endorserPublicKey)
        .leftMap(e => GenericError(s"Invalid commitment signature: $e"))
      // Proves the sender holds the VRF key it registers, so that a mistyped key fails here rather than silently
      // leaving a generator unable to produce a verifiable block for the whole period
      _ <- crypto
        .verifyVRF(tx.vrfCommitmentSignature, tx.vrfPopMessage, tx.vrfPublicKey)
        .leftMap(e => GenericError(s"Invalid VRF commitment signature: $e"))
      _ <- blockchain.committedGenerators(next).foldLeft(Either.unit[GenericError]) {
        case (r @ Left(_), _) => r
        case (Right(_), cg) =>
          if (cg.address == sender) GenericError(s"$sender is already committed").asLeft
          else if (cg.endorserPublicKey == tx.endorserPublicKey)
            GenericError(s"BLS key ${tx.endorserPublicKey} is already committed, try another key").asLeft
          else if (cg.vrfPublicKey == tx.vrfPublicKey)
            GenericError(s"VRF key ${tx.vrfPublicKey} is already committed, try another key").asLeft
          else ().asRight
      }
      snapshot <- StateSnapshot.build(
        blockchain,
        portfolios = Map(
          sender -> Portfolio(
            balance = -tx.fee.value
            // generationDeposit = ??? // We don't need this, because calculate from nextCommittedGenerators
          )
        ),
        nextCommittedGenerators = Seq(GenerationCommitment(tx.sender, tx.endorserPublicKey, tx.vrfPublicKey))
      )
      generatingBalanceAfterDeposit = SnapshotBlockchain(blockchain, snapshot).generatingBalance(sender)
      minBalance                    = GeneratingBalanceProvider.minMiningBalance(blockchain, Height(blockchain.height))
      _ <- Either.raiseWhen(generatingBalanceAfterDeposit < minBalance) {
        GenericError(s"Generating balance $generatingBalanceAfterDeposit is less than $minBalance required for block generation")
      }
    } yield snapshot
  }
}
