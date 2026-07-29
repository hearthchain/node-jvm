---
purpose: Canonical agent instructions for the node-jvm repo (AGENTS.md is a thin pointer)
---

# node-jvm: agent instructions

Hearth chain node: a Scala 3 fork of the Waves node (consensus, state, REST/gRPC API, RIDE). sbt multi-module build, JDK 17. README.md covers node quickstart; keep this file short and laconic.

## Build / test

- `sbt compilePR`: clean, `scalafmtCheck`, compile everything (Test included) with `-Werror`; warnings block.
- `sbt checkPR`: `compilePR` + unit tests (`node-tests`, `grpc-server`) + `node/assembly` + docker tarballs. This is what CI (`check-pr.yaml`) runs.
- `sbt node-tests/test`: unit tests only; single suite via `sbt "node-tests/testOnly *SuiteName"`.
- `sbt "node-it/docker;node-it/test"`: integration tests (needs Docker); slow, don't run by default.
- `sbt scalafmtAll`: auto-format before committing.

## Modules

`node` (core), `node/testkit` (shared test base classes + generators, published), `node/tests` (unit tests), `grpc-server`, `node-it` (Docker integration tests), `node-generator`, `benchmark`, `repl` (cross JVM/JS).

## Conventions

- In-repo skills live in `.claude/skills/`: `scala-conventions`, `engineering-philosophy`, `running-tdd-cycles`, `committing-changes`, `reviewing-changes`. They are the source of truth for code style, TDD, and review; `scala-conventions` is grounded in this repo's actual tooling.
- In-repo review subagents live in `.claude/agents/` (code-reviewer, security-auditor, architect-review, acceptance-auditor). Each runs one pass of `reviewing-changes` in a clean context for an independent, reproducible verdict; `/review` launches all four in parallel and aggregates.
- Comments are short and explain why, not what.
- Every `.md` file carries YAML frontmatter with `purpose:` (SKILL.md files use skill frontmatter, `name:` + `description:`, instead); paragraphs are single unwrapped lines.
- No em-dash (U+2014) in any file.
- GitHub Actions pinned by commit SHA (`@<sha> # vX.Y.Z`).

## PR workflow

- Default branch is `hearth-chain`. Never push to it; feature branch + PR, human merges.
- Before pushing: `sbt compilePR` locally; fix formatting with `sbt scalafmtAll`, never by hand-matching the checker. Then run `/review` and fix Critical/Major findings in the same branch.
- After pushing: watch `gh pr checks` until green; a red check is yours to fix or explicitly hand over.
- On a mistake repeated twice, encode the rule (lint config, test, CI, or this file); never just fix the instance.

# Implementation Notes

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

`Address` is `tech.hearth.crypto.Address` — bech32, `thrth1…` on testnet — so base58 address literals from before the
migration no longer parse (`InvalidAddress`).

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

## Block fees

The miner of a block receives two things from transaction fees:

- 40% (`BlockDiffer.CurrentBlockFeePart`) of the fees in its own block, credited per transaction as it is applied;
- 60% — the **carry** — of the fees in the block it references, credited up front in the initial block snapshot.

Always take the fraction **per transaction and then sum**, never sum the fees and then take a fraction of the total.
`Fraction.apply` truncates, so the two orders give different results, and only the per-transaction order matches the
shares credited while microblocks are processed.

Sponsorship is gone, so fees arrive in arbitrary assets: a carry is a `Portfolio`, not a `Long`. It is wrapped in the
opaque `BlockFee` (`state/package.scala`), which is validated to be non-negative and to carry no lease balance or
generation deposit. Both the carry and the total fee are persisted per block in `BlockMeta` (`carry_fee`, `total_fee`,
each a repeated `Amount`) and tracked per microblock in `NgState.BlockData`. Read them with
`Blockchain.carryFee(refId): Either[String, BlockFee]`.

`api.BlockMeta.totalFeeInWaves` and the REST `totalFee` field remain WAVES-only, derived from the WAVES entry of
`total_fee`.

## Transaction JSON

`TransactionType` was renumbered when the removed types went: `Genesis, Transfer, Exchange, Lease, LeaseCancel,
MassTransfer, CommitToGeneration`, ids 1 to 7. A lease cancel is type 5, not 9. Transactions no longer carry a
`version`, and the field is absent from their JSON — an API expectation written as a JSON literal has to drop it.

