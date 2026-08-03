package tech.hearth.transaction

import tech.hearth.common.state.ByteStr
import tech.hearth.crypto
import tech.hearth.transaction.TxValidationError.GenericError
import monix.eval.Coeval

trait Proven extends Authorized {
  def proofs: Proofs
  val bodyBytes: Coeval[Array[Byte]]

  protected def verifyFirstProof(): Either[GenericError, Unit] =
    if (proofs.size != 1) Left(GenericError("Transactions from non-scripted accounts must have exactly 1 proof"))
    else
      Either.cond(
        crypto.verify(proofs.proofs.head, bodyBytes(), sender),
        (),
        GenericError(s"Proof doesn't validate as signature for $this")
      )

  lazy val firstProofIsValidSignatureAfterV6: Either[GenericError, Unit] = verifyFirstProof()
}

object Proven {
  implicit class ProvenExt(private val p: Proven) extends AnyVal {
    def signature: ByteStr = p.proofs.toSignature
  }
}
