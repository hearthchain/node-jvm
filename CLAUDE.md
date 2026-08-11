---
purpose: Canonical agent instructions for the node-jvm repo (AGENTS.md is a thin pointer)
---

# node-jvm: agent instructions

Hearth chain node: a Scala 3 fork of the Waves node (consensus, state, REST/gRPC API, RIDE). sbt multi-module build, JDK 25. README.md covers node quickstart; keep this file short and laconic.

## Build / test

- `sbt compilePR`: clean, `scalafmtCheck`, compile everything (Test included) with `-Werror`; warnings block.
- `sbt checkPR`: `compilePR` + unit tests (`node-tests`, `grpc-server`) + `node/assembly` + docker tarballs. This is what CI (`check-pr.yaml`) runs.
- `sbt node-tests/test`: unit tests only; single suite via `sbt "node-tests/testOnly *SuiteName"`.
- `sbt "node-it/docker;node-it/test"`: integration tests (needs Docker); slow, don't run by default. `node-it/docker`
  builds the image from whatever `node`/`grpc-server` currently compile to; it is not rebuilt automatically, so re-run
  it by hand after touching either module's sources before `node-it/testOnly ...`.
- Sandboxed dev environments: the default Docker builder here cannot do nested overlayfs mounts, so `node-it/docker`
  (or any `docker build`/`docker buildx build` using the default `docker` driver) fails every `RUN` layer with
  `mount source: "overlay", ... err: operation not permitted`, even for a trivial `RUN echo`. Switch to a
  docker-container buildx builder first (`docker buildx create --use` if none exists, or `docker buildx use
  <existing-container-builder>` — check `docker buildx ls` for one already running), then build the image directly:
  `cd docker && docker buildx build --load -t hearth/node-it:latest .`. `sbt node-it/docker` itself always
  uses the default builder and cannot be told to use buildx, so it will keep failing in this environment; run the
  `docker buildx build` command by hand instead (the tarballs it needs are still produced by
  `sbt node-it/docker`'s dependency on `buildTarballsForDocker`, which itself works fine — only the final
  `docker build` step needs the workaround, so letting `sbt node-it/docker` fail once first to produce fresh
  tarballs before the manual `buildx build` is a reasonable way to sequence it).
- node-it test suites run with `-Dhearth.it.max-parallel-suites=N` to cap Docker resource usage (each suite starts
  its own set of containers); pass this as a JVM property on the `sbt` command line, not inside the sbt shell.
  Per-suite failures are deterministic (confirmed identical across parallelism 3 and 6 on the same code), so lowering
  parallelism only helps with wall-clock/resource pressure, not with distinguishing real failures from flakiness.
  Per-suite logs land in `node-it/target/logs/<run-id>/<abbreviated.suite.Name>/`, but the real assertion/exception
  message for a failure is only in the sbt console output (the per-suite `test.log` is DEBUG-level container/HTTP
  traffic and rarely contains the failure reason); grep the sbt output for `*** FAILED ***`/`*** ABORTED ***` and the
  lines immediately following instead.
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

`crypto.signVRF` is gone entirely; only `crypto.verifyVRF` remains. A test can no longer hand-forge a VRF proof for a
block or microblock outside the committed-generator flow (see "Building blocks in tests" below) — there is no way to
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

## Dependency cleanup: web3j and blst-java

`web3j` and the direct `blst-java` dependency are gone from `node`'s build. Both were legacy from the pre-fork
Waves codebase and had shrunk to a handful of call sites, most of them dead.

`web3j` turned out to have no live callers left. `EthEncoding` (its only wrapper) served three things, and every
one was dead code: `transaction.ERC20Address`'s JSON `Format` (nothing ever serializes or deserializes that
type), `CustomDirectives.massValidateEthereumIds` (zero call sites), and `testkit`'s `Domain.solidStateSnapshot()`
(computed inside `makeStateSolid()`, whose return value is discarded at its only call site,
`liquidAndSolidAssert`). All three were deleted outright rather than reimplemented, along with the dead
`Keys.assetStaticInfo(addr: ERC20Address)` overload. The one live `web3j` use, `P256Curve`'s `toBytesPadded` (pads
a `BigInteger` to a fixed-length unsigned byte array, used by the P-256 certificate-chain verification path),
moved to `org.bouncycastle.util.BigIntegers.asUnsignedByteArray`, an existing hard dependency (`bcprov-jdk18on`)
that already does the same thing.

BLS (`crypto.bls.BlsUtils`/`BlsKeyPair`) no longer imports `supranational.blst` directly; it calls
`tech.hearth.crypto.BlsKey` instead. `blst-java` itself is still on the classpath (`tech.hearth:crypto`'s own pom
depends on it), just not as a direct dependency of this repo any more.

