---
purpose: Implementation notes for testing (node-it fixtures, grpc-server tests, Node Tests conventions)
---

# Testing: node-it fixtures, grpc-server tests, Node Tests

## node-it fixtures

`template.conf`'s genesis section is on the current `balances`/`generators` schema (bech32 addresses), not the old
`transactions`/`initial-balance` one, though the balances/generators/assets themselves now live in the height-1 entry
of a `predefined-snapshots` array alongside `genesis`, not inside `genesis` itself (see "Predefined snapshots" in `docs/notes/state-and-blocks.md`).
Every miner-eligible node in `nodes.conf` (node01-node09; node10 stays a plain account) is both a funded account and a
committed generator: its `hearth.miner.accounts` entry's `signing-key` is the same hex seed as its own account (so the
address that mines is also a regular funded address), with independently generated `vrf-key`/`bls-key` alongside it.
`predefined-snapshots`' height-1 `assets` (`NodeConfigs.GenesisAssets`) are fully distributed between the
"firstKeyPair"/"secondKeyPair" fixture accounts (`IntegrationSuiteWithThreeAddresses`), not to any node.

Genesis-committed generators are only committed for the genesis period, and nothing in node-it ever sends a
`CommitToGenerationTransaction` to renew that commitment for a later one. `generation-period-length` therefore has to
stay large enough (currently 1000000) that no suite's run ever crosses into a period the genesis commitment doesn't
cover — mining halts silently otherwise, surfacing only as a timeout waiting for a transaction to be mined, with
`Error mining block by X: ... is not a committed generator of period [a, b]` in the node's own log.

A block finalizes once the miner plus the endorsers `EndorsementFilter.simulate` greedily picks (richest first) reach
**2/3 of the total generating balance of every generator committed for that block's period** —
`endorsedBalance * 3 >= totalBalance * 2` in `EndorsementFilter.scala`, not a majority (1/2). A block's miner
endorses only its immediate parent - there is no retry or gradual accumulation across several blocks if quorum isn't
reached; the same generator set either clears 2/3 on that one attempt or the parent never finalizes at all, no matter
how many further blocks pass.

This bites node-it suites that override `generation-period-length` down to something small to actually reach a later
period (the finalization suites are the case that found it): every one of node01-node09 is committed *for the
genesis period* regardless of which of them the suite's own `nodeConfigs` actually starts containers for (see
above), so a single-node suite's one running node holds only its own slice of all nine nodes' combined balance for
every block in the genesis period - for `BiggestMiner` alone that's roughly 25%, nowhere near 2/3 - and finalization
cannot progress at all during it. Once the chain crosses into a later period the suite itself committed generators
for, the very first block of that period becomes endorsable by the new, presumably fully-online set, but that
endorsement can only land in the *next* block (one shot at the immediate parent, per the paragraph above) - so a
`finalizedHeight` baseline taken right at the period boundary itself still reflects the stuck genesis-period value;
it has to be read one block later, not "waited out" over several.

`Docker.genesisOverride` computes and pins all three genesis commitments (`signature`, `state-hash`, `block-id`) fresh
on every run, from the `Block.genesis` this config actually produces — it cannot leave any of them unset, because the
config that reaches a container is flattened into `-D` system properties (`Docker.asProperties`/`renderProperties`),
which has no way to express an absent value: a `null` HOCON key becomes an empty string once flattened, and
`GenesisSettings`'s `Option[ByteStr]` fields decode that as `Some(ByteStr.empty)`, not `None`. Left unpinned,
`custom-defaults.conf`'s placeholders (e.g. `state-hash = "BASE58STATEHASH"`) leak through as a real, failing mismatch.