`SigningKey.publicKey()` returns an `Array[Byte]`, so interpolating it into an expected JSON string yields `[B@1234`.
Wrap it: `PublicKey(signer.publicKey())`.

`utils.byteArrayFromString` — which reads every `ByteStr` field of every request — no longer accepts a `base64:`
prefix, and no longer measures the string itself: an over-long one fails with `Can't parse '…' as base58 encoded byte
array` rather than naming the limit.

## Balance snapshots

`SnapshotBlockchain.balanceSnapshots(address, from, to)` prepends the liquid height's own snapshot and consults the
inner blockchain only when `from < liquidHeight - 1`. So asking from the height *just below* the liquid block yields
the liquid snapshot alone, and asking from one lower yields it plus everything from the inner blockchain — asking from
a later height can therefore return fewer snapshots than asking from an earlier one. That asymmetry is deliberate and
long-standing, so do not "fix" it: only height 2 is exempt, so that a generating balance there accounts for the genesis
snapshot. That exemption was gated on RideV6 upstream, where the suites checked both behaviours; RideV6 is always
active here, so it is unconditional and only the fixed behaviour is under test.

A `BalanceSnapshot` carries the generation deposit in its own field: `regularBalance` is the full balance, with the
deposit neither deducted from it nor part of it. The REST balance details split the same way — `regular` includes the
deposit, `available` and `effective` do not.

## BlockDiffer's reference precondition

`BlockDiffer` does not validate `block.header.reference` against the chain — `BlockchainUpdaterImpl.processBlock` does
that, and it is the only place that ever did.

`BlockDiffer` nevertheless *requires* the block to sit on top of the blockchain it is given, because
`mkInitialSnapshot` takes the carry from `blockchain.carryFee(block.header.reference)`. That was an unwritten
assumption; it is now asserted explicitly, and a mismatch fails with `Block references X, but the blockchain it is
applied to is at Y`. Every production call site satisfies it: the appender passes a blockchain positioned at the
reference, and `createInitialBlockSnapshot` builds one with `referencedBlockchain(reference)`.

`maybePrevBlock` is an `Option[SignedBlockHeader]`, not a block — no transactions are needed from it. It is used only
for penalties, the previous block timestamp, and the previous state hash. Whoever *builds* a block has to pass the same
one the differ will when it is applied, or the state hash they compute is not the one the differ arrives at:
`BlockChallengerImpl` passes the header at `challengedBlock.header.reference`, the parent the challenging block shares
with the block it replaces.

A consequence for tests: a block with a *bad* reference cannot be built at all, because `d.createBlock(ref = …)`
computes its state hash through the differ and trips this assertion. Build a valid block and retarget it —
`Block.buildAndSign` with a different reference — and hand it straight to `blockchainUpdater.processBlock(block,
hitSource, snapshot, generatorSet)`. Not to `Domain.appendBlockE`: that resolves the reference itself first, to verify
the VRF proof against the parent's hit source, and fails with `history does not contain parent` before the updater gets
to say `References incorrect or non-existing block`.

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

## Genesis commitments

