package tech.hearth.state

import tech.hearth.account.Address
import tech.hearth.common.state.ByteStr

/** A TEE miner's enclave, registered via StartBoostTransaction for one generation period - the DCAP analogue of
  * [[GenerationCommitment]]/[[CommittedGenerator]], keyed by the enclave's own ephemeral public key (raw Ed25519,
  * 32 bytes, from the quote's report_data[32:64]) rather than an address: an enclave generates a fresh keypair on
  * every restart, so the key that changes with the enclave's lifecycle is exactly the key this registry should be
  * keyed by, and it is the only key the enclave can later sign with or receive encrypted payloads through (the
  * quote's DCAP attestation key belongs to the platform's Quoting Enclave and is shared by every TD on the host,
  * so it identifies the machine, not this workload). `validator` is the already-committed consensus generator this
  * TEE miner boosts, carried alongside the enclave key rather than derived from it - the two are unrelated
  * identities. `operator` is the address that sent the registering transaction, i.e. the TEE miner operating this
  * enclave; registration is first-wins per enclave key and period, so a hijacked registration is escaped by
  * restarting the enclave (fresh key) rather than contested. ReserveTransaction/BindApiKeyTransaction check this
  * field against `Blockchain.registeredEnclaves` (current or next period) to decide whether an address is a
  * "registered miner".
  */
case class RegisteredEnclave(enclavePublicKey: ByteStr, validator: Address, operator: Address)
