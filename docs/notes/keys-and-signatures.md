---
purpose: Implementation notes for keys and signatures (account keys, transaction JSON, transaction schema, proof verification)
---

# Keys and transaction signatures

## Keys

Keys come from the `tech.hearth.crypto` library, and an account now has **three** of them, each derived independently
from one seed by `KeyTree` at an account nonce:

- `SigningKey` (`KeyTree.signingKey`) — Ed25519. Signs blocks, microblocks, transactions and orders
  (`Block.buildAndSign`, `ProvenTransaction.signWith`). `SigningKey.fromSeed` takes a **32-byte** seed; `.publicKey()`
  gives the 32 bytes wrapped by `account.PublicKey`, and `.toAddress` the account address.
- `VrfKey` (`KeyTree.vrfKey`) — ECVRF. Produces only the PoS hit source (`Ecvrf.prove`, verified by
  `crypto.verifyVRF`). It is *not* derived from the signing key, so its public key is a plain `ByteStr`, never a
  `PublicKey`. A generator commits to it on-chain via `CommitToGenerationTransaction.vrfPublicKey` plus a
  proof-of-possession (`mkVrfPopSignature`); blocks signed by a generator whose committed VRF key doesn't match the one
  that produced the proof fail with `Invalid VRF proof` (or, from inside sodium,
  `crypto_scalarmult_ed25519_base_noclamp failed`).
- A BLS key (`KeyTree.blsSecretKey` → `BlsKeyPair`, `crypto/bls/`) — signs block endorsements for finality.

`crypto.signVRF` is gone entirely; only `crypto.verifyVRF` remains. A test can no longer hand-forge a VRF proof for a
block or microblock outside the committed-generator flow (see "Building blocks in tests" in `docs/notes/testing.md`) — there is no way to
sign a hit source with an arbitrary key any more.

`Address` is `tech.hearth.crypto.Address` — bech32, `thrth1…` on testnet — so base58 address literals from before the
migration no longer parse (`InvalidAddress`).

`tech.hearth.crypto` (the external key/address library) and `tech.hearth.account` (this repo's own package, whose
`Recipient.scala` defines `Address`/`PublicKey`-friendly companions) both now have a member named `Address`, which
matters for import shadowing: a wildcard `import tech.hearth.crypto.*` inside code that lives in
`package tech.hearth.account` silently wins over the same-package `Address` companion (imports outrank same-package
members), so unqualified `Address.fromPublicKey(pk)` there resolves to the crypto library's `Array[Byte]`-taking
overload instead of the account one — caught as a type mismatch (`Found: PublicKey, Required: Array[Byte]`), not a
"not found" error. `account/PublicKey.scala`'s `toAddress` hit this; fixed by fully qualifying
(`tech.hearth.account.Address.fromPublicKey(...)`) rather than dropping the wildcard import, since `KeyLength`/
`EthereumKeyLength` from the same import are used unqualified elsewhere in the file.

Two different types are called `MiningAccount`, which is worth keeping straight:

- `mining.MiningAccount` — the runtime pair, holding a constructed `SigningKey` and `VrfKey`;
- `settings.MiningAccount` — a `MinerSettings.accounts` entry, holding either a `mnemonic` (plus
  `signingAccount`/`vrfAccount`/`blsAccount` derivation nonces) or `signingKey`/`vrfKey`/`blsKey` as **hex-encoded
  seeds**, not keys — `MinerImpl` builds the runtime pair from them with `SigningKey.fromSeed(Hex.decode(…))`.

`MinerImpl` takes its accounts *only* from the settings, so handing a test helper a runtime `MiningAccount` configures
nothing and leaves `nextBlockGenerationOffsets` empty (the miner then reports `No delay` for every address). A seed
cannot be recovered from a `SigningKey`, which is why `withDomainAndMiner(minerAccounts = …)` takes indices into
`TxHelpers.signer` rather than keys, and derives the seeds with `TxHelpers.signerSeed`/`vrfSeedOf`.

`account.KeyPair`/`SeedKeyPair`/`PKKeyPair` are gone: nothing derives a key from a seed inside the node any more.
`account.PrivateKey` still exists but is vestigial — an opaque 64-byte `ByteStr` (`PrivateKeyLength = 64`, an Ed25519
expanded secret key) used only by `crypto.sign`, `MinerSettings.privateKeys` and the legacy `sign`/`signed` implicit
conversions on `Order`, `ExchangeTransaction` and `CommitToGenerationTransaction`. Prefer `SigningKey`. Note that a
32-byte seed is not a `PrivateKey`: passing one gives `invalid private key length: 32`, while passing a 64-byte private
key to `SigningKey.fromSeed` gives `Ed25519 seed must be 32 bytes`.