Addresses are bech32m keyed by a process-wide default HRP (`tech.hearth.crypto.Address.setDefaultHrp`/`-Dhearth.hrp`),
a *different* setting from `AddressScheme.current.chainId` (which only affects a transaction's `chain_id` byte).
Nothing in `Application.scala` calls `setDefaultHrp` — a real node crashes the first time it renders or parses any
address (`IllegalStateException: default HRP not configured`) unless `-Dhearth.hrp` is set some other way. node-it
works around this for itself (`Docker.scala` calls `setDefaultHrp("thrth")` for its own JVM and passes
`-Dhearth.hrp=thrth` to every container's `JAVA_OPTS`), but this is a genuine unfixed gap for real deployments.

`entrypoint.sh` runs `exec java $JAVA_OPTS ...` with `$JAVA_OPTS` unquoted, so any config value containing a space
(e.g. a genesis asset `description`) is split into separate argv words by the shell; the first bareword-looking piece
is then parsed by `java` as the main class name (`Could not find or load main class ...`), silently discarding every
argument after it. node-it's own fixtures avoid this by keeping every config value space-free; the underlying bug
(`Docker.renderProperties` wraps a multi-word value in quotes that only `eval` would honor) is still there.

Minimum-fee validation is not implemented: `FeeValidation.getMinFee` computes the minimum fee for a transaction type,
but nothing in `TransactionDiffer`/`CommonValidation` ever calls it — `FeeValidation.apply` only checks `fee > 0`. A
transaction below the nominal minimum (e.g. a Transfer at 99999 instead of 100000) is currently accepted. This is
deliberate debt pending a fee-rules design, not an oversight; `TransferTransactionSuite`'s `fee = 99999` case is
commented out with a TODO until it exists.

Many individual suites carry their own `pre-activated-features`/`features.supported` overrides left over from the
pre-migration feature set (ids 2 through 15+); since only id 1 (`SmallerMinimalGeneratingBalance`) is implemented now,
activating any other id crashes the node outright (`UNIMPLEMENTED FEATURE N has been ACTIVATED ON BLOCKCHAIN, UPDATE
THE NODE IMMEDIATELY`, logged, then the process force-stops), which surfaces in node-it only as the whole suite
timing out/aborting with no useful client-side message — the real cause is only visible in that suite's own
`nodeNN.log` under `node-it/target/logs/`. This was the single largest cause of aborted (not just failed) suites
during the post-migration test fixup: check every crashing suite's node logs for this line before assuming a timeout
is a logic bug. The fix is almost always to just delete the stale feature ids from that suite's config (keeping `1`
if the suite still wants it pre-activated) — the behaviors those old ids used to gate (NG/microblocks, FairPoS, VRF,
block v4/v5 fields, block-size-by-bytes limiting) are all unconditional now, so a suite testing "before vs. after
activation" for one of them no longer has a meaningful "before" state and needs its assertions collapsed to just the
always-on behavior (see `BlockSizeConstraintsSuite`, `BlocksApiSuite`).

A generator account's `hearth.miner.accounts` entry now requires all three of `signing-key`/`vrf-key`/`bls-key` (a
hex-encoded seed each) when not using a mnemonic; `GeneratorKeys.fromSettings` throws `bls-key is required when
mnemonic is not provided` at node startup (another node-log-only crash) if a suite's hand-built account config only
sets the first two, which several still did since bls-key postdates when they were written.

Suites that pick `Miners.head`/`Default.head` (or any other low-index `NodeConfigs.Default` entry) as their sole
miner can time out waiting for even the very first transaction to be mined: genesis balances are distributed
unevenly (node01 has the smallest, node10 — non-mining — the largest; see the `balances` list in `template.conf`),
so a low-balance generator's expected PoS delay can exceed the fixture's own `8 * average-block-delay` tx-await
timeout. Prefer `BiggestMiner` (`Miners.last`) or another high-balance index when a suite doesn't care which specific
account mines.

