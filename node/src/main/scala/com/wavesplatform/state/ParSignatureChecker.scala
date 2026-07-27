package com.wavesplatform.state

import cats.syntax.parallel.*
import com.wavesplatform.block.Block
import com.wavesplatform.transaction.{ProvenTransaction, Transaction}
import com.wavesplatform.utils.Schedulers
import monix.eval.Task
import monix.execution.schedulers.SchedulerService

object ParSignatureChecker {
  implicit val sigverify: SchedulerService = Schedulers.fixedPool(4, "sigverify")

  def checkTxSignatures(txs: Seq[Transaction]): Unit =
    txs
      .parUnorderedTraverse {
        case tx: ProvenTransaction =>
          Task {
            tx.firstProofIsValidSignatureAfterV6
          }.void
        case _ => Task.unit
      }
      .executeOn(sigverify)
      .runAsyncAndForget

  def checkBlockAndTxSignatures(block: Block, checkTxSignatures: Boolean): Unit = {
    val verifiedObjects: Seq[Any] = (block +: block.transactionData)
    verifiedObjects
      .parTraverse {
        case tx: ProvenTransaction if checkTxSignatures =>
          Task {
            tx.firstProofIsValidSignatureAfterV6
          }.void
        case b: Block => Task(b.signatureValid()).void
        case _        => Task.unit
      }
      .executeOn(sigverify)
      .runAsyncAndForget
  }

  def checkBlockSignature(block: Block): Unit =
    Task(block.signatureValid()).executeOn(sigverify).runAsyncAndForget
}