`GenesisSettings` carries three commitments to what the genesis block must come out as. All are optional, and all are
checked in `Block.genesis` — the single place every path builds it (startup's `checkGenesis`, `WithState`, tests):

- `state-hash` pins the snapshot the settings describe: assets, generators, balances;
- `block-id` pins the header, so it covers the state hash along with `timestamp` and `initial-base-target`;
- `signature` is verified against the header bytes by `validateGenesis`.

The id is the hash of the header and the signature is not part of it, so `block-id` does not pin `signature` and the
two are independent — conflating them is a live bug in node-it's `Docker.genesisOverride`, which writes the id into
`signature`.

A mismatch fails `Block.genesis`, and `checkGenesis` turns that into a force-stop, so a node refuses to run on genesis
settings that would build a different chain. `custom-defaults.conf` ships placeholder `state-hash`/`block-id` that
every custom-network config inherits, so config-driven custom networks fail closed until the real values from
`GenesisBlockGenerator` are pasted in. Settings built in code — `DomainPresets`, `withDomain`,
`TestHelpers.genesisSettings` — leave all three `None` and stay unpinned, which is why tests are unaffected.

## Node Tests

By default, blocks are mined by defaultSigner, and when no generators are committed, defaultSigner is added as the committed generator.
Other generators may be explicitly added.

Committed generators pay `CommitToGenerationTransaction.DepositInWavelets` (100 WAVES). `withDomain` funds every
generator it commits that the caller did not name — the genesis snapshot is rejected with `Generator X balance 0 is
less than required for generation` otherwise — but only up to that default: a generator that *spends* in the test still
needs an explicit balance covering the deposit *on top of* what it spends. An explicit entry always wins, since genesis
balances are deduped by address keeping the first.

Committing a generator is not the same as being able to mine: `d.appendBlock()` and `d.createBlock()` generate with
defaultSigner, so a suite that passes its own `generators` has to include defaultSigner among them, or every such
append fails with `X is not a committed generator of period [a, b]`.

`withDomain`'s `balances` argument does not add to the genesis settings it is given — `withGenesisBalances` *replaces*
`genesisSettings.balances` with it. Balances written into the settings and then passed as `withDomain(settings)` are
silently dropped, and what is left is the entry `withDomain` adds for the auto-committed defaultSigner, so the chain
starts with `ENOUGH_AMT` and nothing else. Pass balances through the argument, and name defaultSigner there whenever the
total matters, since it is only auto-funded when the caller does not mention it.

A fee is charged before what the same transaction earns is credited, so having it *net* is not enough: an exchange
matcher that starts at zero fails with `negative waves balance: before=0, after=-fee` even though the matcher fees it
collects would more than cover it. Fund whoever pays a fee for the fee itself.

As implemented today, a commitment is mandatory with no exceptions. A block's VRF proof is only ever verified against a
VRF key the generator committed earlier (`Blockchain.vrfPublicKeyOf`), so an account that has not committed for a
period cannot produce a block in it — no matter how few committed generators there are, or how many of them are poor or
in conflict.

The intended rule has one exception, and it is **not implemented yet**: when a period's generator set is *empty*,
anyone may generate in it without having committed. Such a generator picks an arbitrary VRF key, but only once per
period — the first block of the period carries the key, and every later block of that period is verified against the
key that first one carried. That needs a new field in the block and changes through the main sources, so nothing in the
node does it today; a period whose generator set is empty currently cannot be extended at all.

An ineligible generator — poor or in conflict — must never mine either. `findBlockAndGetGenerators` does not enforce
that yet: it only rejects a foreign miner `when(validGenerators.nonEmpty && ...)`, so when *every* committed generator
of the period is ineligible one of them is still let through. That guard is wrong and is marked TODO; dropping it
currently fails the conflict-endorser suites and `GenesisSnapshotSpec`, which encode the present behaviour rather than
the intended rule.

A test that empties a generator set is therefore testing the unimplemented rule, whatever else it looks like it is
about, and the symptom is always the same: the miner never schedules an attempt for the uncommitted account, so the
tick sequence runs dry with `no tasks in scheduler`. Six are `ignore`d until the rule exists, with everything else
about them — accounts, commitments, funding, tick sequences — already fixed:

- `LastMicroBlockSuite`, "transfer in the last microblock of period" — the last micro block spends both generators
  down, so the block after it is the first of a period with no generator left;
- `MinerWithFinalitySuite`, "Mining works if not committed, but all generators have no right to mine" (both);
- `MultipleAccountsMinerWithFinalitySuite`, all three — an uncommitted second account generating once the committed
  first one is spent down.

`BlockChallengerImpl` takes a `GeneratorKeys` — the node's own generator keys, exactly as `BlockEndorser` does. It used
to take a bare `Seq[(SigningKey, VrfKey)]` that both `Application` and the testkit's `Domain` filled with `Seq.empty`,
so no challenge was ever produced anywhere; a test that wants one now only has to name accounts in
`waves.miner.accounts`.

A challenger is a generator like any other, and two things follow that are easy to miss:

- it must be a **committed generator of the period it challenges in**, not merely of the genesis period, so a test that
  runs past a period boundary has to commit it there too — and that second commitment costs a second deposit;
- its block replaces the one it challenges only if its timestamp is **better**, and that is a PoS delay, so the
  challenger has to out-weigh the miner it challenges. A challenger funded to just clear the generation minimum fails
  with `Challenging block timestamp (…) is not better than challenged block timestamp (…)`, or, once the block it
  challenges is already the liquid block, `Competitors liquid block … is not better than existing`.

`ChallengingAfterFinalizationSuite`'s "Anyone can challenge" still fails, and its premise is a *third* question,
distinct from the empty-set rule: the challenger there is neither committed nor funded while the committed set is
non-empty.

Two more things that catch out challenge tests, both visible in `BlockChallengeTest`: a challenged miner's balance is
banned, so it drops out of the period's generator set and the appender turns it away with `is not allowed to generate a
block` rather than any message about its balance — including when `makeStateSolid`/`liquidAndSolidAssert` needs one
more block, which is why the challenged miner there must not be defaultSigner. And a transaction is *elided* rather
than making its block unappendable only when it is affordable as the block is built and unaffordable once the challenge
takes the sender's balance, so the amount has to sit under the sender's balance.

Two different periods gate a block, which matters at a period boundary:

- the appender (`findBlockAndGetGenerators`) checks the generator against the committed set of the **new block's**
  period, failing with `X is not allowed to generate a block, allowed: …`;
- `PoSSelector` resolves the generator's VRF key at the **parent's** height (`validateBlockDelay`,
  `validateGenerationSignature`), failing with `X is not a committed generator of period [a, b]`.

So the generator of the first block of a period must be committed on *both* sides of the boundary — commit it in
genesis as well as in the transaction that commits it for the period under test. (That overlap is the designed flow;
`Blockchain.generationDeposit` charges a generator committed for both the current and the next period two deposits.)

Because tests log at `OFF` by default, an append rejected inside the appender surfaces only as
`Can't apply block …, see logs` from `Domain.appendBlock`. Re-run with `-Dlogback.test.level=TRACE` and grep for
`Could not append` to get the real reason.

### Building blocks in tests

No `TestBlock.create` overload builds a block a domain will accept, and none of them says so: the ones taking no
explicit `ref` use `randomSignature()`, and *every* one of them fills the generation signature with zeros and leaves the
state hash `None`. Build blocks through the domain instead: `d.createBlock(txs, generator = ...)` references
`lastBlockId`, proves with the generator's committed VRF key and computes the state hash, and `d.appendBlock(...)` goes
through the real append path — `d.appendBlock(txs*)` does both in one call.

Several other builders produce the same unappendable blocks:

- `TestBlock.withReference`/`withReferenceAndFeatures` fill the generation signature with `randomOfLength(...)`;
- `history.correctGenerationSignature()` returns all zeros despite the name, so everything built on it —
  `customBuildBlockOfTxs`, `buildBlockOfTxs`, `chainBaseAndMicro` — carries a proof that cannot verify.

A zero proof does not fail cleanly: `Domain.appendBlock` verifies it before `BlockDiffer` ever runs, and sodium throws
`crypto_scalarmult_ed25519_base_noclamp failed` out of `Ecvrf.verify` — the same message a proof by the wrong VRF key
gives.

A block verifies only if its generation signature is an `Ecvrf.prove` by the VRF key its generator committed, over the
hit source of the referenced height — and that hit source is taken 100 blocks back once the chain is that long
(`PoSSelector.getHitSource`, mirrored in `Domain.BlockchainUpdaterExt.processBlock`). `HistoryTest` shows the shape.

When a test must hand a block to something other than the domain, `WithState.blockOnTopOf(block, signer, blockchain)`
retargets the reference onto the chain head *and* computes the state hash. `assertDiffEi` and family go through it,
which is why they tolerate plain `TestBlock.create` blocks. It overrides the reference, so it is no use for fork tests —
those need `withDomain`/`Domain.appendBlock`, which is where `BlockchainUpdaterImpl` validates references anyway.

Blocks the domain builds are timestamped from the genesis block onwards, so the genesis timestamp has to be a plausible
one: `history.DefaultWavesSettings` puts it at 0, which lands every block in 1970 while `TxHelpers` stamps transactions
with the current time, past `maxTransactionTimeForwardOffset`. `DomainPresets` starts the chain an hour ago instead,
which is why `withDomain`'s default settings work and that one does not.

A block or microblock reference is a 32-byte block id (`Block.ReferenceLength = DigestLength`), not the 64-byte
`totalResBlockSig`. Assertions comparing `header.reference` against a signature silently never match, and a hand-built
microblock with a 64-byte reference is rejected with `Incorrect reference length: 64`. `Domain.appendMicroBlock` returns
the total block id to compare against.

### Genesis assets

Nothing issues an asset whose id a test hardcodes, so trading it fails with `Assets should be issued before they can be
traded`. Declare it in the genesis snapshot instead — `withDomain(..., assets = Seq(GenesisAssetSettings(...)))`, and
the same parameter on `assertDiffEi`/`assertLeft`. `GenesisSnapshot` rejects a partially distributed asset, so the
genesis balances must hold exactly the declared quantity between them; splitting it between the two traders is usually
what a test wants, since each side has to hold what it sells. To test a *balance* failure, issue the asset but give it
to someone uninvolved — otherwise the trade dies on "not issued" before reaching the check under test.

`decimals` is not cosmetic either: an order's price is normalized by the difference between the decimals of the two
assets of its pair, so any expectation about a normalized price depends on what the genesis asset declares. Where a test
was converted from issue transactions, the decimals they carried have to be carried over with it — an
`ExchangeTransaction` has no version any more, so a Default-mode order is *always* normalized, exactly like an
`AssetDecimals` one, and there is no longer a transaction version that compares prices as written.

### Testing a forced shutdown

JDK 25 removed `SecurityManager`, so `System.exit` can no longer be trapped. The components that stop the node take
their exit action as a parameter instead — `PoSSelector(..., onFatalStop)`, `BlockchainUpdaterImpl(..., onFatalStop)`
and `withDomain(..., onFatalStop = ...)` — defaulting to the real `forceStopApplication`; `HasFatalStopProbe` supplies a
probe. Unlike the old `SecurityException`, execution *continues* past the call instead of unwinding.

The reverse bites too: a suite that leaves `autoShutdownOnUnsupportedFeature = true` in its default settings and votes a
chain into activating an unimplemented feature calls the real `System.exit` and takes the whole run with it — sbt exits
with the stop reason's code (38 for `UnsupportedFeature`), with no test output.

### Wiring a state up by hand

When a test builds a `RocksDBWriter` and a `BlockchainUpdaterImpl` separately, give both the same `blockchainSettings`.
The updater builds the genesis snapshot from *its own* settings, so handing it `WavesSettings.fromRootConfig(...)` while
the writer got the test's genesis makes it apply the packaged config's balances, which fails on their base58 addresses
(`Genesis balance 3My3…: invalid recipient`).

`supportsLightNodeBlockFields` is currently unconditionally true, so the differ checks every block's state hash. A
hand-built `TestBlock` without one is rejected; `d.createBlock` supplies it. Where there is no domain to build through,
`WithState.blockOnTopOf(block, signer, blockchain)` is an object method — no need to extend the trait — and it hashes
the block *as given* before re-signing it, so the block has to already name the generator it will be applied with:
`TestBlock.create(…, signer = account)`. Hashing a block signed by someone else credits that someone else's reward and
yields `InvalidStateHash`.

`TestBlock.create` also defaults to a `baseTarget` of 2, which is not the chain's. Nothing rejects it — the base target
is only validated on the appender's path — but the *next* block's PoS delay is derived from it, so a miner asked to
build on such a block lands minutes in the future (`Block time … is from the future`, or a mining task that simply
never fires). Copy the parent's base target onto a hand-built block that something will later mine on top of.

`d.createBlock` computes that state hash against a blockchain carrying the next block reward. When passing such a block
to `BlockDiffer.fromBlock` directly, wrap the blockchain with
`SnapshotBlockchain(d.blockchain, Some(d.settings.blockchainSettings.rewardsSettings.initial))`, otherwise the differ
hashes a different miner balance and reports `InvalidStateHash`.

When asserting on fee arithmetic, zero the block reward (`rewardsSettings.copy(initial = 0)`); otherwise the reward
dominates the fees under test. Note that the miner does not receive the full reward — the DAO share is deducted — so
expectations written as `fee + reward` are wrong whenever a DAO address is configured.
