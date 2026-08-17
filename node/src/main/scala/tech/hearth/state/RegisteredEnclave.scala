package tech.hearth.state

import tech.hearth.account.Address
import tech.hearth.common.state.ByteStr

/** A TEE miner's enclave, registered via StartBoostTransaction for one generation period - the DCAP analogue of
  * [[GenerationCommitment]]/[[CommittedGenerator]], keyed by the quote's own attestation public key (raw P-256
  * point, 64 bytes) rather than an address: unlike a generator's identity, an enclave's identity changes on every
  * restart (a fresh quote embeds a fresh attestation key), so the key that changes with the quote is exactly the
  * key this registry should be keyed by. `validator` is the already-committed consensus generator this TEE miner
  * boosts, carried alongside the enclave key rather than derived from it - the two are unrelated identities.
  */
case class RegisteredEnclave(attestationPublicKey: ByteStr, validator: Address)