In tests, get keys from `TxHelpers`: `signer(i)`/`defaultSigner`, and `vrfKeyOf(signer)`/`defaultVrfKey` for the
matching VRF key. Wallets hand out `SigningKey` (`Wallet.signingKey(address)`).

## Transaction JSON

`TransactionType` was renumbered when the removed types went: `Genesis, Transfer, Exchange, Lease, LeaseCancel,
CommitToGeneration, StartBoost, Reserve, BindApiKey, Settle, Withdraw`, ids 1 to 11 (`MassTransfer` merged into
`Transfer`, see below, rather than surviving as its own id). A lease cancel is type 5, not 9. Transactions no longer
carry a `version`, and the field is absent from their JSON — an API expectation written as a JSON literal has to drop
it.

`SigningKey.publicKey()` returns an `Array[Byte]`, so interpolating it into an expected JSON string yields `[B@1234`.
Wrap it: `PublicKey(signer.publicKey())`.

`utils.byteArrayFromString` — which reads every `ByteStr` field of every request — no longer accepts a `base64:`
prefix, and no longer measures the string itself: an over-long one fails with `Can't parse '…' as base58 encoded byte
array` rather than naming the limit.

Aliases are gone entirely: `AddressOrAlias`, `Alias`, `CreateAliasTransaction` and the alias HTTP endpoints
(`aliasByAddress`, `addressByAlias`, alias-typed `/transactions/sign` requests) don't exist any more. `Address` is the
only recipient a transaction can name, and the `Recipient` protobuf message is now just `{ public_key_hash: bytes }`
with no oneof — `Recipient.of(bytes)` replaces the old `Recipient.of(Recipient.Recipient.PublicKeyHash(bytes))`.

`Order` still carries its own version (`Order.V1`..`Order.V4`, `TxHelpers.order(..., version = ...)`), independent of
the (removed) transaction version — don't conflate the two when adapting exchange tests: a version-per-order ×
version-per-order × version-per-tx combinatorial test collapses to version-per-order × version-per-order once the tx
side of it is dropped.

A transaction's REST JSON (`BaseTxJson.toJson`) always includes `proofs`; there is no `signature` field to fall back on
and no `version` to gate on any more, so `/transactions/sign`/`/transactions/broadcast` test helpers that branched on
"v1 uses signature, v2+ uses proofs" collapse to a single unconditional proofs check.

## Transaction schema: Transfer merge, fee restructuring, new tx types