Some suites (`GrpcReflectionApiSuite`, `BlockV5GrpcSuite`, `BlocksApiSuite`, and others built via
`NodeConfigs.newBuilder`/`NodeConfigs.Builder`) pick their node set via `Random.shuffle` inside `build`/
`buildNonConflicting`, so which specific account ends up mining varies run to run; combined with the low-balance-miner
timing issue above, this makes such suites intermittently slow/flaky rather than deterministically broken. Not fixed
as of this writing — `Builder`'s shuffle would need seeding or a balance-aware selection to make these reliably fast.

The REST API's block JSON moved the consensus fields out of the old nested `"nxt-consensus": {"generation-signature":
..., "base-target": ...}` object into flat top-level `"generationSignature"`/`"baseTarget"` fields
(`BlockHeaderSerializer.toJson`); `node-it`'s own `api/model.scala` `Block`/`BlockHeader` readers still expected the
old nested path, so `generationSignature`/`baseTarget` silently parsed as `None` in every suite that reads a block
header (breaking anything that then calls `.get` on them, e.g. `BlockV5TestSuite`).

`/addresses/seed/{address}` (used to recover a server-wallet-generated account's seed over the API) is gone, matching
the rest of the "no seed recoverable from a node" design (see "Keys" in `docs/notes/keys-and-signatures.md`); node-it's `createKeyPairServerSide()`
test helper still called it and always 404'd. Split into `createAddressServerSide()` (registers a fresh account with
the node's own wallet, for when the *node* must sign with it via `/transactions/sign`, and returns only the address)
and the existing, purely-local `createKeyPair()` (no node round-trip at all, for when a caller just needs some
distinct public key and never needs the node to hold or use its private key). `createAddressServerSide()` is *not*
enough for a `CommitToGenerationTransaction` specifically, despite registering the address in the node's wallet:
`TransactionFactory.signCommitToGeneration` signs with `GeneratorKeys.signingKey(address)`, sourced only from
`hearth.miner.accounts` (see "Keys" in `docs/notes/keys-and-signatures.md`), never from the wallet — an address the node only knows about through its wallet
fails `/transactions/sign` with `$address is not one of this node's generators, see hearth.miner.accounts`. A node-it
suite that needs a *second or third* generator identity on a single node (e.g. to test multiple committed generators
without spinning up that many containers) needs new fixture support for provisioning extra `hearth.miner.accounts`
entries with known seeds, which doesn't exist yet; `OneNodeFinalizationTestSuite`/`TwoNodesFinalizationTestSuite` hit
exactly this and can't pass until it's built.

Watch for this when a test deliberately diverges one setting between two node configs for comparison purposes (e.g.
zeroing `rewards.initial` on just the mining node to keep the reward out of a balance assertion): if *both* nodes
independently compute state hashes for the blocks the miner produces — which they always do, `supportsLightNodeBlockFields`
is unconditionally on — an override applied to only one node's config makes the two nodes disagree about it,
which is a real `InvalidStateHash` divergence (not the intended narrower test difference): the miner embeds a hash
computed under its own config, the other node recomputes independently and blacklists it, and the node that's now
short a peer never converges (endless reconnect/reject loop until whatever wait the test is doing times out). This
surfaces only as an unrelated-looking timeout with no obvious cause unless you check the non-miner's log for
`InvalidStateHash`/`Blacklisting`; a config override meant to affect block *content* has to go on every node's
config in the cluster, not just the miner's, `RollbackSuite`'s reward-zeroing being the case that found this.

`generation-balance-depth-from-50-to-1000-after-height` doesn't correspond to any settings field any more —
`GeneratingBalanceProvider.SecondDepth` hardcodes the generating-balance lookback window to 1000 blocks
unconditionally, with no shorter pre-transition window at all. A test relying on the old shorter window to observe a
funded account becoming mining-eligible within some practical number of blocks can't work any more — it would need
to wait out a real 1000-block window, well over an hour in node-it. `MinerStateTestSuite`'s only test is `ignore`d for
this reason, confirmed as permanent (depth-50 is not coming back).