This repo's BLS scheme did not match what `tech.hearth.crypto.BlsKey` shipped with. `BlsKey.sign`/`verify`/
`fastAggregateVerify` hardcode the eth2 proof-of-possession ciphersuite DST (`..._POP_`); this repo signs block
endorsements and generation commitments under the Basic (unaugmented, `..._NUL_`) DST instead, and implements its
own period-bound proof of possession at commit time (`CommitToGenerationTransaction.mkPopMessage`, `pubkey ++
periodStart`, checked with the same signing primitive as everything else) rather than the library's dedicated PoP
DST. `BlsKey` had no way to pick a DST, so a straight swap would have silently changed the on-chain BLS scheme.
Fixed by patching the crypto library itself (sibling repo, `../hearth-chain/java`) to add a second, explicitly
named Basic ciphersuite alongside the POP one: `signBasic`/`verifyBasic`/`fastAggregateVerifyBasic`, plus
`isValidPublicKey` (in-group, non-infinity check, backs this repo's `BlsPublicKey.validated`) and
`fromSeedKeygenV5` (blst's own arbitrary-length-seed `keygen_v5`, for `BlsKeyPair.fromSeed`, the seed-derived
test/tooling convenience path; distinct from `fromSeed`'s EIP-2333 derivation, which needs a real account index
and rejects seeds under 32 bytes). This makes the migration byte-for-byte identical to the previous
directly-on-blst implementation, not a protocol change: same DST, same `keygen_v5` salt, confirmed by the
"expected public keys" fixture in `BlsUtilsTest` and the hash-pinned fixture in `TxStateSnapshotHashSpec` (see
"Node Tests" below) needing zero changes.

A short seed to `BlsKeyPair.fromSeed`/`fromSeedKeygenV5` collapses to the zero scalar (blst's own `keygen_v5`
quirk, not EIP-2333); `TxStateSnapshotHashSpec`'s "with generation commitment" case deliberately relies on this
(`Ints.toByteArray(101)`, 4 bytes) to get a compact, hash-pinned point-at-infinity BLS public key fixture. Do not
change `fromSeedKeygenV5`'s algorithm without recomputing that fixture.

The crypto library lives in a sibling repo, `../hearth-chain` (its Java module at `../hearth-chain/java`, Maven,
published as `tech.hearth:crypto:0.1.0-SNAPSHOT`). A change there needs `mvn install` (run from
`../hearth-chain/java`) to publish the rebuilt jar to `~/.m2` before `node` picks it up; `build.sbt` already
resolves through `Resolver.mavenLocal`, but sbt's dependency cache can still hold an already-resolved older
SNAPSHOT, so `sbt update`/a clean rebuild may be needed on top of the `mvn install` if a stale version was
resolved earlier in the same environment.

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

`api.BlockMeta.totalFeeInHearth` and the REST `totalFee` field remain HRTH-only, derived from the HRTH entry of
`total_fee`.

## HRTH emission curve

`BlockRewardCalculator.fullRewardAt` no longer returns a flat, voted constant (see hearth-tokenomics-spec S2): the
block reward continuously decays, `R(h) = R0 * 2^(-h/Hhalf)`, where `h` counts blocks since the first rewarded
height and `Hhalf` is the reward half-life in blocks. `h = height - 2`: the genesis block (height 1) earns no
reward (`mkInitialSnapshot` skips it outright, "crediting Hearth is only supported at genesis" via predefined
snapshots instead - see "Predefined snapshots"), so height 2 is `h = 0`. `fullRewardAt` returns `0L` for `h < 0`
rather than calling into the curve, since `EmissionCurve.rewardAt`/`powFixed` themselves reject a negative `h`.

### Why fixed-point `BigInt`, not `Math.pow`

`EmissionCurve` (`state/EmissionCurve.scala`) computes the curve as pure fixed-point integer arithmetic - no
`Double`, no `Math.pow`/`math.log`, no transcendental function call at runtime. This repo's name (`node-jvm`)
implies a non-JVM client is expected eventually, and IEEE-754 floating point only guarantees `Math.pow`/`Math.log`
accurate *within an error bound*, not bit-identical across implementations: HotSpot's `Math.*` are JIT intrinsics,
not required to match another language's libm. `BigInt` add/multiply/shift have no such freedom - the result is
exact and identical in any language with correct arbitrary-precision integers, so the curve is reproducible
bit-for-bit by a future non-JVM implementation as long as it runs the same algorithm against the same pinned
constants (below). `consensus.FairPoSCalculator.calculateDelay` already calls `math.log` for the PoS block-delay
calculation and carries this same latent cross-language risk - undocumented before now, not fixed here, out of
scope for this change, but worth knowing about before a second client implementation ships.

The representation is `Q(EmissionCurve.FixedPointBits)` fixed point, `FixedPointBits = 128`: a value `v` is stored
as `round(v * 2^128)`. `RewardsSettings.decayRatioFixed` is `2^(-1/Hhalf)` in this format, derived **offline, once**,
at arbitrary precision (not computed by any client at startup - an `Nth` root is exactly the kind of transcendental
this design avoids at runtime) and pinned as a literal, the same pattern `GenesisSettings.stateHash`/`blockId` use
for genesis (see "Genesis commitments") - derive once, pin, never recompute. `RewardsSettings.initialReward` (`R0`)
is likewise pre-derived: `R0 = floor(cEmit * ln(2) / Hhalf)`, `cEmit` being the network's total forged emission
(hearth-tokenomics-spec S2.1: `Cmax = Pgen + Cemit`, 95% forged / 5% genesis premine).

At runtime, `EmissionCurve.rewardAt(h, R0, decayRatioFixed)` computes `ratio^h` via fixed-point binary
exponentiation (`powFixed`: `O(log h)` multiplications, each `fixedMul(a, b) = (a * b) >> 128`, exact `BigInt`
multiply then a floor right-shift - unambiguous since every operand here is non-negative, so "floor" and "truncate
toward zero" coincide and there's no cross-language rounding-mode question), then floors
`R0 * ratio^h` back down by one more `>> 128`. Flooring at every block is what makes the hard cap hold *by
construction*: the running sum of rewards always stays strictly below `cEmit` (the curve's asymptote), so there is
no separate runtime clamp. `RewardsSettings` does still `require(decayRatioFixed <= 1 << FixedPointBits, ...)`
(`BlockchainSettings.scala`) - a ratio `> 1.0` would make the reward *grow* instead of decay, silently breaking
that invariant for a hand-misconfigured CUSTOM network; exactly `1.0` (a flat, non-decaying reward) is allowed and
is exactly how test fixtures get a controllable flat reward (below).

### Cross-language test vectors

The Go node (a separate, in-progress fork of `gowaves`) will need its own `EmissionCurve` port, and it has to
match this one bit-for-bit - that's the entire point of avoiding floating point above. The two implementations
don't share a build-time or runtime dependency: there is no library either one loads the curve logic from. Instead,
a sibling repo, `hearth-specs` (`emission-curve/` directory), holds the one canonical generator
(`derive.py`, Python stdlib `decimal`, arbitrary precision - the source of every constant in the table below) and
its checked-in output (`vectors.json`: per-network constants, `rewardAt` vectors including the full-precision
MAINNET year table, `powFixed` identities, and rejection cases for a negative height and an out-of-range ratio).
Each client hardcodes (transcribes) the same literal vectors into its own test suite - `EmissionCurveTest` here is
the Scala transcription - the same convention `hearth-chain`'s five crypto implementations already use for RFC
9381/9180 test vectors: no shared file loaded at test time, just independently-transcribed literals that all trace
back to one generator. `EmissionCurve.rewardAt`/`powFixed` are public specifically so a vectors-based test can call
them directly, the way `EmissionCurveTest` does. If a formula or a network's parameters (half-life, `cEmit`) ever
change, `hearth-specs/emission-curve/derive.py` is the first thing to update - regenerate `vectors.json` there,
then re-transcribe into this repo (and whichever others carry a copy), never the reverse.

### Per-network constants

| | MAINNET | TESTNET | STAGENET |
|---|---|---|---|
| `halfLifeBlocks` | 5,256,000 (10yr @ 60s blocks) | 1,440 (~1 day) | 100 (~1.7hr) |
| `cEmit` (embers) | 9,500,000,000,000,000 | 9,500,000,000,000,000 | 9,500,000,000,000,000 |
| `initialReward` (R0, embers) | 1,252,834,515 | 4,572,845,982,860 | 65,848,982,153,194 |
| `decayRatioFixed` (Q128) | 340282322045415694657836056900309514630 | 340118610667410880413344550167336787510 | 337931864918735857425456001828432707560 |

`cEmit` is `95,000,000 * Constants.UnitsInHearth` on every network (95% of the 100M-HRTH cap, matching
`Constants.TotalHearth`). Only `halfLifeBlocks` differs by design: MAINNET carries the spec's real 10-year figure
(`R0 ≈ 12.52834515 HRTH`, and the curve's year-1/2/4/8/12/20/40 rewards match the spec's own illustrative table to
the precision it's given - `EmissionCurveTest`'s golden vectors pin the exact ember counts). TESTNET/STAGENET get
deliberately short half-lives purely so the decay is observable on a running chain within a practical time, instead
of only in a unit test that calls `EmissionCurve.rewardAt` directly at an arbitrary height - not economically
meaningful, so their `initialReward` is correspondingly huge (the same `cEmit` compressed into a far shorter
half-life). All three networks' `initialReward`/`decayRatioFixed` are transcribed, not hand-derived here: the
canonical generator (`derive.py`, Python stdlib `decimal`, arbitrary precision) and its checked-in output
(`vectors.json`) live in the sibling `hearth-specs` repo's `emission-curve/` directory - see "Cross-language test
vectors" below. Re-run that generator to audit or change any of these literals; there is no in-repo tool for it
(unlike `GenesisBlockGenerator` for genesis commitments, which is local because nothing outside this repo needs to
agree with it).

### Hard cap, not the global constant

`BlockchainSettings.hardCap` (`= initialBalance + rewardsSettings.cEmit`) is this **network's own** supply ceiling,
deliberately not `Constants.TotalHearth * Constants.UnitsInHearth`: MAINNET/TESTNET's premine (5%, below) plus `cEmit`
(95%) sum to exactly that constant, but STAGENET's predefined snapshot premines the *entire* `Constants.TotalHearth`
at genesis *and* still carries a `cEmit` of 95M on top (STAGENET is an internal-only devnet, premined in full for
fast bring-up rather than following the 5%/95% split - see `PredefinedSnapshotSettings.STAGENET`'s comment), so its
real ceiling is `Constants.TotalHearth * Constants.UnitsInHearth + cEmit`. `RewardApiRoute`'s `remainingToCap` field
(`hardCap - totalHearthAmount`) reads this per-network value for exactly that reason - reading the global constant
instead makes STAGENET's remaining-to-cap go negative.

### Genesis premine

`PredefinedSnapshotSettings.MAINNET`/`TESTNET`'s genesis balances credit exactly 5% of the cap (`Pgen`), split
3% fallen-chains burn claim / 1% DAO treasury / 1% team (vested), per hearth-tokenomics-spec S2.1 - not the ~100%
premine these lists held before this change. The DAO-treasury address is the same one
`FunctionalitySettings.MAINNET`/`TESTNET.daoAddress` already commits to for the block-reward DAO split (below); the
burn-claim and team addresses are freshly generated placeholders (`Address.fromPublicKey` over an arbitrary 32-byte
value, encoded per-network via `Address.toBech32(hrp)` - see `Address.MAINNET_HRP`/`TESTNET_HRP`), following the
same **TODO: replace before launch** convention already used for `daoAddress`. STAGENET keeps its pre-existing
single-address, full-premine genesis balance unchanged (see "Hard cap" above for why that's fine).

### The miner/DAO split is unchanged, just fed a decaying input

`BlockRewardCalculator.rewardSharesAt` - the tiered miner/DAO split (nothing to the DAO below
`GuaranteedMinerReward` = 2 HRTH, half of the excess up to `FullRewardInit` = 6 HRTH, a flat `MaxAddressReward` = 2
HRTH above that) - was left as-is: it now receives `fullRewardAt`'s decaying value instead of a flat one, so a
network's DAO share shrinks and eventually stops (once the curve decays below `GuaranteedMinerReward`) over the
decades, without any change to the split logic itself. `Blockchain.blockRewardBoost` (used by
`RewardApiRoute.currentReward = reward * blockchain.blockRewardBoost(...)`) is unrelated, pre-existing, and still
hardcoded to `1` everywhere - not a multiplier the curve interacts with.

### `RewardApiRoute` JSON

The reward-voting fields this route used to report (`minIncrement`, `term`, `nextCheck`, `votingIntervalStart`,
`votingInterval`, `votingThreshold`, `votes`/`RewardVotes`) are gone, matching reward voting itself being
unimplemented (see "Node Tests" below) - replaced by `cEmit`, `halfLifeBlocks` (display/documentation only, not
consensus-relevant - the consensus-relevant value is `decayRatioFixed`) and `remainingToCap`. `currentReward`/
`totalHearthAmount`/`daoAddress` are unchanged. `CustomJson.fieldNamesToTranslate` (the `large-significand-format:
string` allow-list) drops the now-gone `minIncrement` and adds `cEmit`/`remainingToCap` - both routinely exceed
`2^53`, JS's safe-integer bound (`cEmit` alone is `9.5e15 > 9.007e15`), so they need the same string-encoding
escape hatch `totalHearthAmount` already had.

### Test fixtures: a controllable flat reward

Most existing tests want a small, exactly predictable reward to assert on, not to exercise the curve itself (that's
`EmissionCurveTest`'s job). `history.DefaultRewardsSettings` (`node/testkit`) pins `decayRatioFixed` to exactly
`1 << FixedPointBits` (ratio `1.0`), under which `EmissionCurve.rewardAt` returns `initialReward` unchanged at every
height - `fixedMul(oneFixed, oneFixed) == oneFixed`, so the flatness is exact, not approximate.
`history.withFlatReward(rewardsSettings, reward)` is the drop-in replacement for the old, pre-curve
`rewardsSettings.copy(initial = reward)` pattern tests used throughout to pin a specific value (e.g. to walk
`rewardSharesAt`'s DAO-share tiers). Before this change, `RewardsSettings.MAINNET`/`TESTNET`/`STAGENET` were all the
same flat 6-HRTH value, so nothing needed its own test-only flat setting; now that the three networks genuinely
differ, anything that used to lean on that coincidence needs `DefaultRewardsSettings`/`withFlatReward` instead.

This bit `test.DomainPresets.SettingsFromDefaultConfig` specifically: it loads
`HearthSettings.fromRootConfig(loadConfig(None))` - the packaged default config, whose network type resolves to
TESTNET - so every `DomainPresets.*` value (`RideV6`, `ConsensusImprovements`, etc.) silently inherited TESTNET's
new huge, fast-decaying reward instead of a stable one, until pinned to `DefaultRewardsSettings` there too. Only
one test actually failed at runtime from
this (`RewardApiRouteSpec`'s boosted-reward check, which asserts an exact `6.hearth` reward), because most tests
don't assert on an absolute reward-derived balance - but the exposure was systemic across most of `node-tests`, not
limited to the one test that happened to catch it.

### CUSTOM-network config files

MAINNET/TESTNET/STAGENET never parse a `rewards {}` HOCON block at all - `BlockchainSettings.fromRootConfig`'s
`ConfigReader` pattern-matches the network type and returns the hardcoded `RewardsSettings.MAINNET`/`TESTNET`/
`STAGENET` Scala object directly. Only `type = CUSTOM` actually reads `rewards` from config, via
`RewardsSettings derives ConfigReader` - so every packaged template a CUSTOM network's config might inherit needed
migrating to the new `c-emit`/`initial-reward`/`decay-ratio-fixed`/`half-life-blocks` keys, not just the three
hardcoded networks:
`node/src/main/resources/custom-defaults.conf`, `network-defaults.conf`'s `devnet` alias (itself `type = CUSTOM`),
`docker/private/hearth.custom.conf`, and `node-it/src/test/resources/template.conf`. Left unmigrated, loading any of
them fails config parsing outright (`KeyNotFound(c-emit, ...)` etc.), not just at some later, already-documented
"placeholder genesis hash" checkpoint. All four were given the same flat reward as `DefaultRewardsSettings`
(`decay-ratio-fixed = "340282366920938463463374607431768211456"`, i.e. `2^128`, quoted since it is a ~39-digit
decimal string, not a native HOCON number) to preserve their previous constant-reward behavior exactly, since
these are templates/dev fixtures rather than a network with real tokenomics.

## Token rename: waves → Hearth/HRTH, wavelet → ember

The native currency's code-level naming was renamed to match the "Hearth"/"HRTH" branding the tokenomics spec
already used: `Waves`/`waves`/`WAVES` → `Hearth`/`hearth`/`HRTH` (`Asset.Waves` → `Asset.Hearth`,
`Constants.TotalWaves`/`UnitsInWave` → `TotalHearth`/`UnitsInHearth`, `WavesSettings` → `HearthSettings`, the
`waves {}` HOCON config root → `hearth {}` and every `-Dwaves.*` system property → `-Dhearth.*`, REST/gRPC JSON
fields like `totalWavesAmount`/`totalFeeInWaves` → `totalHearthAmount`/`totalFeeInHearth`), and the base unit
`wavelet` → `ember` (`CommitToGenerationTransaction.DepositInWavelets` → `DepositInEmbers`). `Constants.UnitsInHearth
= 100000000L`, i.e. 1 HRTH = 10^8 embers, unchanged by the rename. This also reached the
two local proto messages (`node/src/main/protobuf/hearth/database.proto`): `BlockMeta.total_waves_amount` →
`total_hearth_amount`, and `TransactionData`'s oneof case `waves_transaction` → `hearth_transaction` (so
`TD.WavesTransaction` → `TD.HearthTransaction` at its two call sites in `database/package.scala`). Renamed files:
`WavesSettings.scala`/`WavesSettingsSpecification.scala`/`WavesTxChecks.scala` → `Hearth*.scala`,
`node/waves-sample.conf` → `hearth-sample.conf`, `docker/private/waves.custom.conf` → `hearth.custom.conf`.

Docker/deployment packaging followed the same rename: `docker/Dockerfile`/`entrypoint.sh`'s env vars
(`WAVES_NETWORK`/`WVDATA`/`WVLOG`/etc. → `HEARTH_NETWORK`/`HEARTH_DATA`/`HEARTH_LOG`/etc.) and paths
(`/etc/waves`, `/var/lib/waves`, `/usr/share/waves` → `/etc/hearth`, `/var/lib/hearth`, `/usr/share/hearth`), the
`node-it` test image tag (`com.wavesplatform/node-it` → `hearth/node-it`), the tarball names `buildTarballsForDocker`
produces (`waves.tgz`/`waves-grpc-server.tgz` → `hearth.tgz`/`hearth-grpc-server.tgz`), `grpc-server`'s artifact
name (`waves-grpc-server` → `hearth-grpc-server`, matching `node`'s already-renamed `hearth-jvm`), and the Linux
package name/summary in `node/build.sbt`/`ExtensionPackaging.scala` (`waves${network}` → `hearth${network}`,
`maintainer` → `tech.hearth`).

Several things were deliberately left saying "waves", each for a different reason:

- **The external `tech.hearth % protobuf-schemas` dependency's own fields** — `SignedTransaction.wavesTransaction`
  (from `transaction.proto`'s *top-level* `waves_transaction` field, not to be confused with the local
  `TransactionData` oneof case above, which is a different message in a different file),
  `BalanceResponse.WavesBalances`/`.waves` (`accounts_api.proto`), and `StateUpdate`'s `updatedWavesAmount`
  (`events.proto`) are defined in the sibling `protobuf-schemas` repo, out of scope here. The local hand-written
  code that talks to them keeps matching names too, rather than renaming just one side of the wire: `grpc-server`'s
  vanilla event mirror (`events.scala`, `events/repo/LiquidState.scala`, `events/protobuf/serde/package.scala`) and
  `events/fixtures/HearthTxChecks.scala`'s pattern matches all still say `updatedWavesAmount`/`wavesTransaction`.
  `PBTransactions.scala`, `PBBlocks.scala`, and `PBTransactionSerializer.scala` likewise still call
  `.wavesTransaction`/`.getWavesTransaction`/`.withWavesTransaction` on `SignedTransaction`. A future rename of
  `protobuf-schemas` itself needs to update all of these together.
- **CI publish destinations** — Docker Hub (`wavesplatform/wavesnode`, `wavesplatform/waves-private-node`,
  `wavesplatform/ride-runner`), `ghcr.io/wavesplatform/waves*`, `apt.wavesplatform.com`, and the `@waves/ride-lang`
  npm package (`.github/workflows/*.yml`, `create-aptly-repo.sh`) are real registries tied to existing
  `DOCKERHUB_USER`/`DOCKERHUB_PASSWORD`/`OSSRH_*` secrets; renaming the target without matching credentials would
  just break the release pipeline. Only cosmetic labels/descriptions in those workflows were updated (e.g.
  `org.opencontainers.image.description=Hearth Node`); `docker/private/Dockerfile`'s
  `ARG baseImage=wavesplatform/wavesnode:latest` default is the same case, left as-is.
- **`project/Dependencies.scala`'s `"com.wavesplatform" % "curve25519-java"`** is a real external Maven
  coordinate (an actual published groupId) — renaming the string breaks dependency resolution, not just cosmetics.
- **Historical lineage prose** ("a Scala 3 fork of the Waves node", "pre-fork Waves codebase", `gowaves`, and the
  `com.wavesplatform.*` → `tech.hearth.*` package-migration paragraphs above) describes this repo's actual
  ancestry and past migrations, not its current branding — left as written.
- **Real external references not owned by this repo**: `wavesnodes.com` seed hosts (`network-defaults.conf`),
  `waves.tech`/`docs.waves.tech` (homepage and doc links), and `mpotanin@wavesplatform.com`
  (`node/build.sbt`/`node/testkit/build.sbt` developer list).

## Transaction JSON

`TransactionType` was renumbered when the removed types went: `Genesis, Transfer, Exchange, Lease, LeaseCancel,
MassTransfer, CommitToGeneration`, ids 1 to 7. A lease cancel is type 5, not 9. Transactions no longer carry a
`version`, and the field is absent from their JSON — an API expectation written as a JSON literal has to drop it.

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

## Protobuf package migration

Generated protobuf code moved from `com.wavesplatform.*` packages to `tech.hearth.*` ones. Most of it comes from the
external `tech.hearth % protobuf-schemas` dependency, already on the new package name; but `node`'s own two local
proto files (`node/src/main/protobuf/hearth/{api,database}.proto`, covering `BlockMeta`/`LeaseDetails`/
`AssetDetails`/`TransactionsByIdRequest` and the rest of `database.proto`/`api.proto`) still carried a stale
`option java_package = "com.wavesplatform...."` left over from before the migration, unlike every other `.proto`
file (including the external ones), which already say `tech.hearth...`. That single leftover setting was the actual
root cause of the bulk of "not found"/type-mismatch errors across `database/Keys.scala`, `RocksDBWriter.scala` and
`database/package.scala` once the surrounding hand-written code moved to `tech.hearth.*` and started looking for
these types there — the fix is to update `java_package` to match, not to chase each individual "not found" site.

Hand-written Scala that assumed same-package visibility with the *not-yet-renamed* generated code (`grpc-server`'s
`api/grpc`, `events/protobuf`, `events/api/grpc/protobuf`, and `node`'s `protobuf/transaction/transaction.scala`)
used a `package object` re-export shim — `type X = tech.hearth.foo.X` plus `val X = tech.hearth.foo.X` for the
companion — instead of a blanket import, so callers could keep naming these types unqualified. That shim only makes
sense while the hand-written package and the generated package actually differ. Once the hand-written code itself
finished moving from `com.wavesplatform.*` to `tech.hearth.*` — the same package the generated code already lived
in — every one of these re-exports became a circular self-reference (`type X = tech.hearth.foo.X` written *inside*
`package tech.hearth.foo`), which scalac rejects as `[E161] Naming Error: X is already defined as class/object X in
.../target/src_managed/main/.../X.scala`. That specific error shape — a *Naming* error pointing at a generated file
under `target/src_managed`, not a plain "not found" — is the signal that a shim has gone stale, not that a type is
missing. Fix is to delete the redundant re-export lines; some files were 100% shim and got deleted outright
(`events/protobuf/package.scala`, `events/api/grpc/protobuf/package.scala`), others had real converter logic
alongside the shim and only needed the `type`/`val` alias lines stripped out (`api/grpc/package.scala`,
`protobuf/transaction/transaction.scala`).

Build-level references to renamed classes are easy to miss in a source-only grep, since they live as string literals
in sbt files rather than as Scala imports, and most don't fail loudly: `mainClass`
(`project/RunApplicationSettings.scala`), `extensionClasses` (`grpc-server/build.sbt`), `V.scalaPackage`
(`node/build.sbt`, controls the package the generated `Version.scala` lands in), and the `-Wconf:...&origin=...`
deprecation-suppression filters in `build.sbt` all embed a fully-qualified class name as a string. A stale
`mainClass`/`extensionClasses` entry only breaks at runtime (`ClassNotFoundException`), and a stale `-Wconf` origin
filter only breaks `-Werror` if and when the class it no longer matches happens to emit a fresh deprecation warning
— neither shows up compiling the Scala sources themselves, so both need a deliberate check after any package rename.

A few files were reduced to just a `package` line with no members at some earlier point in the migration and never
finished being deleted (`consensus/nxt/api/http/NxtConsensusApiRoute.scala`, `network/HistoryReplierL1.scala`,
`utils/CloseableIterator.scala`, three `api/http/requests/SignedXV2Request.scala` test files). Confirmed via
`grep -rl` that nothing in the repo referenced them, then deleted them outright. They don't cause "not found" errors
(nothing depends on them), only `-Wunused`/`-Werror` failures ("No class, trait or object is defined in the
compilation unit").

The `Transaction` wire message dropped `version` entirely: `chain_id`, `sender_public_key`, `fee`, `timestamp`, then a
`data` oneof of only the six surviving transaction kinds (transfer, exchange, lease, lease-cancel, mass-transfer,
commit-to-generation). Constructing one directly no longer takes a version argument. `SignedTransaction` simplified to
`{ wavesTransaction: Option[Transaction], proofs: Seq[ByteString] }`, with no `EthereumTransaction` oneof variant.
`StateUpdate`/`TransactionMetadata` lost their data-entry, script and alias fields and oneof branches along with the
features that produced them.

## SBT 2 unused-settings lint

`sbt compile`/`sbt compilePR` surfaced a fresh "there are 24 keys that are not used by any other settings/tasks"
warning right after the SBT 2 migration. Checking out the pre-migration commit and running the exact same
`node`/`grpc-server`/`build.sbt` files under a real sbt 1.12.14 shows zero such warnings, so this looked at first
like new dead weight from the migration — it isn't. `sbt-native-packager 1.11.7` cross-publishes separate jars for
sbt 1 (`sbt-native-packager_2.12_1.0`) and sbt 2 (`sbt-native-packager_sbt2_3`), but its GitHub source tree shows
`DebianPlugin.scala`/`LinuxPlugin.scala`/`JavaServerApplication.scala` living under the *shared* `src/main/scala`
directory, identical in both builds — only two small `xsbti.FileConverter` compat shims differ, under
`src/main/scala-2.12`/`scala-3`. The settings graph these plugins wire up — which key's value feeds which other
key's `.value` call — is therefore byte-for-byte the same on both sbt versions; sbt 1's own lint just never caught
these particular keys, and sbt 2's is more thorough at the same check (plausibly a side effect of its
action-cache rearchitecture needing a much more precise live/dead distinction across the settings graph than sbt 1
ever needed). All 24 keys were confirmed dead by reading the plugin source directly and cross-checking with
`inspect tree`/`inspect uses` against this build, not by assumption:

- `Rpm/*` (`node`): `RpmPlugin` cannot be disabled — `JavaAppPackaging` has it as a hard `requires`, not a trigger;
  `disablePlugins(RpmPlugin)` fails project load outright with `Failed to sort ... topologically`. This repo never
  runs an Rpm packaging task regardless (only Debian + Universal tarballs, see `packageAll`/`buildDebPackages`/
  `buildTarballsForDocker`). Separately, `JavaServerApplication.scala`'s "Daemon User and Group" block
  (`Rpm / daemonUser := (Linux / daemonUser).value` etc.) and `RpmPlugin`'s own `Rpm / executableScriptName`/
  `Rpm / name` bridges are dead **even when Rpm packaging is used**, on any project: the actual mustache-replacement
  machinery (`linuxScriptReplacements`) reads the `Linux`-scoped settings directly and never touches these
  Rpm-scoped copies — a real (harmless) gap in the plugin itself, not something specific to this repo's config.
- `node / Linux / javaOptions`: `JavaServerAppPackaging` unconditionally derives
  `Linux / javaOptions := (Universal / javaOptions).value`. The JVM options this repo actually cares about
  (`-J-Xmx2g` etc., `node/build.sbt`) are defined exactly once, at `Universal` scope, and baked into the Universal
  launcher script that node's own `systemd.service` template invokes via `ExecStart`. The `Linux`-scoped copy exists
  only to feed a classic SysV init script's inline JVM flags (`SystemVPlugin`), which node never uses (it uses
  `SystemdPlugin`) — so options aren't really defined twice by this repo, just once here plus one unused
  plugin-provided derived copy.
- `Debian/executableScriptName`, `Universal/executableScriptName`, `Universal-src/name`, and (for `grpc-server` only)
  `Debian/sourceDirectory`: `DebianPlugin`/`UniversalPlugin` define these as bridges from `Linux`/`Universal` scope,
  same dead-by-design pattern as the Rpm ones above — confirmed with `inspect tree Debian/linuxScriptReplacements`,
  which resolves through scope delegation straight to `Linux/linuxScriptReplacements` and never touches the
  Debian-scoped copies.
- `node / Debian / daemonUser`/`daemonUserUid`/`daemonGroup`/`daemonGroupGid`: `JavaServerApplication.scala`'s
  "Daemon User and Group" block, Debian side — same dead bridge as the Rpm ones above. Separately, this repo's own
  `node/src/package/debian/{postinst,postrm,prerm}` hardcode the account name from `${{app_name}}` rather than
  reading `daemon_user`/`daemon_group` replacements at all (see "node-it fixtures" for the analogous
  `${{app_name}}` mustache convention), since `maintainerScripts` there is fully hand-authored, not templated — so
  even a hypothetical upstream fix wiring these settings up would still find no consumer here.
- `node / debianControlScriptsDirectory`: unused only for `node`, not `grpc-server`. `node/build.sbt`'s
  `Debian / maintainerScripts := maintainerScriptsFromDirectory(...)` is a plain `:=` that fully replaces
  `DebianPlugin`'s default `Debian / maintainerScripts` — the only setting that ever reads
  `debianControlScriptsDirectory` — with hand-authored scripts. `grpc-server`'s `ExtensionPackaging` instead does
  `maintainerScripts := maintainerScriptsAppend((Debian / maintainerScripts).value - Postinst)(...)`, which reads
  the *old* value first and so keeps that default (and `debianControlScriptsDirectory`) reachable — confirmed with
  `inspect tree node/Debian/maintainerScripts` (stops at `Debian/packageSource`, no plugin default in the tree) vs.
  `inspect tree grpc-server/Debian/maintainerScripts` (does include it).
- `gitDescribedVersion` (every subproject): `sbt-git`'s `GitPlugin` injects this per-project unconditionally, but
  only the root's `enablePlugins(GitVersioning)` wires `version := gitDescribedVersion.value` — this repo
  deliberately versions every module from one root git descriptor rather than per-module, confirmed with
  `inspect uses gitDescribedVersion` (only `ThisBuild/version` and `hearth-node/version` show up as consumers). Its
  key reference needs the `git.` prefix (`git.gitDescribedVersion`) in `excludeLintKeys`, unlike the other keys
  here — it lives under `SbtGit.GitKeys`, exposed via the `git` settings object already used elsewhere in
  `build.sbt` (`git.useGitDescribe`), not as a bare top-level import.

None of the above are fixable from this repo's side beyond `excludeLintKeys` (`build.sbt`): the keys are defined the
moment their owning plugin is enabled, sbt has no API to retract a key another plugin's `AutoPlugin.projectSettings`
already added, and overriding a dead key's *value* doesn't remove it from the graph or change whether anything reads
it. `TransactionsApiGrpcImpl` failing to compile (`needs to be abstract, since def getStateChanges ... is not
defined`) is unrelated and pre-existing — confirmed by running the pre-migration commit's unmodified
`grpc-server` sources under sbt 1.12.14 too; almost certainly a `tech.hearth:protobuf-schemas:0.1.0-SNAPSHOT` drift
picked up from `~/.m2`/mavenLocal between whenever `TransactionsApiGrpcImpl` was last touched and now, not anything
either this pass or the SBT 2 migration changed.

## SBT 2 action-cache: `buildTarballsForDocker`

`run-integration-tests` (`.github/workflows/check-pr.yaml`) failed `sbt --batch "node-it/docker;node-it/test"` with
`ERROR: failed to calculate checksum of ref ...: "/target": not found` the moment `docker build` hit its
`RUN --mount=type=bind,source=target,target=/tmp/` step. `node-it/build.sbt`'s `docker` task depends on the root
`buildTarballsForDocker` (`build.sbt`) specifically to populate `docker/target/{hearth,hearth-grpc-server}.tgz`
before invoking `docker build` from the `docker/` directory, and the CI log's own `[internal] load build context`
step confirms the bug directly: it transferred only `1.15kB`, not the ~196MB the two tarballs total, so
`docker/target/` was empty when the build context was captured, despite `buildTarballsForDocker` having reported
success moments earlier in the same log.

Root cause, reproduced locally: `buildTarballsForDocker` writes its output via `IO.copyFile` straight to
`docker/target/*.tgz`, a path sbt's dependency/output tracking has no visibility into (it isn't a declared task
output, just an out-of-band filesystem write). Under sbt 1 every run of a task like this just re-executes its body;
sbt 2's `ActionCache` instead treats every task as cacheable by default and, on a cache hit, replays the cached
result *without re-running the body* — for a `Unit`-returning task whose entire purpose is a side effect, that
means the copy silently never happens. Confirmed by deleting `docker/target/` and running
`sbt buildTarballsForDocker` twice in a row with no source changes: the first run recreates the tarballs, the
second reports `[success]` in a few seconds (upstream tasks' log lines even replay) but leaves `docker/target/`
missing entirely - the exact shape of the CI failure. `setup-java`'s `cache: 'sbt'` in the workflow persists this
action-cache directory *across* CI runs for the same PR, which is what lets a stale hit strike on a fresh checkout
where the actual `docker/target/` directory obviously doesn't exist yet.

This is the same class of problem the SBT 2 migration already had to fix for `classpathOrdering`,
`compilePRRaw`, `IntegrationTestsPlugin`'s `logDirectory`/`testGrouping`, and `benchmark/build.sbt`'s `Jmh / compile`
(all wrapped in `Def.uncached` in the migration commit, see their entries elsewhere in this file for why each one
needed it) - `buildTarballsForDocker` just wasn't caught at the time since it only got the mandatory
`FileConverter`/`toFileRef` syntax updates to compile under sbt 2, not an audit for cacheability. Fixed the same
way: wrapped its body in `Def.uncached`, which forces the task to actually run every time regardless of what the
action-cache thinks it already knows. Any other task in this build that performs a filesystem write to a path
outside its own declared outputs is a candidate for the same bug and needs the same treatment.

## node-it fixtures

`template.conf`'s genesis section is on the current `balances`/`generators` schema (bech32 addresses), not the old
`transactions`/`initial-balance` one, though the balances/generators/assets themselves now live in the height-1 entry
of a `predefined-snapshots` array alongside `genesis`, not inside `genesis` itself (see "Predefined snapshots" below).
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
the rest of the "no seed recoverable from a node" design (see "Keys" above); node-it's `createKeyPairServerSide()`
test helper still called it and always 404'd. Split into `createAddressServerSide()` (registers a fresh account with
the node's own wallet, for when the *node* must sign with it via `/transactions/sign`, and returns only the address)
and the existing, purely-local `createKeyPair()` (no node round-trip at all, for when a caller just needs some
distinct public key and never needs the node to hold or use its private key). `createAddressServerSide()` is *not*
enough for a `CommitToGenerationTransaction` specifically, despite registering the address in the node's wallet:
`TransactionFactory.signCommitToGeneration` signs with `GeneratorKeys.signingKey(address)`, sourced only from
`hearth.miner.accounts` (see "Keys"), never from the wallet — an address the node only knows about through its wallet
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

A duplicate already-mined transaction resubmitted via `/transactions/broadcast` after restarting the node(s) that
mined it used to be accepted again instead of being rejected with `AlreadyInState`/`AlreadyInTheState`
(`NodeRestartTestSuite`, "the duplicate transaction cannot be put into the blockchain"). Root cause, confirmed with a
repro (`DuplicateTransactionAfterRestartSpec`): `RocksDBWriter`'s constructor rebuilds its tx bloom filters
(`prevTxFilter`/`currentTxFilter`) by seeking into the `txHandle`/default column families with plain
`writableDB.newIterator(...)` — default `ReadOptions`, no `setTotalOrderSeek(true)`. Every column family here is
opened with a 10-byte capped prefix extractor (`RDB.newColumnFamilyOptions`, for iterator performance), under which a
`seek()` to a key that isn't an exact match only sees keys sharing that key's prefix bucket unless total-order-seek is
set — exactly the caveat the surrounding comment already documented ("if specified key has less than 10 bytes:
iterator finds the exact key for seek(key) and becomes invalid after next()"). Every *other* iterator constructed
anywhere else in the database package (`ReadOnlyDB`, `DBResource`) already sets `setTotalOrderSeek(true)`; this one
constructor didn't. The practical effect: on a real restart, a fresh `RocksDBWriter` rebuilds these filters as
effectively empty, `containsTransaction` false-negatives for recently-mined transactions, and
`disallowDuplicateIds` silently lets them back in. Fixed by wrapping both iterators in a shared
`new ReadOptions().setTotalOrderSeek(true)`. `CommonValidationTest`/`TxBloomFilterSpec` never caught this because
neither exercises `RocksDBWriter`'s constructor against a *pre-existing* DB (they only ever build a fresh, empty one);
`DuplicateTransactionAfterRestartSpec` closes that gap by building a second `RocksDBWriter`/`Domain` via
`TestStorageFactory` against the same `RDB` mid-test, simulating exactly that.

`node-generator` and `benchmark` are both back to compiling clean under `compilePR` (fixed in a later pass). Both had
rotted the same way: whole feature areas the migration removed (RIDE compiler/evaluator/estimator, smart accounts,
`Issue`/`Reissue`/`Burn`/`CreateAlias`/`Data`/`SponsorFee`/`InvokeScript`/`Ethereum`/`SetScript`, `account.KeyPair`/
`SeedKeyPair`, `TxVersion`) were still referenced throughout. The fix pattern was the same as everywhere else in this
migration: delete whole files/benchmarks whose entire subject is a removed feature (nothing to salvage - the RIDE
evaluator benchmarks under `lang/v1/`, `HearthEnvironmentBenchmark`, `SmartGenerator`/`MultisigTransactionGenerator`/
`OracleTransactionGenerator` and the `Mode.MULTISIG`/`ORACLE`/`SWARM` cases that drove them), and migrate what's
still meaningful to the current APIs (`account.KeyPair` → `tech.hearth.crypto.SigningKey`,
`MassTransferTransaction.create`/`TxHelpers.buy`/`.sell`/`.exchange` current signatures, `Keys.hearthBalance`/
`hearthBalanceAt` in place of the removed `Keys.data`/`dataAt` data-entry storage `RocksDBSeekForPrevBenchmark`
benchmarked, `PoSCalculator.hit`/`FairPoSCalculator.calculateDelay` in place of the removed `HearthEnvironment
.calculateDelay` wrapper). `node-generator`'s two standalone dev utilities under `utils/generator/`
(`BlockchainGeneratorApp`, `MinerChallengeSimulator`) needed a real fix too, not deletion: `MinerImpl`'s constructor
dropped its explicit miner-accounts parameter (derived from `MinerSettings` via `GeneratorKeys` instead) and its
`forgeBlock`/`nextBlockGenerationTime` methods now take a separate `SigningKey`/`VrfKey` pair instead of one unified
key, and `BlockchainUpdaterImpl`'s constructor dropped its lease-loading-callback parameter entirely. `benchmark`'s
`NarrowTransactionGenerator`-equivalent (the actual `NarrowTransactionGenerator` in `node-generator`) now only
generates the six surviving transaction types; `Exchange` generation needs an explicit `tradeAssetId` (a pre-existing
asset id on the target chain) since there is no way to mint a fresh one any more.

### Remaining known node-it failures (as of this pass)

Fixed since the previous pass:

- `activation.FeatureActivationTestSuite`/`PreActivatedFeaturesTestSuite` — both set `hearth.features.supported`,
  a dead config path; the miner's actual vote list (and what `ActivationApiRoute` reports) comes from
  `hearth.miner.supported-features` (`MinerSettings.supportedFeatures`), which neither suite ever set, so the node
  never voted and status fell through to `Implemented` instead of `Voted`. Fixed both suites' config key; all 7 tests
  across the two suites pass now.
- `sync.transactions.SignAndBroadcastApiSuite`, "/transactions/sign should handle erroneous input" — not a
  validation-order issue as first suspected: the test signed as `sender.address` (the node's own miner/generator
  account), which is never in that node's wallet (wallet derives from a wholly separate seed, see "Keys"), so
  `resolveSigner`'s wallet lookup failed before ever reaching the request-shape check being tested — for *every*
  case in the test, not just one. Fixed by signing as a wallet-backed address from `createAddressServerSide()`
  instead. The suite's chainId-mismatch broadcast case was a separate, deeper issue: `TransferRequest` has no
  `chainId` field at all, so the request reader always builds the transaction against the server's own network
  regardless of what the request JSON says — that check is structurally unreachable for a `Transfer` broadcast via
  this route. Removed that assertion with a comment; it was also asserting a message
  (`"Address belongs to another network"`) that doesn't match current error text
  (`"Transaction from another network, expected: ..."` from `CommonValidation.disallowFromAnotherNetwork`) regardless.
- `sync.RollbackSuite`, "Apply the same transfer transactions twice with return to UTX" — two stacked causes, not
  the rollback logic: (1) the state snapshots being compared were taken one block apart between the two mining
  attempts, so a block reward difference leaked into the comparison whenever the same 190 transactions happened to
  need a different number of blocks between attempts — fixed by zeroing `rewards.initial` for the suite; (2) that fix
  was first applied to only one of the two node configs, which produced a genuine `InvalidStateHash` divergence
  between the two nodes (see the note on this under "node-it fixtures" above) rather than fixing anything — fixed by
  applying the override to both.
- `sync.MinerStateTestSuite` — two timing bugs (low-balance-miner selection, a too-tight explicit wait; see
  "node-it fixtures" above for both patterns) got the test running cleanly up to its actual assertion, which then
  turned out to depend on the removed depth-50 window (see "node-it fixtures" above); the test itself is now
  `ignore`d for that reason, permanently, per project decision.

Fixed in a later pass (same session), all following the two patterns already documented above (low-balance-miner
selection, and stale pre-`tech.hearth` migration assumptions):

- `sync.MerkleRootTestSuite`, `sync.SeveralAccountMiningSuite`, `sync.AddressApiSuite` (asset transfers must come from
  `firstKeyPair`, never a node's own account — see "node-it fixtures" above),
  `sync.AmountAsStringSuite` (fixed via the `AssetsApiRoute.jsonDetails` `issueTimestamp` fix, see "Transaction JSON")
  — all low-balance-miner or stale-assumption fixes, no node bugs found.
- `sync.lightnode.LightNodeMiningSuite` — two stacked bugs: (1) `fullNode.transfer(..., fullNode.balance(...).balance
  - 1.hearth)` tried to spend the 100 HRTH committed-generator deposit along with the rest of the regular balance
  (see "Balance snapshots" above; fixed by reading `.balanceDetails(...).available` instead); (2) once that no longer
  crashed the transfer, the test still failed on its core assertion — its `buildNonConflicting()`-based node
  selection always assigns node01 as the "full" node and node04 as the "light" one, but node04's genesis balance is
  2.5x node01's (see the `balances` list in `template.conf`), so the light node actually out-mined the full node in
  the early blocks the test asserts belong to the latter. Fixed by picking node07/node01 explicitly instead of
  through the builder, keeping the full node's balance well ahead of the light node's.
- `async.MicroblocksFeeTestSuite` — two stacked bugs: (1) the suite's sole miner was `Default(0)` (node01, the
  lowest-balance account in the whole fixture), whose PoS delay averaged ~30s/block; (2) once given a high-balance
  miner instead, the suite crashed outright the moment it reached its `pre-activated-features.2` height — NG (and
  with it the 40%/60% fee split under test) is unconditional now, not feature-gated, so pre-activating it hits the
  same "UNIMPLEMENTED FEATURE ... ACTIVATED ON BLOCKCHAIN" force-stop documented above. Rewrote the test to check the
  always-on 40%/60% split across two consecutive blocks instead of a before/on/after-activation sequence, matching
  how `BlockSizeConstraintsSuite`/`BlocksApiSuite` were collapsed earlier.
- `sync.grpc.LeasingTransactionsGrpcSuite` ("can not make leasing to yourself") and
  `sync.grpc.MassTransferTransactionGrpcSuite` ("cannot broadcast invalid mass transfer tx") — not node bugs: both
  `AsyncGrpcApi`'s `broadcastLease`/`broadcastMassTransfer` call `PBTransactions.vanilla(...).explicitGet()` on the
  client side, to compute `bodyBytes` for signing, before any gRPC call is made. That runs full domain-object
  construction (`LeaseTransaction`/`MassTransferTransaction.create`, which invoke their `TxValidator`s and
  `TxNonNegativeAmount`'s bounds check), so every one of these tests' structural rejections (self-lease, negative
  transfer amount, too many transfers, oversized attachment) now happens client-side as a plain
  `RuntimeException(validationError.toString)` (`EitherExt2.explicitGet` on a `Left`), never reaching the server as a
  `GrpcStatusRuntimeException`. `assertGrpcError` only handles the latter, so `case Failure(e) => Assertions.fail(e)`
  swallowed the real cause (confirmed by running with a temporary debug print). Fixed both tests to assert on the
  plain exception and its `ValidationError.toString` message directly instead of using `assertGrpcError`.
- `sync.grpc.BlockV5GrpcSuite`, `grpc.BlocksApiSuite`, `grpc.GrpcReflectionApiSuite` — not flakiness: all three
  build their `nodeConfigs` via `NodeConfigs.newBuilder`/`Builder(...).buildNonConflicting()`, which (unlike `build()`)
  does *not* shuffle nodes — it deterministically assigns the lowest-index `NonConflictingNodes` entry (node01, the
  fixture's lowest-balance account) as the sole/default miner, so these were consistent low-balance-miner failures,
  not intermittent ones; the "remain intermittently-aborting, not failure-vs-flake confirmed" framing from the
  previous pass was wrong. Fixed all three the same way, picking a high-balance node (node07) explicitly instead of
  going through the builder. `BlockV5GrpcSuite` additionally needed `SyncGrpcApi.blockSeqByAddress`'s
  `Base58.decode(address)` fixed to `Address.fromString(address).explicitGet().toBytes()` — addresses are bech32m
  now (see "Keys" above), so the raw `Base58.decode` call threw on the first non-base58 character.
  `GrpcReflectionApiSuite` additionally needed its `FileContainingSymbol` queries updated from the pre-migration
  `waves.events.grpc.BlockchainUpdatesApi`/`waves.node.grpc.BlocksApi` proto symbols to their current
  `hearth.events.grpc`/`hearth.node.grpc` packages (see "Protobuf package migration" above) — reflection returned a
  valid, successful response either way, just an `ErrorResponse` instead of a `FileDescriptorResponse` for a symbol
  that no longer exists under the old package, so the failure surfaced as a plain assertion mismatch, not a call
  error.

Fixed in a later pass (same session), a real production bug found by tracing `TwoNodesFinalizationTestSuite` through
both nodes' logs line by line (connectivity, endorsement gossip, and chain agreement all confirmed fine first):

- `EndorsementFilter.simulate` (`state/EndorsementFilter.scala`) only ever set `reached = true` *inside* the `while`
  loop that greedily adds endorsers one at a time. If the miner's own `endorsedBalance` (which already includes its
  balance before the loop starts - "a miner doesn't need to endorse its own block, mining is already an
  endorsement") already meets the 2/3 threshold by itself, or more generally if there is nobody left in `richest` to
  add, the loop body never executes even once - so `reached` stayed `false` forever despite `endorsedBalance` already
  equaling `totalBalance`. This is the single-committed-generator case exactly (`OneNodeFinalizationTestSuite`), and
  would hit in production for any generator set where quorum is already satisfied by the miner alone or before the
  last needed endorser is actually added. Fixed by computing the pre-loop `reached` value from the miner's own
  `endorsedBalance` up front, instead of hardcoding `false`. Regression test added in
  `EndorsementFilterSpec` ("reaches finalization on the miner's own balance alone, with nobody left to endorse").
- `TwoNodesFinalizationTestSuite` also had its own bug once the above was fixed: the "Finalized height checks" loop's
  runaway guard compared current height against a small fixed offset from the *pre-loop* `finalizedHeight` baseline
  (effectively ~5), but by the time this step starts the chain is already ~20 blocks in (quorum is structurally
  impossible during the genesis period, see above, so all of those blocks pass with `finalizedHeight` stuck at its
  genesis value) - so the guard fired and failed the test on its very first loop iteration, before any transaction/
  microblock ever got a chance to actually apply the now-correctly-computed quorate vote. Fixed by tracking height
  since the step itself started instead of the stale baseline. With both fixes, `TwoNodesFinalizationTestSuite`
  passes outright (5m15s).

Still blocked, not a test-infra gap any more - a genuine unresolved question in the always-on-node-restart path:

- `OneNodeFinalizationTestSuite` (single committed generator, so the `EndorsementFilter` fix above is exactly what
  it needed) now gets past every step through "Survives restart" - finalization itself works - but still fails at
  "Finalization voting in a block header": `node.blockHeaderAt(finalizedBlock1.height + 1).finalizationVoting` is
  `None`. `MicroBlockMinerImpl.forgeBlocks` embeds the accumulated `FinalizationVoting` by *re-signing the current
  liquid (not-yet-solidified) key block* on every microblock, so the data only becomes durably readable once a
  further key block supersedes it. This suite restarts the node's container between the finalized-height check and
  this assertion; if the block that reached quorum was still the liquid tip (not yet superseded) at the moment of
  restart, that in-memory liquid state is exactly what NG never persists separately from a solid key block, so it
  would be lost on restart regardless of whether finalization succeeded. Not yet confirmed as the actual cause vs.
  some other height/timing mismatch - next step is checking whether the block that reached quorum had already been
  superseded by a further key block *before* the restart happens, and if not, whether the test should wait for one
  more block before restarting.

Also newly found this pass, not yet fixed: `sync.transactions.SignAndBroadcastApiSuite`'s
"/transactions/broadcast should handle erroneous input" chainId case (see above) — the chainId check itself is fine,
but it's untestable for `Transfer` via broadcast as things stand; nothing wrong with proof-before-chainId validation
ordering, that part was a red herring.

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

- `state-hash` pins the snapshot the settings describe, sourced from the height-1 entry of `predefinedSnapshots`
  (`BlockchainSettings.genesisSnapshot`, see "Predefined snapshots" below), not from `GenesisSettings` itself;
- `block-id` pins the header, so it covers the state hash along with `timestamp` and `initial-base-target`;
- `signature` is verified against the header bytes by `validateGenesis`.

The id is the hash of the header and the signature is not part of it, so `block-id` does not pin `signature` and the
two are independent. node-it's `Docker.genesisOverride` used to conflate them (writing the id into `signature`, and
never pinning `state-hash`/`block-id` at all); it now computes and pins all three separately (see "node-it fixtures").

A mismatch fails `Block.genesis`, and `checkGenesis` turns that into a force-stop, so a node refuses to run on genesis
settings that would build a different chain. `custom-defaults.conf` ships placeholder `state-hash`/`block-id` that
every custom-network config inherits, so config-driven custom networks fail closed until the real values from
`GenesisBlockGenerator` are pasted in. Settings built in code — `DomainPresets`, `withDomain`,
`TestHelpers.genesisSettings` — leave all three `None` and stay unpinned, which is why tests are unaffected.

## Predefined snapshots

Genesis is no longer a special code path for state. `GenesisSettings` carries only chain/header params (`timestamp`,
`signature`, `initialBaseTarget`, `averageBlockDelay`, `stateHash`, `blockId`); the state genesis puts into an empty
node (assets, generators, balances) comes from `PredefinedSnapshotSettings`, a height-keyed entry in
`BlockchainSettings.predefinedSnapshots`, and genesis is simply the entry at `GenesisBlockHeight` (1). This exists
because there is no issue transaction any more (see "Transaction JSON"), so a predefined snapshot bundled in a
network's own config is the only way to mint a new asset, and since the mechanism is height-keyed rather than
genesis-only, minting can happen at any later height too, typically lined up with a feature activation only by
config-authoring convention, not by any code coupling to feature-activation logic.

`BlockDiffer.mkInitialSnapshot` looks up `predefinedSnapshots` for the height of the block about to be built, on
every block, and merges a match (`PredefinedSnapshot.build`, via the `StateSnapshot` monoid) into the
reward/carry-fee/penalty part that height already computes. At genesis that reward/carry/penalty part is
`StateSnapshot.empty` (no prior block to reference, no reward system running yet), so genesis is the degenerate case
of the same merge, not a separate branch.

A missing height-1 entry does not fail startup: `BlockchainSettings.genesisSnapshot` (used by `Block.genesis` and
`WithState`) falls back to an empty `PredefinedSnapshotSettings`, the same as an empty genesis balances list did
before predefined snapshots existed. A CUSTOM network's `predefined-snapshots` HOCON key is mandatory at parse time
(and MAINNET/TESTNET/STAGENET each have a hardcoded height-1 entry), but nothing checks a height-1 entry is actually
among its elements; settings built directly in code (tests, tools) routinely have none at all.

Only the height-1 (genesis) entry may credit `hearth`; `PredefinedSnapshot.build` rejects a later entry that does
("crediting Hearth is only supported at genesis"). Hearth supply growth beyond genesis is tracked as block rewards only
(`Blockchain.hearthAmount`/`BlockMeta.totalHearthAmount` compute purely from `previous + reward - penalties`, never from
a snapshot's own balances), so a later entry silently crediting Hearth would desync the reported supply from the real
one. A predefined snapshot beyond genesis is for minting new assets, not new Hearth.

The committed-generator funding check (`Generator X balance ... is less than required for generation`) has to
resolve against the *resulting* blockchain view (`SnapshotBlockchain(blockchain, snapshot).balance(...)`), not the
snapshot's own balance map alone: that map only holds entries for addresses this specific entry's own `balances`
touched, so a generator funded earlier (e.g. at genesis) and merely committed at a later height, with no balance
entry of its own in that entry, would otherwise look like it holds 0 and get wrongly rejected.

A predefined snapshot beyond genesis also rejects an asset id that already exists on chain
(`blockchain.assetDescription(id).isEmpty`), a check genesis itself never needs since state is empty at that point.

Rollback needs no special-casing for a non-genesis predefined snapshot: `RocksDBWriter`/`Caches` already undo
whatever got persisted at a height purely from what is there, not from why. The one genesis-specific branch anywhere
(committed generators are effective for the *current* period at `GenesisBlockHeight`, the *next* period everywhere
else) is keyed on height alone, so a later-height predefined snapshot's own committed generators automatically get
the correct non-genesis semantics.

`GenesisSnapshot`/`GenesisSnapshotSpec` were renamed `PredefinedSnapshot`/`PredefinedSnapshotSpec` as part of this;
`EmptyBlockchain` moved from `node-testkit` into `node` (`tech.hearth.utils`), since `Block.genesis` now needs an
always-empty `Blockchain` to build the height-1 snapshot against, independent of the real chain's current state (it
runs unconditionally on every startup, including against an already-populated chain, to verify the persisted genesis
block still matches).

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
