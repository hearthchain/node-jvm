package tech.hearth.state.diffs

import cats.syntax.either.*
import tech.hearth.lang.ValidationError
import tech.hearth.state.*
import tech.hearth.transaction.BindApiKeyTransaction
import tech.hearth.transaction.TxValidationError.GenericError

/** BindApiKeyTransaction semantics: bind an HPKE-sealed API key envelope (encryptedApiKey, opaque to the node) to
  * a registered enclave's attestation public key. The node never decrypts encryptedApiKey - it only checks that
  * enclavePublicKey is currently registered (see Blockchain.isRegisteredEnclave) and stores the binding, upsert by
  * (enclavePublicKey, sender).
  */
object BindApiKeyTransactionDiff {
  def apply(blockchain: Blockchain)(tx: BindApiKeyTransaction): Either[ValidationError, StateSnapshot] = {
    val sender = tx.sender.toAddress
    for {
      _ <- Either.raiseUnless(blockchain.isRegisteredEnclave(tx.enclavePublicKey)) {
        GenericError(s"${tx.enclavePublicKey} is not a registered enclave public key")
      }
      snapshot <- StateSnapshot.build(
        blockchain,
        portfolios = Map(sender -> Portfolio(balance = -tx.fee.value)),
        apiKeyBindings = Map((tx.enclavePublicKey, sender) -> tx.encryptedApiKey)
      )
    } yield snapshot
  }
}