Reward voting (a miner's `rewards.desired` setting influencing the term-end block reward) is not implemented and,
per project decision, will not be: `RewardApiRoute` hardcodes `RewardVotes(0, 0)` and nothing reads `rewards.desired`
to attach a vote to a mined block. `RewardsTestSuite` (entirely about this) was deleted rather than ignored, and
`BlockHeadersTestSuite`'s `desiredReward`/term-increase assertions were dropped for the same reason.

A restarted node used to accept a duplicate already-mined transaction instead of rejecting it with
`AlreadyInState`. Root cause: `RocksDBWriter`'s constructor rebuilds its tx bloom filters
(`prevTxFilter`/`currentTxFilter`) with a plain `writableDB.newIterator(...)` - no `setTotalOrderSeek(true)`. Every
column family here uses a 10-byte capped prefix extractor, under which `seek()` to a non-exact key only sees keys
sharing its prefix bucket unless total-order-seek is set (every *other* iterator in the database package already
sets it; this constructor didn't). Effect: on restart, the filters rebuild effectively empty,
`containsTransaction` false-negatives, `disallowDuplicateIds` lets duplicates back in. Fixed by adding
`new ReadOptions().setTotalOrderSeek(true)` to both iterators. Neither `CommonValidationTest` nor `TxBloomFilterSpec`
caught this since neither exercises the constructor against a *pre-existing* DB; `DuplicateTransactionAfterRestartSpec`
does, by building a second `RocksDBWriter`/`Domain` against the same `RDB` mid-test.

`node-generator`/`benchmark` compile clean under `compilePR`. If either rots again from a future feature removal
(the pattern that broke them this time: RIDE evaluator/estimator, smart accounts, `account.KeyPair`, `TxVersion`,
several removed transaction types), the fix is the same: delete whole files whose entire subject is the removed
feature, migrate what's still meaningful to current APIs (`account.KeyPair` → `SigningKey`, `MinerImpl`/
`BlockchainUpdaterImpl` constructor signatures, `TxHelpers` current builders). `NarrowTransactionGenerator` only
generates the six surviving transaction types; `Exchange` generation needs an explicit pre-existing `tradeAssetId`
since there's no way to mint a fresh asset any more.

### node-it: known gaps

`EndorsementFilter.simulate` correctly detects quorum reached via the miner's own balance alone, with nobody left
to endorse (e.g. a single committed generator) - it used to only ever set `reached = true` inside the endorser
loop, so that case stayed stuck at `false` forever despite already meeting the 2/3 threshold. Regression:
`EndorsementFilterSpec`.

Still open, a genuine unresolved question in the always-on-node-restart path, not a test-infra gap:
`OneNodeFinalizationTestSuite` gets past "Survives restart" (finalization itself works) but fails at "Finalization
voting in a block header" - `MicroBlockMinerImpl.forgeBlocks` embeds `FinalizationVoting` by re-signing the current
liquid (not-yet-solidified) key block on every microblock, so it only becomes durably readable once a further key
block supersedes it; a restart while the quorate block is still the liquid tip would lose that in-memory state
regardless of whether finalization succeeded. Not yet confirmed as the actual cause vs. some other timing mismatch.

## grpc-server tests (`WithBUDomain`, `BlockchainUpdatesSpec` family)

`WithBUDomain.withDomainAndRepo`/`withManualHandle` default to funding `defaultSigner` with the full configured
supply (`Constants.TotalHearth * Constants.UnitsInHearth`), matching `withGenerateSubscription`'s existing convention.
A test whose miner must start at a *specific* small balance (not the full supply) needs an explicit `balances` entry
naming that account — the auto-fund only backs off when the caller already named the address, same dedup-keep-first
rule as `node/testkit`'s `withDomain`. A committed generator can never be funded with a literal 0 either way: genesis
validation requires each committed generator's starting balance to cover `CommitToGenerationTransaction
.DepositInEmbers`, so a test wanting a small, exact miner balance funds it at genesis with that deposit (or the
test's own intended total) rather than via a later transfer.

Genesis (height 1) never produces a `BlockchainUpdated` event of its own — the update stream starts at height 2, the
first real block after it. Tests written before this held (subscribing/ranging from height 1 and expecting height 1
in the result) need their expectations shifted by one; this bit most of the pre-existing `BlockchainUpdatesSpec`
suite, which predates the genesis-occupies-height-1 convention.

A committed generator's *generating* balance is the minimum effective balance over a lookback window, not its
current balance: crediting one via a transfer in the same test run doesn't make it eligible to mine immediately,
because the window hasn't accumulated enough history yet. Fund a generator that needs to mine right away at genesis,
not through a same-run transfer — this is why the `(1 to 999).foreach(_ => d.appendBlock())` wait-out pattern
appears throughout `node/tests`, and why `grpc-server` tests that skip it need genesis-level funding instead.

Every order is now AssetDecimals-normalized unless it's V4 with `priceMode = Default` (see `ExchangeTransactionDiff
.getPortfolios`): the exchange transaction's own `price` field must be `>= sellOrder.price` in *normalized* terms
(`rawPrice / 10^(priceDecimals - amountDecimals)`), not the order's raw price. `TxHelpers.exchangeFromOrders`'s
default price is the raw `order1.price.value`, so a V1-V3 order pair traded against assets with different decimals
needs that normalized price passed explicitly, or validation rejects the tx with `exchange.price = X should be >=
sellOrder.price = Y (assetDecimals price = X)`.

`grpc-server`'s `Repo`/`Loader.scala` (untouched by the transaction-type-removal migration, confirmed via `git log`)
mis-numbers replayed history when a subscriber attaches (or a range/GetBlockUpdate is requested) starting from
height 1 after real blocks already exist: `Loader.loadBatch` computes each replayed row's height arithmetically from
the requested `fromHeight`, assuming dense storage starting exactly there, but genesis is never persisted by `Repo`,
so the first replayed row is mislabeled and duplicated against the next one. Pre-existing, not something this pass
fixed — six `BlockchainUpdatesSpec` tests that subscribe/query from height 1 against non-empty history are `ignore`d
for this reason (mirroring the six `ignore`d suites in `node/tests` for the empty-generator-set rule), with a shared
comment at the first one.

## Node Tests

By default, blocks are mined by defaultSigner, and when no generators are committed, defaultSigner is added as the committed generator.
Other generators may be explicitly added.

Committed generators pay `CommitToGenerationTransaction.DepositInEmbers` (100 HRTH). `withDomain` funds every
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
matcher that starts at zero fails with `negative hearth balance: before=0, after=-fee` even though the matcher fees it
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
currently fails the conflict-endorser suites and `PredefinedSnapshotSpec`, which encode the present behaviour rather than
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
`hearth.miner.accounts`.

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
one: `history.DefaultHearthSettings` puts it at 0, which lands every block in 1970 while `TxHelpers` stamps transactions
with the current time, past `maxTransactionTimeForwardOffset`. `DomainPresets` starts the chain an hour ago instead,
which is why `withDomain`'s default settings work and that one does not.

A block or microblock reference is a 32-byte block id (`Block.ReferenceLength = DigestLength`), not the 64-byte
`totalResBlockSig`. Assertions comparing `header.reference` against a signature silently never match, and a hand-built
microblock with a 64-byte reference is rejected with `Incorrect reference length: 64`. `Domain.appendMicroBlock` returns
the total block id to compare against.

### Genesis assets

Nothing issues an asset whose id a test hardcodes, so trading it fails with `Assets should be issued before they can be
traded`. Declare it in the genesis snapshot instead — `withDomain(..., assets = Seq(GenesisAssetSettings(...)))`, and
the same parameter on `assertDiffEi`/`assertLeft`. `PredefinedSnapshot` rejects a partially distributed asset, so the
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
The updater builds the genesis snapshot from *its own* settings, so handing it `HearthSettings.fromRootConfig(...)` while
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

