---
purpose: Implementation notes for hearth transactions (DCAP collateral registry, StartBoost, Reserve, BindApiKey, Settle, workBoost)
---

# Hearth transaction lifecycle: DCAP, StartBoost, Reserve, BindApiKey, Settle, workBoost

## DCAP collateral registry

The first real semantics landed for one of the five stub transaction types from "Transaction schema" in `docs/notes/keys-and-signatures.md`: `UpdateCollateralTransaction`, a
permissionless on-chain registry for Intel DCAP (TDX/SGX) remote-attestation collateral, is the prerequisite state
`StartBoostTransaction` will eventually verify a TEE miner's quote against. `StartBoostTransaction` itself (quote
parsing, per-period enclave registration) is still unimplemented; only the collateral registry underneath it exists
so far. Terminology: a *generator* is a consensus miner (committed via `CommitToGenerationTransaction`, unrelated to
this); a *TEE miner*/*boosted miner* is a separate role that runs AI inference inside an attested enclave and proves
that with a DCAP quote, chained through `StartBoost`. Do not conflate the two.

DCAP quote verification needs a chain of Intel-signed collateral, most of it expiring and requiring periodic
permissionless updates: a Root CA CRL, a PCK (Provisioning Certification Key) CRL plus the PCK CA's own issuer
chain, and signed-JSON TCB Info (per platform model, keyed by FMSPC) plus QE/Enclave Identity, both chained through
a TCB Signing CA issuer chain. `UpdateCollateralTransaction` (`transaction/UpdateCollateralTransaction.scala`)
carries all six as independent `Option[ByteStr]` fields rather than a oneof, so one transaction can update several
at once; `UpdateCollateralTxValidator` only requires at least one field be set. `state/diffs/
UpdateCollateralTransactionDiff.scala` runs whichever fields are present through `state/DcapCollateral.scala` and
merges the result into a `StateSnapshot`.

### Verification

`crypto/dcap/IntelPki.scala` holds the only cryptographic trust decisions in this path: a pinned `rootCaPublicKey`
constant (matching dcap-rs's `INTEL_ROOT_CA_PEM`), `verifyIssuerChain` (reuses the existing `P256Curve` cert-chain
validator, checked against the pinned root, mandatory revocation checking against an already-on-chain CRL),
`verifyCrl`/`crlNumberOf` (CRL Number via the X.509 `2.5.29.20` extension), and `verifyRawSignature` (raw r||s to
DER conversion, since Intel's JSON collateral signs with raw 64-byte ECDSA signatures while X.509/CRL use DER).

TCB Info and QE/Enclave Identity are signed JSON, not X.509: `{"tcbInfo": {...}, "signature": <raw r||s, hex>}`
(and `enclaveIdentity` for the other). The signature covers the exact raw bytes of the nested object as served, not
a reserialized copy, so `crypto/dcap/JsonRawValue.scala` extracts that byte span with a byte-level brace-matching
scanner (string-literal/escape aware) rather than parsing and re-emitting JSON, which would silently break the
signature on any whitespace or key-order difference from Intel's own serialization.

`DcapCollateral.scala` rejects a stale resubmission for every field: Root/PCK CRL by CRL Number, TCB Info/QE
Identity by Intel's own `tcbEvaluationDataNumber` (incremented on every republish, including a pure TCB-recovery
event with no other visible change). `rejectDowngrade` only compares when both a new and a stored number exist, so
the very first submission for a field is never rejected as a "downgrade". TCB Info is stored per-FMSPC (read out of
the payload itself, not a separate transaction field), so freshness is compared against whatever is already stored
for that specific platform model, not any other one.

### Storage and genesis

The six fields use the same history-keyed RocksDB pattern as `AssetsInfo` (`KeyTag` history/value key pairs,
`updateHistory`/`filterHistory` for rollback, new tags appended at the end of the append-only enum, since the
ordinal is the on-disk prefix), not the period-keyed pattern `committedGenerators` uses, since collateral isn't
tied to a generation period. `Blockchain` gained six
accessor methods (`dcapRootCaCrl`, `dcapPckCrl`, `dcapTcbInfo(fmspc)`, `dcapQeIdentity`,
`dcapTcbSigningIssuerChain`, `dcapPckCaIssuerChain`), implemented in every `Blockchain` (`RocksDBWriter`,
`SnapshotBlockchain`, `BlockchainUpdaterImpl`, `EmptyBlockchain`, testkit's `ForwardingBlockchainUpdaterImpl`).

Everything required to run the chain, including the Root CA CRL that every issuer-chain verification depends on
for revocation checking, has to be seedable at genesis rather than relying on the first few blocks to bootstrap it
in the right order (see "Predefined snapshots" in `docs/notes/state-and-blocks.md`): `PredefinedSnapshotSettings` gained the same six optional/repeated
fields, and `PredefinedSnapshot.build` overlays a `SnapshotBlockchain` per field (`rootCaCrlBlockchain`,
`tcbSigningBlockchain`) so that, within one genesis entry, a field can resolve another field also being seeded in
that same entry (e.g. a genesis-seeded PCK CRL resolving its issuer key against a PCK CA issuer chain seeded in
the same entry).

### The genesis-timestamp bug

`PredefinedSnapshot.build` needs a real wall-clock time to check collateral validity windows (`issueDate`/
`nextUpdate`, cert `notBefore`/`notAfter`) against. It used to default that `atTime` to epoch-0 wherever a caller
didn't have an obvious timestamp to pass, and every collateral-bearing genesis silently hit exactly that default,
since `Block.genesis` used to call it before computing the block's own timestamp. This surfaced only when
genesis-seeded collateral actually exercised an issuer-chain check, as `validity check failed: NotBefore: ...
2018 ...` from decades in the future relative to epoch-0. Fixed by threading a real `blockTimestamp: Option[Long]`
through every caller: `Block.genesis` now computes `timestamp` first and passes `Some(timestamp)`;
`BlockDiffer.mkInitialSnapshot`, the production block-application path (`Domain.appendBlock`/
`BlockchainUpdaterImpl.processBlock`, used for every predefined snapshot at any height, not just genesis) passes
`Some(block.header.timestamp)`; `WithState.blockWithComputedStateHash` (testkit) passes the same for its
genesis-height branch. All three call sites had to be found and fixed together, by grepping every
`PredefinedSnapshot.build` call site, not just the one a specific failing test pointed at, since only one of the
three happened to be exercised by any single failing test at a time.

### Testing

`node/tests/src/test/resources/dcap/` vendors real Intel-signed artifacts (Root CA cert/CRL, TCB Signing CA cert,
a genuine TCB Info V3 JSON document, MIT-licensed from `automata-network/automata-dcap-attestation`, see `SOURCE.md`
there) rather than only synthetic ones, matching this repo's "Cross-language test vectors" convention (see `docs/notes/economics.md`) of preferring
real, independently-produced fixtures over ones the test itself constructs. `IntelPkiTest`'s "against real
Intel-signed fixtures" group exercises the actual accept path against production code's pinned `rootCaPublicKey`;
every reject path (wrong trust anchor, non-self-issued claimed root, wrong CRL signer, garbage bytes) is covered by
synthetic fixtures built in-test with BouncyCastle (`bcpkix-jdk18on`, test-scope only), since a handful of fixed
real artifacts can't exercise every rejection path on their own. `UpdateCollateralTransactionDiffTest` covers all
six fields end-to-end: genesis seeding, permissionless update, idempotent resubmit, rollback, and the reject paths
above.

## StartBoost: TDX quote verification and enclave registration

`StartBoostTransaction` (`sender`, `validator: Address`, `tdxQuote: ByteStr`, `generationPeriodStart: Height`) is
the second stub transaction type from "Transaction schema" in `docs/notes/keys-and-signatures.md` to grow real semantics: a permissionless proof
that a TEE miner's enclave is genuine, registering it for one generation period against the DCAP collateral
registry (see "DCAP collateral registry" above). `validator` names the already-committed consensus generator
(`CommitToGenerationTransaction`) this TEE miner boosts; the two identities are unrelated (a generator mines
blocks, a TEE miner runs attested AI inference, see "DCAP collateral registry" above for the terminology split)
and StartBoost only records that pairing, it doesn't grant the validator anything by itself.

`state/diffs/StartBoostTransactionDiff.scala` does the real work; `StartBoostTxValidator` only pre-filters (parses
`tdxQuote` and rejects SGX outright, structurally, before touching chain state - a malformed or SGX quote gets
rejected before it can occupy mempool or block space, matching the "reject SGX" policy decision made when this was
planned). The diff:

- requires `generationPeriodStart` to be exactly the *next* generation period from the current one, and `validator`
  to already be a committed generator of that next period - a StartBoost can't register ahead of or behind the
  generator commitment it rides on;
- checks quote freshness and extracts the enclave key: the TDX report's 64-byte `report_data` is `blockId(32) ++
  enclavePublicKey(32)` - `report_data[32:64]` carries the enclave's own ephemeral Ed25519 key (the only key
  generated inside the TEE, and so the only one worth registering), and `blockId` (`report_data[0:32]`) must name a
  block within the last `FreshnessWindowBlocks` (100) blocks below the current height. Both values are embedded raw,
  not hashed - unforgeability comes from the quote's own TDX hardware signature over `report_data`, not from hiding
  either value. The transaction sender is deliberately *not* bound into the quote (see #32): the sender becomes the enclave's
  `operator` on a first-registration-wins basis (see below), so a hijacked registration is escaped by restarting the
  enclave (which mints a fresh key) rather than contested. An all-zero enclave key, or one already carrying an entry in
  `registeredEnclaves(next)`, is rejected outright, before signature verification runs;
- verifies the full quote signature chain: the quote's own embedded PCK certificate chain (leaf, PCK CA, root) is
  checked against `IntelPki`'s pinned Intel root and the on-chain `pckCrl` (revocation) - it does *not* need the
  on-chain `pckCaIssuerChain` too, since the chain a quote itself carries is already self-contained; that field
  only backs verifying `pckCrl`'s own signature, at `UpdateCollateral` submission time. The PCK leaf's key then
  verifies `qeReportSignature`; the QE report's own `user_report_data` (`SHA256(attestationPubKey ++ authData)`,
  zero-padded to 64 bytes, per Intel's spec) is checked to prove the QE vouches for that specific attestation key;
  that attestation key finally verifies `isvSignature` over the quote's header+body. `DcapQuote.Quote` carries
  `isvSignedMessage`/`QuoteSignatureData.qeReportBodyMessage` as the original wire-byte slices these two signatures
  cover, not re-serialized from the parsed fields, so a signature check can never diverge from what was actually
  signed due to a re-encoding bug in the parser;
- checks the quote's FMSPC (read from the PCK leaf's SGX certificate extension, OID `1.2.840.113741.1.13.1` nesting
  `1.2.840.113741.1.13.1.4`, parsed the same ASN.1-`OCTET STRING`-wrapping-a-`SEQUENCE` way `IntelPki`'s existing
  CRL Number extraction does) has a TCB Info entry on chain - existence only, not full TCB status evaluation (see
  the deferred-scope note below);
- registers the enclave: `RegisteredEnclave(enclavePublicKey, validator, operator)`, keyed by the enclave's own
  ephemeral public key (raw Ed25519, 32 bytes, from `report_data[32:64]` above) rather than by address or by
  `mrEnclave` - unlike a generator's identity, an enclave's identity changes on every restart (a fresh boot mints a
  fresh keypair), so the registry key is exactly the value that changes with the enclave's lifecycle, and it is the
  only key the enclave can later sign with or receive encrypted payloads through. This is deliberately *not* the
  quote's own DCAP attestation key (a P-256 point belonging to the platform's Quoting Enclave, shared by every TD on
  the host - it identifies the machine, not this workload); an earlier revision of this transaction keyed the
  registry by that attestation key instead, fixed before it shipped (see "Reserve and BindApiKey" below for the
  on-disk consequence). `operator` (the StartBoost sender, i.e. the TEE miner operating this enclave - added
  alongside `Reserve`/`BindApiKey`, see below) is what lets `Blockchain.isRegisteredMiner`/`isRegisteredEnclave`
  answer "is this address a registered miner" / "is this enclave key a registered enclave" without an extra lookup
  layer.

Storage mirrors `committedGenerators`/`GenerationPeriod` exactly, including the same "only this and next period are
cached" restriction, the same rollback-on-discard handling, and the same `Caches`/`RocksDBWriter`/`Blockchain`
three-layer wiring - `RegisteredEnclave` is the DCAP analogue of `CommittedGenerator`.

**Deliberately deferred, both flagged in `StartBoostTransactionDiff`'s own doc comment rather than silently
skipped:** the on-chain TCB Info for a quote's FMSPC is checked for *presence* only, not for its TCB status
(UpToDate/OutOfDate/Revoked, Intel DCAP spec S6.2.2 - a real evaluation needs matching `sgxtcbcomponents`/`pcesvn`
against a sorted `tcbLevels` list and picking the first match, a nontrivial algorithm on its own) or re-checked for
freshness at StartBoost time (only at the `UpdateCollateral` submission that put it on chain) - a TEE running under
a since-downgraded TCB is not yet rejected. Neither has a settled design yet; don't guess at either without
checking whether one has landed since.

**Testing gap, also flagged rather than silently accepted:** no real, currently-valid PCK CRL fixture exists to vendor - Intel's DCAP PKI has no static "PCK CRL for this specific PCK CA" sample the way a Root CA CRL or TCB Signing CA chain does, and even upstream `dcap-rs`'s own test suite fetches this collateral live from Intel PCS at test time rather than vendoring it, which this repo's tests never do (see "Cross-language test vectors"/real-fixture convention in `docs/notes/economics.md`). `StartBoostTransactionDiffTest`'s deepest deterministically-reachable reject path is therefore "PCK CRL not set" - the accept path, and the PCK-chain/QE/ISV signature verification beyond it, are exercised only indirectly: `IntelPkiTest`'s synthetic-fixture groups cover `verifyIssuerChain`/`verifyCrl` in isolation with an injectable trust anchor, and `DcapQuoteTest` confirms `isvSignedMessage`/`qeReportBodyMessage` slice the exact expected byte ranges against real quote fixtures - but nothing currently exercises `StartBoostTransactionDiff`'s full signature-chain wiring end to end against a quote that actually verifies. A future pass wanting to close this would need either a live Intel PCS fetch (a new kind of test dependency this repo doesn't have) or threading an injectable trust anchor through `StartBoostTransactionDiff` itself (mirroring `IntelPki.verifyIssuerChain`'s own `trustAnchor` parameter) so a synthetic PCK chain built in-test with BouncyCastle could stand in for Intel's real one. The accept path has since been exercised end to end, outside the unit tests, against a real Intel-signed quote and live PCS collateral on the docker/private image (see "Live StartBoost on docker/private" in `docs/notes/docker-private.md`); the unit-test gap itself is unchanged.

### REST: signing and broadcasting

`StartBoostTransaction`/`UpdateCollateralTransaction`/`ReserveTransaction`/`BindApiKeyTransaction`/
`SettleTransaction` are the five stub types from "Transaction schema" in `docs/notes/keys-and-signatures.md` that are actually signable through
`/transactions/sign`/`/transactions/broadcast` now, via `StartBoostRequest`/`UpdateCollateralRequest`/
`ReserveRequest`/`BindApiKeyRequest`/`SettleRequest` (`api/http/requests/`) wired into
`TransactionFactory.parseRequest` - only `Withdraw` still falls through to `UnsupportedTransactionType`, unchanged.

Both `StartBoostRequest`/`UpdateCollateralRequest` hit the same real bug once tested against realistically-sized
payloads (a fixture-derived TCB Info blob or an actual TDX quote, not a 3-byte placeholder): `ByteStr`'s default
REST `Format` decodes at most 280 base16 characters (`Base16.defaultDecodeLimit`, via `Base16.tryDecodeWithLimit`'s
default `limit`), sized for a signature or public key, but a `tdxQuote` is ~4935 raw bytes (~9870 hex characters)
and a `tcbInfo`/PEM-chain collateral field can run to several KB - both silently exceed it and fail with `Can't
parse '...' as base16 encoded byte array` at request-parse time, before validation ever runs.
`api/http/requests/package.scala`'s `largeByteStrFormat` (`LargeBlobDecodeLimit = 65536` characters) exists for
exactly these fields; `StartBoostRequest`/`UpdateCollateralRequest` each shadow the package's ambient
`given Format[ByteStr]` with it, locally, in their own companion object, before their `Json.format` macro call -
every other request type keeps the small default unchanged. `BindApiKeyRequest.encryptedApiKey` shadows it the
same way pre-emptively, even though a real HPKE-sealed envelope is well under 280 hex chars - see "Reserve and
BindApiKey" below. A future request class with its own large-blob field should reuse `largeByteStrFormat` the same
way rather than growing the shared default limit, which stays deliberately small for the common case.

## Reserve and BindApiKey: locking funds and binding enclave-sealed API keys

The third and fourth stub transaction types from "Transaction schema" in `docs/notes/keys-and-signatures.md` to grow real semantics, both reading
the `RegisteredEnclave` registry `StartBoostTransactionDiff` writes (see above) rather than adding a new registry
of their own. `miner` (`ReserveTransaction`'s own field, naming the reservation's target) and `RegisteredEnclave`'s
`operator` field are the same identity under different names - both are the *TEE miner* role from "DCAP collateral
registry"'s terminology split (the `StartBoostTransaction` sender), never the `validator`/consensus generator it
boosts - conflating the two here would silently let `Reserve`/`BindApiKey` target the wrong identity.

**"Registered miner"/"registered enclave" checks are period-scoped, not permanent.** `RegisteredEnclave` only
covers the current and next generation period (StartBoost registers for the *next* one, same as
`CommitToGenerationTransaction`), so `Blockchain.isRegisteredMiner(address)`/`isRegisteredEnclave(enclavePublicKey)`
(`state/Blockchain.scala`'s `BlockchainExt`) check membership in `registeredEnclaves(current) ++
registeredEnclaves(next)`, not an unbounded history. A miner that stops re-attesting every period stops being a
valid `Reserve` target or `BindApiKey` registry match, by design - a deliberate choice over a permanent
once-registered identity, made to stay consistent with every other period-scoped check in this codebase
(`committedGenerators`, `isConflict`) rather than introduce a second, unbounded registry alongside the period-scoped
one.

**`ReserveTransaction`** (`sender, assetId, amount, miner, feeAssetId, fee, timestamp, proofs, chainId`) locks
`amount` of `assetId` from the sender's balance against a registered miner. `state/diffs/
ReserveTransactionDiff.scala` checks `assetId` is `Hearth` or an already-issued `IssuedAsset` (`feeAssetId`'s
existence is already checked upstream by `TransactionDiffer.feePortfolios`, driven by `TxWithFee.InCustomAsset`, so
the diff doesn't re-check it) and that `miner` is registered, then debits `amount` (from `assetId`) and `fee` (from
`feeAssetId`) from the sender's portfolio and accumulates the total into a new `(sender, miner, asset) -> Long`
ledger, `Blockchain.reservedAmount`. Multiple `Reserve` transactions to the same triple keep adding to the same
total - **accumulate-only, by design**: `reservedAmount` is never decremented, it is only ever compared against
(see "Settle: retiring reserved funds" below - `SettleTransaction` reads it as a ceiling, `WithdrawTransaction` is
still an unimplemented stub). The debited amount is credited nowhere else - not to the miner, not to any pool -
only recorded in `reservedAmounts`; until `Withdraw` exists, whatever a client reserves and never spends (i.e.
never covered by a `Settle`) is **unspendable and unrecoverable**. This is a real, currently-open fund-safety gap,
not an oversight: it is not yet safe to expose `Reserve` on a network carrying real value.

**`BindApiKeyTransaction`** (`sender, enclavePublicKey, encryptedApiKey, fee, timestamp, proofs, chainId`) binds an
HPKE-sealed API key envelope to a registered enclave's public key. `state/diffs/
BindApiKeyTransactionDiff.scala` checks `enclavePublicKey` is registered, then upserts `encryptedApiKey` into a new
`(enclavePublicKey, sender) -> ByteStr` store, `Blockchain.apiKeyBinding` - keyed enclave-first so a future read
API could enumerate bindings addressed to one enclave (no such read API exists yet; this pass only implemented the
write path). The node never decrypts `encryptedApiKey` or touches HPKE/curve semantics at all - it is opaque bytes
to the node, capped at `IntelPki.MaxCollateralFieldSize` by `BindApiKeyTxValidator` purely as a resource-exhaustion
bound, reused from DCAP collateral rather than a dedicated constant since a real HPKE envelope is far smaller.
`BindApiKeyTxValidator` also requires `enclavePublicKey` be exactly 32 bytes (a registered enclave key's own
length, raw Ed25519 - a different length can never match one, so it's rejected structurally rather than paying for
the registry lookup). Note the Ed25519/X25519 mismatch this still implies: `RegisteredEnclave.enclavePublicKey` is
the enclave's Ed25519 identity key (see "StartBoost" above), while HPKE sealing in this ecosystem (see
`../hearth-chain/java`'s `ApiKeyEnvelope`, `Hpke`) needs an X25519 point - `BindApiKeyTransaction.enclavePublicKey`
is checked against the registry purely as a registry-membership proof, not because the node believes it's already
an HPKE recipient key. Unlike the P-256 attestation key this replaced, an Ed25519 key *can* be converted to the
matching X25519 point by a standard birational map (`SigningKey.toX25519()` in `../hearth-chain/java`, the
libsodium `crypto_sign_ed25519_{pk,sk}_to_curve25519` construction) - the enclave side needs the seed (which only
it holds) to derive the X25519 secret key, but the client/sealing side only ever sees the raw public key (from the
quote or from this registry) and needs the same conversion applied to just the public half; as of this writing
`tech.hearth.crypto` only exposes the seed-holding path (`SigningKey.toX25519()`), not a public-key-only one, which
is a real gap for that sealing flow, not something this transaction type resolves - how the sealing key relates to
the registered key stays an off-chain client/enclave concern either way.

**Storage** follows the DCAP-collateral history-keyed pattern (`KeyTag`/`Keys` history+value key pairs,
`updateHistory`/`filterHistory` for rollback, new tags appended at the end), not the period-keyed
`committedGenerators`/`registeredEnclaves` one, since neither ledger is tied to a generation period: `Keys.
reservedAmount`/`apiKeyBinding` key by a `ByteStr` suffix (`Keys.reservedAmountSuffix`/`apiKeyBindingSuffix`, since
the composite key - two addresses and an asset, or a public key and an address - doesn't fit the single-`ByteStr`-
suffix shape `dcapTcbInfo`'s per-FMSPC keying uses directly), with a `...KeysAtHeight` index (mirroring
`dcapTcbInfoFmspcsAt`) recording which suffixes changed at a height, for rollback. Both new `StateSnapshot` fields
carry the Diff-computed final value, not a delta (matching `assetVolumes`/DCAP fields, not `balances`' fuller
safeSum-inside-`StateSnapshot.build` machinery) - correct across multiple same-key `Reserve` transactions within
one block since each Diff reads the running total through `SnapshotBlockchain` over prior transactions' snapshots
in order. Both are folded into `TxStateSnapshotHashBuilder` (new consensus state needs hashing, the same class of
omission the DCAP fields' own PR had to fix after the fact per "DCAP collateral registry" above).

**`RegisteredEnclave`'s on-disk record layout changed twice, independently, with no version bump or migration
either time.** It started as `attestationPublicKey(64) ++ validator(32)`, 96 bytes. This pass added the operator
field, `... ++ miner(32)`, 128 bytes. Separately, a later fix (upstream PR #32, "register the enclave key, not the
DCAP attestation key" - rebased in on top of this pass) replaced the 64-byte P-256 attestation key with the
32-byte Ed25519 enclave key described under "StartBoost" above, and renamed the third field to `operator`; combined
with this pass's field, the record is now `enclavePublicKey(32) ++ validator(32) ++ operator(32)`, 96 bytes -
coincidentally the same total size as the original 2-field layout, but a different composition, so a byte-count
check alone can't tell old data from new. `readRegisteredEnclaves`/`writeRegisteredEnclaves`
(`database/package.scala`) simply changed their `.grouped(...)` width both times. Confirmed safe only because
nothing has persisted any prior layout outside disposable dev/test runs (the whole project is pre-launch, see
"HRTH emission curve"'s premine `TODO: replace before launch` notes in `docs/notes/economics.md`) - a node with real old-format
`RegisteredEnclaves` data on disk would misparse it. If a longer-lived devnet/testnet ever accumulates real
`StartBoost` history before a change like this, it needs an explicit migration or a length-based reader fallback,
not a silent width change.

**Testing constraint, same one `StartBoostTransactionDiffTest` already documents:** no test fixture in this repo
can drive a `StartBoostTransaction` to its accept path (needs a real, currently-valid Intel-signed PCK certificate
chain - see "Testing" under "DCAP collateral registry"), so neither `Reserve`'s nor `BindApiKey`'s (nor `Settle`'s,
below) "registered" accept path can be reached through a real `StartBoostTransaction` in a test.
`ReserveTransactionDiffTest`/`BindApiKeyTransactionDiffTest`/`SettleTransactionDiffTest` work around this by
calling the Diff object directly against a `Blockchain` value that overrides `registeredEnclaves` to inject a
`RegisteredEnclave` entry (Scala 3 `export blockchain.{registeredEnclaves as _, *}` plus one `override def`),
rather than going through `d.appendBlockE` - the reject paths that don't need a registered miner/enclave (unknown
asset, unregistered miner/enclave) go through the real domain normally. `TransactionFactorySpec` covers all three
new REST request types, including a `BindApiKeyRequest` with an oversized `encryptedApiKey` to exercise
`largeByteStrFormat`.

## Settle: retiring reserved funds

The gist "settle" analysis (a miner-authored spec fragment, not part of `hearth-tokenomics-spec` - see
https://gist.github.com/swell-a2a/c1c8571f465010403b3ec8c13bf47928) is the only source for this transaction's
semantics: "At any moment of its choosing the node submits an enclave-signed batch of `(client, cumulative
spent)`", validated against three rules ("reservation open", "counter non-decreasing", "counter within the total
ever reserved") and, on acceptance, "Settled Cred is retired" while "value settled in epoch E feeds the
beneficiary validator's workBoost in E+1".

**`SettleTransaction`** (`sender, enclavePublicKey, settlements, enclaveSignature, fee, timestamp, proofs,
chainId`) is submitted by a miner (`sender`, the same *TEE miner*/operator identity as `ReserveTransaction.miner`
and `RegisteredEnclave.operator` - see "Reserve and BindApiKey" above) and carries a batch of
`Settlement(client, assetId, cumulativeSpent)` entries, protobuf `SettleTransactionData.Settlement{client:
Recipient, cumulative_spent: Amount}` (asset piggybacks on the existing `Amount{asset_id, amount}` message rather
than a bare `int64`, matching `ReserveTransactionData.amount`'s shape). `enclavePublicKey`/`enclaveSignature` are
the raw 32-byte Ed25519 key and 64-byte signature of the *enclave itself* attesting to the batch - a second,
enclave-level signature alongside the transaction's own `proofs` (signed by `sender`'s account key), the same
two-signer split `CommitToGeneration`'s VRF/BLS proof-of-possession fields use. The signed message
(`SettleTransaction.mkSettlementMessage`) is each settlement's `client.toBytes`(20, `tech.hearth.crypto.Address
.HASH_LEN`) `++` `assetId`(32, zero-padded for `Hearth`) `++` `cumulativeSpent`(8, big-endian), concatenated in
order - fixed-width fields throughout specifically so concatenating several settlements has no parsing-boundary
ambiguity, but only because `SettleTxValidator` separately rejects any `IssuedAsset` id whose length isn't exactly
32 bytes. That check is load-bearing, not cosmetic: the wire format itself (`PBAmounts.toVanillaAssetId`) accepts
an `assetId` of *any* length with no validation, so without it a malicious `sender` (the operator relaying the
batch - exactly the party the enclave signature exists to constrain) could submit a settlements list whose
concatenated bytes collide with a genuinely enclave-signed message for a *different* settlements list, settling
funds the enclave never actually authorized. Caught in review before merge (security audit, Pass 2) - the
comment on `mkSettlementMessage` explains the dependency so a future field addition doesn't silently reopen it.
This wire shape was cross-checked against `../hearth-rs`'s independently-written stub
(`crates/transaction/src/simple.rs`'s `SettleTransaction { enclave_public_key, settlements: Vec<(Recipient, Asset,
i64)>, enclave_signature }`) and against `hearthchain/miner` (the actual TEE execution node repo, gateway +
Go enclave identity service) - the latter has no settlement/spend-tracking code at all yet, only quote generation
(`GET /v1/quote`) and TLS endorsement (`GET /v1/tls`), so this is the first concrete definition of the settlement
wire format, not a match against existing miner-side code.

`state/diffs/SettleTransactionDiff.scala` implements the gist's three rules per settlement, reading and writing
two triple-keyed `Long` ledgers - `Blockchain.reservedAmount` (existing, read-only here) and the new
`Blockchain.settledAmount(client, miner, asset)` (same DCAP-collateral-style history mechanism as
`reservedAmount`, `Keys.settledAmountSuffix`/`settledAmountHistory`/`settledAmountKeysAt`, `KeyTag.SettledAmount*`
appended at the end). `settledAmount` is a second, independent lifetime-cumulative counter - `Settle` never
decrements `reservedAmount` itself, only ever compares against it. This is load-bearing, not an arbitrary choice:
rule 3 is "counter within the total **ever** reserved," so the ceiling it checks against has to stay a lifetime
total across the reservation's whole history, not shrink as settlements land. The enclave's own `cumulativeSpent`
counter is likewise a lifetime total (that's what makes resubmitting an old batch a safe no-op rather than a
double-spend) - decrementing `reservedAmount` by each settlement's delta would turn it into a *remaining balance*,
and a later, legitimately-higher `cumulativeSpent` could then fail against that shrunk ceiling even though it's
still within the true lifetime total. Keeping the two ledgers separate is also what a future `Withdraw` needs
regardless: a client's recoverable balance is `reservedAmount - settledAmount`, which only means anything if both
are tracked independently.

- looks up `blockchain.registeredEnclave(tx.enclavePublicKey)` (a new `Blockchain.registeredEnclave` extension,
  same current-or-next-period scoping as `isRegisteredEnclave`/`isRegisteredMiner`, but returning the full record
  instead of a boolean so its `operator` field can be checked) and requires `tx.sender == registered.operator` -
  "reservation open" and the other two rules are otherwise keyed by whatever `sender` claims as the miner, so this
  stops an unrelated account from settling against someone else's reservations even though it could never forge
  the enclave signature itself;
- verifies `tx.enclaveSignature` against `mkSettlementMessage(tx.settlements)` using `crypto.verify` with the
  enclave's raw public key (`PublicKey(tx.enclavePublicKey)`) - this is what "enclave-signed batch" means in
  practice: the account-level `proofs` only prove the operator relayed the batch, not that the enclave produced it;
- per settlement: rejects an asset that isn't `Hearth` and isn't an already-issued `IssuedAsset` (mirroring
  `ReserveTransactionDiff.assetIssued`, defence in depth - `reservedAmount(...) > 0` already implies this in
  practice, since `Reserve` itself checks it, but `Settle` shouldn't depend solely on that); rejects
  `reservedAmount(client, miner, asset) <= 0` ("reservation open" - also blocks writing a `settledAmount` entry
  for a client that never reserved anything at all, which the other two rules alone wouldn't); rejects a new
  cumulative value lower than what's already recorded ("counter non-decreasing" - checked against a running total
  accumulated *within the same batch* too, via a `foldLeft` over `tx.settlements`, in case a client appears more
  than once in one transaction); rejects a new cumulative value exceeding `reservedAmount` ("counter within the
  total ever reserved"); credits `ServingNodeCredPart` of the newly-confirmed delta (see below) to the miner,
  accumulated across every settlement in the batch into one `Portfolio` before being combined with the fee debit.

**"Settled Cred is retired" - a three-way split (`hearth-tokenomics-spec` S4), not a full burn, but only one of
the three shares is credited.** `p = φ_b·p (burned) + φ_n·p (serving node) + φ_v·p (verifier pool)`, launch values
`φ_b = 0.60 / φ_n = 0.30 / φ_v = 0.10`. `SettleTransactionDiff.ServingNodeCredPart` (`state.diffs.BlockDiffer
.Fraction(3, 10)`, hardcoded the same way `BlockDiffer.CurrentBlockFeePart`/`BlockRewardCalculator`'s tier
fractions are - a fixed protocol constant next to the logic that uses it, not a per-network `BlockchainSettings`
field, since nothing about this fraction is meant to differ mainnet/testnet/stagenet) implements `φ_n`: for each
settlement, the newly-confirmed delta (`newCumulative - previouslySettled` - always non-negative, since it's
computed only after the "counter non-decreasing" check passes) is split via `ServingNodeCredPart.apply` (integer
division first, matching every other `Fraction` in this codebase - see "Block fees"'s truncation warning in `docs/notes/economics.md`) and
credited to the miner (`tx.sender`, same identity as the settlement's `miner`/`RegisteredEnclave.operator`) in the
settled asset. Computed incrementally per settlement rather than off the raw `cumulativeSpent` field directly, so
splitting one client's spend across several `Settle` transactions credits the same total the node would have
received from one transaction straight to the final value (`ServingNodeCredPart` is linear in its truncation
behavior only when applied to the same delta either way - `SettleTransactionDiffTest`'s "credits the serving node
incrementally" case pins this down: two transactions settling 40 then a further 60 credit `12 + 18 = 30`, the same
as one settling 100 directly would (`100 / 10 * 3 = 30`)).

`φ_v` is **not** credited to anyone: the verifier pool has no on-chain destination of any kind yet (no
verifier-committee registry), so that 10% is, for now, folded into the effectively-burned remainder rather than
guessed at - a future pass adding a verifier pool needs to revisit `SettleTransactionDiff` to add that credit
alongside the other two, not replace either. `φ_b` isn't credited to a balance either, but unlike `φ_v` it isn't
simply dropped - it feeds workBoost (see "workBoost: boosting a validator's generating balance" below).

The retired (uncredited) portion needs no extra bookkeeping beyond what `Reserve` already does: it debits the
sender's spendable balance at reservation time and credits it nowhere (see "Reserve and BindApiKey" above) -
recording a settlement (and crediting the node's slice of it) is what makes the rest of that debit permanent,
there is nothing left to burn or move for the untouched remainder. Asset volumes are never touched either way:
they are fixed at issuance for every asset in this state model (`StateSnapshot.assetVolumes`'s own comment - "an
asset's volume is fixed forever at issuance"), and this repo has no Burn/Reissue transaction type to change that
invariant for.

**Testing** follows the same constraint and workaround as `ReserveTransactionDiffTest`/`BindApiKeyTransactionDiffTest`
(see above) - `SettleTransactionDiffTest` injects a `RegisteredEnclave`, and seeds `reservedAmount` directly through
`StateSnapshot.build`/`SnapshotBlockchain` rather than a real `Reserve` transaction, since only the reserved
*total* matters to the diff under test, not how it got there. The `RegisteredEnclave`-injecting `Blockchain`
wrapper itself used to be hand-rolled separately in each of the three test files (`export blockchain.
{registeredEnclaves as _, *}` plus one `override def`); adding a third copy for `SettleTransactionDiffTest` was
the trigger to extract it once, as `WithDomain.blockchainWithRegisteredEnclave(blockchain, enclave)`
(`node/testkit/.../db/WithState.scala`), with `Reserve`/`BindApiKeyTransactionDiffTest` refactored to call it too.
`TxHelpers.settle` defaults to a distinct enclave signer (`signer(9)`) from its `sender` (`defaultSigner`) -
reusing the same key for both would hide a bug where the wrong key ends up signing the settlement message, since
in production the two are always unrelated keys.

## workBoost: boosting a validator's generating balance

The second half of what was, until this pass, a documented gap: "value settled in epoch E feeds the beneficiary
validator's workBoost in E+1" (the gist), matching hearth-tokenomics-spec S7.1's `b_eff(i) = b_i(1 +
workBoost_i)` amplifying "a staker's forging weight." Two project decisions fixed the previously-open design
questions: **"let epoch be a generation period"** (so no new period concept was needed - `GenerationPeriod`,
already used by `committedGenerators`/`registeredEnclaves`, *is* the epoch), and **work is tracked in the name of
the validator, not the miner** (the settling enclave's `RegisteredEnclave.validator` - the consensus generator it
boosts - not `RegisteredEnclave.operator`/the TEE miner submitting the `Settle`). Implemented as a **deliberately
simplified placeholder** for the spec's full curve, not the curve itself - see "Why simplified, not the full
spec formula" below for why, and for how to upgrade later without touching storage.

**Tracking (`SettleTransactionDiff`):** `SettleTransactionDiff.BurnedWorkPart` (`Fraction(6, 10)`, φ_b = 0.60,
same hardcoding rationale as `ServingNodeCredPart`) computes each settlement's burned share of its delta the same
incremental way `ServingNodeCredPart` computes the node's share, and accumulates it into the new
`Blockchain.workDone(validator, period)` - a third `Long` ledger with the exact `reservedAmount`/`settledAmount`
history mechanism (`Keys.workDoneSuffix`/`workDoneHistory`/`workDoneKeysAt`, `KeyTag.WorkDone*` appended at the
end, `StateSnapshot.workDone`), keyed by `(validator, period)` rather than a triple since there's only one
validator per enclave. Every settlement in one `SettleTransaction` shares the same `enclavePublicKey` and
therefore the same `registered.validator` and the same current `blockchain.currentGenerationPeriod` - so unlike
`settledAmounts`/node-credit (keyed per settlement), the whole batch accumulates into a single `(validator,
period)` entry. `registeredEnclave` having already resolved earlier in the same `for`-comprehension guarantees
`currentGenerationPeriod` is defined at this point (see `Blockchain.findRegisteredEnclave`), so fetching it again
as an explicit `Either` (rather than silently reusing a private detail of that lookup) is belt-and-braces, not a
reachable failure mode.

**`SettleTransactionDiff` also requires `registered.validator` to be a committed generator of `period` itself**
(`blockchain.committedGenerators(period).exists(_.address == registered.validator)`), a check independent of and
in addition to `registeredEnclave`'s own. This is load-bearing, not redundant, and its absence was a real Critical
bug caught in review before merge (security audit, Pass 2): `findRegisteredEnclave`'s current-or-next-period
window (see "Reserve and BindApiKey" above) means a `RegisteredEnclave` written for period `X` (StartBoost only
ever checked `validator ∈ committedGenerators(X)`, the period it registered *for*) is also visible while
`blockchain.currentGenerationPeriod == X.prev` - i.e. a `Settle` can land one period *before* the one StartBoost
actually verified committee membership for. Without this check, `workDone` could be written for a `period` whose
`committedGenerators(period)` doesn't include `validator` at all (e.g. a brand-new generator that committed for
`X` but not `X.prev`); `GeneratingBalanceProvider` sums `totalWork` only over `committedGenerators(workPeriod)`
(see below), so that validator's own `work` would be excluded from the sum it's later compared against, letting
`work > totalWork` and breaking the `(1 + MaxBoost)` bound `WorkBoost` depends on. `SettleTransactionDiffTest`'s
"rejects settling when the validator is not a committed generator of the current period" pins this down.

**Consumption (`GeneratingBalanceProvider`):** rather than patch every call site that reads generating balance
(`appender.findBlockAndGetGenerators` for eligibility/endorsement weight, `appender.minerBalance` for the PoS
delay check, `Miner`/`BlockChallenger` for local scheduling, `CommitToGenerationTransactionDiff` for deposit
sizing, `CommonAccountsApi` for read-only display), the boost is applied once, centrally, inside
`GeneratingBalanceProvider.balance` itself - the single function every one of those already calls through
`Blockchain.generatingBalance` (`BlockchainExt`). For a balance lookup at height `h`, the period being *generated
into* is `generationPeriodOf(h + 1)` (mirroring `findBlockAndGetGenerators`'s own `parentHeight.next` ->
`generationPeriodOf` derivation), and the work it draws on is that period's predecessor
(`GenerationPeriod.prev`, `None` only for the very first period - `start == Height(1)` - which therefore never
gets a boost, correctly: there's no period before genesis to have tracked anything in). `totalWork` is the sum
of `workDone(g.address, workPeriod)` over every `g` in `committedGenerators(workPeriod)` - the full committee
that period, not just validators who happen to have work, so a validator's boost is inherently a *share of the
whole committee's tracked work*, not an absolute count. Summed as `BigInt`
(`GeneratingBalanceProvider.workContext`), not a plain `Long`: individual `workDone` values are bounded
(`safeSum`'d at write time), but a sum over an unbounded number of committed generators isn't itself guaranteed
to fit a `Long` - flagged in review (security audit, Pass 2) since the original plain-`Long` `.sum` could silently
wrap on a large enough committee.

**`balance`'s per-account cost, and why `findBlockAndGetGenerators` doesn't call it directly.** Computing
`totalWork` is an `O(committee)` scan (`committedGenerators` plus one `workDone` read per member). `balance` is
public and computes it fresh per call, which is fine for every single-account caller above - but
`findBlockAndGetGenerators` calls it once *per committed generator* to build one block's eligibility list, which
would make that `O(committee²)` (flagged in review, code quality Pass 1, as a real per-block RocksDB-read
multiplier, not a one-off cost). `GeneratingBalanceProvider.workContext(blockchain, atHeight)` computes the
`(workPeriod, totalWork)` pair once; `balanceWithContext` takes it as a parameter instead of recomputing it.
`findBlockAndGetGenerators` calls `workContext` once and threads it through its per-generator loop via
`balanceWithContext`; every other caller keeps using plain `balance`, which computes the same context internally
for its own single account.

**`WorkBoost`** (`consensus/WorkBoost.scala`) is the pure curve: `boosted = balance + balance * MaxBoost * work /
totalWork` (`MaxBoost = 2`, the conservative end of the spec's own "2-3" governance range - see the file's own
comment on the "Bounded amplification (Lemma)" tradeoff), `0` whenever `totalWork <= 0` or this validator's own
`work <= 0`. The `(1 + MaxBoost)` bound (`work/totalWork <= 1`) holds *only* because `SettleTransactionDiff`
guarantees `work` is genuinely one of `totalWork`'s own addends (see the committee-membership check above) - it's
not an invariant `WorkBoost` can enforce on its own, only rely on. As defence in depth against exactly that
assumption ever being violated again (rather than trusting the write-side check silently forever), the final
`BigInt -> Long` conversion is guarded by an explicit `require(boosted.isValidLong, ...)` instead of `.toLong`'s
default silent-truncation behavior - `BigInt.toLong` wraps into an arbitrary, possibly negative, `Long` on
overflow with no error, which would otherwise turn a broken invariant into a corrupted generating balance instead
of a loud failure (flagged in review, security audit Pass 2, alongside the committee-membership check itself).
**BigInt intermediate arithmetic throughout, not Double**: this feeds directly into which blocks are valid, so it
has to be exact and platform-independent, the same "no floating point in consensus" rule `EmissionCurve` follows
(see "Why fixed-point BigInt, not `Math.pow`" under "HRTH emission curve" in `docs/notes/economics.md`).

**Why simplified, not the full spec formula.** hearth-tokenomics-spec S7.1's real curve is EMA-smoothed
(`w_i(E) = (1-α)w_i(E-1) + α·r_i(E)`), normalized against the **median** of every active validator's work that
period (not a sum-based share), then passed through a saturating function (`B_max · g/(g+κ)`). None of `α`/`κ`
has a value anywhere in the spec (unlike `B_max`'s explicit "2-3"), and a per-period median across every
committed generator is meaningfully more machinery than a sum. Given the project is pre-launch (see the emission
curve (in `docs/notes/economics.md`)/retirement-split sections' own "TODO: replace before launch" precedent - nothing on a live chain depends on
today's choice), the simplified version ships the actual mechanic (tracked work -> more forging weight, bounded
the same way) without inventing values for constants the spec doesn't provide. **Upgrading later needs no storage
migration**: `Blockchain.workDone` already stores the raw per-period signal (`r_i(E)`'s role) the EMA would be
built from, so swapping `WorkBoost`'s body for the full curve - and eventually adding the EMA smoothing and
median normalization around it - only touches `GeneratingBalanceProvider`/`WorkBoost`, not
`SettleTransactionDiff` or any persisted schema. The one wrinkle: EMA needs a "previous period's smoothed value"
to carry forward, which isn't stored yet (`workDone` is the raw, un-smoothed signal) - bootstrapping it from
already-stored historical raw values, retroactively or lazily, is a small follow-up, not a blocker.

**Testing:** `WorkBoostTest` covers the pure curve directly (zero-total, zero-own-work, proportional share,
truncation, the `(1 + MaxBoost)` bound). `GeneratingBalanceProviderTest` exercises the full wiring
(period resolution, committee enumeration, `workDone` lookup, boost application) against a *real* domain's
`effectiveBalance`/`generationPeriodOf` wrapped with a `Blockchain` that injects `committedGenerators`/`workDone`
for one specific period - the same "inject the minimal necessary state directly" technique used throughout this
feature, chosen over driving a real period boundary crossing (which needs an actual `CommitToGenerationTransaction`
committing a generator for a *later* period - see "node-it fixtures"'s own extensive notes in `docs/notes/testing.md` on how fiddly that is
- well beyond what this is testing). `generationPeriodLength = 1` makes the genesis period exactly `[1, 1]`, so
its predecessor is already well-defined and a height-1 lookup already draws on it, with no need to advance the
chain past genesis at all. `SettleTransactionDiffTest` covers the tracking side (attributed to the validator, not
the miner; accumulates within a batch and across transactions, mirroring the node-credit tests' structure).