`transaction.proto` changed in the sibling `protobuf-schemas` repo (its own working tree, not yet committed as of
this writing): `MassTransferTransactionData` is gone, `TransferTransactionData` took over its shape (`asset_id`,
`repeated Transfer transfers`, `attachment`, `fee_asset_id`), `Transaction.fee` changed from `Amount` (asset +
amount) to a bare `int64`, and five new oneof cases were added: `start_boost`, `reserve`, `bind_api_key`, `settle`,
`withdraw`. Consuming this required `mvn install` from the `protobuf-schemas` repo itself (`mvn -DskipTests install`),
the same "publish to `~/.m2`, then make sbt actually pick it up" dance as the crypto library (see "Dependency
cleanup" in `docs/notes/build-tooling.md`), plus one extra wrinkle: `build.sbt`'s resolver list had
`Resolver.sonatypeCentralSnapshots` ahead of `Resolver.mavenLocal`, so coursier preferred a real (older, already-
published) remote SNAPSHOT over the freshly-`mvn install`'d local one — the exact same staleness trap the
"Dependency cleanup" section warns about, just from a different cause (a real competing publish, not a resolution
cache). Fixed by swapping the order (`Resolver.mavenLocal` first); if `protobuf-schemas` ever stops being iterated
on locally, this order stops mattering and can be reverted.

**Transfer + MassTransfer merged.** There is only one `TransferTransaction` now, and it is inherently multi-
recipient (`sender, assetId, transfers: Seq[ParsedTransfer], fee, feeAssetId, timestamp, attachment, proofs,
chainId`, `TransactionType.Transfer`) — the shape the old `MassTransferTransaction` already had. The old single-
recipient `TransferTransaction` (with its own `recipient`/`amount` fields) is gone; `tx.recipient`/`tx.amount` don't
exist any more, only `tx.transfers` (a `Seq[ParsedTransfer]`, `ParsedTransfer(address, amount)`). A single-recipient
transfer is just `Seq(ParsedTransfer(recipient, amount))`. `TxHelpers.transfer(...)` keeps its old single-recipient
signature for ergonomics (builds a one-element `transfers` list internally); `TxHelpers.massTransfer(...)` also
still exists, unchanged signature, now just a thin wrapper returning the same `TransferTransaction` type.
`TransferTxSerializer`/`TransferTxValidator`/`TransferTransactionDiff` (in `state/diffs/TransferDiff.scala`) absorbed
the old Mass* versions outright — there is no separate Mass* serializer/validator/diff object any more.

**Fee wire format changed.** `Transaction.fee` is a plain `int64` (always HRTH unless the specific transaction's own
data message says otherwise) — there is no top-level fee asset any more. Only `TransferTransactionData`,
`ReserveTransactionData` and `WithdrawTransactionData` carry their own `fee_asset_id: bytes` field (empty = HRTH);
every other transaction type's fee is unconditionally HRTH at the wire level now. This lines up with what the
domain layer (`TxWithFee.InHearth`/`InCustomAsset`, `transaction/TxWithFee.scala`) already enforced before this
change for every type except Transfer — Lease/LeaseCancel/Exchange/CommitToGeneration were already `InHearth`, so
nothing about their domain classes needed to change, only `PBTransactions`' wire (de)serialization of the fee.
`PBTransactions.create`/`vanilla` no longer take/return a top-level `feeAssetId` — each `Data.*` case reads/writes
its own `fee_asset_id` field (Transfer/Reserve/Withdraw) or has none (everything else, implicitly HRTH).

**Five new transaction types - plumbing only, no semantics, except StartBoost/Reserve/BindApiKey/Settle (see
`docs/notes/hearth-transactions.md`).** `ReserveTransaction`, `BindApiKeyTransaction`, `SettleTransaction`, `WithdrawTransaction` (all directly in
`tech.hearth.transaction`, alongside `CommitToGenerationTransaction`, not in a subpackage) exist as domain case
classes with working protobuf/JSON round-trip and `TxHelpers` constructors (`TxHelpers.reserve`/`.bindApiKey`/
`.settle`/`.withdraw`). Of these, `WithdrawTransaction` still has **no validation or state-diff logic**: its
`TxValidator` is `Valid(tx) // Semantics not implemented yet` (the pattern every one of these five started from),
`TransactionDiffer.transactionSnapshot` has no `case` for it, so it falls through to `UnsupportedTransactionType` -
a broadcast one reaches the mempool/JSON layer fine but is rejected before any state change - and
`TransactionFactory.parseRequest`'s REST `/transactions/sign` path stubs it the same way `Genesis` always was
(`UnsupportedTransactionType`, no `TxBroadcastRequest` subclass exists for it). A field-name-driven best guess at
what it's for (not verified against any spec beyond the gist "settle" analysis linked in `docs/notes/hearth-transactions.md`, which only covers
`Settle`): it looks like the counterpart that would credit a `Reserve`d amount back to the sender, but that hasn't
been designed - don't guess at this from field names again without checking whether a spec has since landed in
`hearth-specs`/`hearth-tokenomics-spec`. `StartBoostTransaction`, `ReserveTransaction`, `BindApiKeyTransaction` and
`SettleTransaction` are no longer in this bucket - see "StartBoost: TDX quote verification and enclave
registration", "Reserve and BindApiKey: locking funds and binding enclave-sealed API keys" and "Settle: retiring
reserved funds" in `docs/notes/hearth-transactions.md`.

## Transaction signatures

`Proven.verifyFirstProof` is the only implementation of the check, exposed as the lazy val
`firstProofIsValidSignatureAfterV6`. It is enforced in exactly one place: `CommonValidation.disallowInvalidProofs`,
called first from `TransactionDiffer.validateCommon` inside its `if (verify)` gate. Every admission path — block
application through `BlockDiffer`, the UTX pool, and `TxStateSnapshotHashBuilder` — funnels through
`TransactionDiffer`, so that single call covers all of them.

Its other callers deliberately discard the result and must not be mistaken for enforcement. `ParSignatureChecker`
(called from `BlockDiffer`, `ExtensionAppender`, `Importer`) only warms the lazy val on a parallel pool with
`runAsyncAndForget`, so the sequential check is cheap by the time it runs. `Signed.signaturesValid` is a separate
mechanism, wired up for blocks, micro blocks and `MicroBlockInv` — never for a transaction.

`transaction.smart.Verifier`, which did this upstream, went with smart accounts, and for a while its call site was left
as `TracedResult.wrapValue(StateSnapshot.empty)` — during which nothing verified proofs at all. `ExchangeTransaction`
overrides `verifyFirstProof` to chain both orders' proofs after the matcher's own; that must stay a `flatMap` chain,
since discarding the orders' results is exactly the bug that made forged orders acceptable.

Note that `Proven.verifyFirstProof` also rejects any transaction carrying more than one proof — a universal rule now,
though the message still refers to non-scripted accounts.

