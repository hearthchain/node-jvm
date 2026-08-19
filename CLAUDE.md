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

`web3j` and the direct `blst-java` dependency are gone from `node`'s build - both legacy from the pre-fork Waves
codebase, with no live callers left except one: `P256Curve.toBytesPadded` (pads a `BigInteger` to a fixed-length
unsigned byte array, for P-256 cert-chain verification), moved to
`org.bouncycastle.util.BigIntegers.asUnsignedByteArray` (`bcprov-jdk18on`, already a hard dependency).

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

- **The external `tech.hearth % protobuf-schemas` dependency's own fields** — `BalanceResponse.WavesBalances`/`.waves`
  (`accounts_api.proto`) and `StateUpdate`'s `updatedWavesAmount` (`events.proto`) are defined in the sibling
  `protobuf-schemas` repo, out of scope here. The local hand-written code that talks to them keeps matching names
  too, rather than renaming just one side of the wire: `grpc-server`'s vanilla event mirror (`events.scala`,
  `events/repo/LiquidState.scala`, `events/protobuf/serde/package.scala`) and `events/fixtures/HearthTxChecks.scala`'s
  pattern matches all still say `updatedWavesAmount`. A future rename of `protobuf-schemas` itself needs to update
  all of these together. `SignedTransaction.wavesTransaction` *was* one of these (see "Transaction schema: Transfer
  merge, fee restructuring, new tx types" below) — `transaction.proto`'s top-level field is now `transaction`, not
  `waves_transaction`, and every local `.wavesTransaction`/`.getWavesTransaction`/`.withWavesTransaction` call site
  was renamed to `.transaction`/`.getTransaction`/`.withTransaction` to match.
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
cleanup" above), plus one extra wrinkle: `build.sbt`'s resolver list had
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
below).** `ReserveTransaction`, `BindApiKeyTransaction`, `SettleTransaction`, `WithdrawTransaction` (all directly in
`tech.hearth.transaction`, alongside `CommitToGenerationTransaction`, not in a subpackage) exist as domain case
classes with working protobuf/JSON round-trip and `TxHelpers` constructors (`TxHelpers.reserve`/`.bindApiKey`/
`.settle`/`.withdraw`). Of these, `WithdrawTransaction` still has **no validation or state-diff logic**: its
`TxValidator` is `Valid(tx) // Semantics not implemented yet` (the pattern every one of these five started from),
`TransactionDiffer.transactionSnapshot` has no `case` for it, so it falls through to `UnsupportedTransactionType` -
a broadcast one reaches the mempool/JSON layer fine but is rejected before any state change - and
`TransactionFactory.parseRequest`'s REST `/transactions/sign` path stubs it the same way `Genesis` always was
(`UnsupportedTransactionType`, no `TxBroadcastRequest` subclass exists for it). A field-name-driven best guess at
what it's for (not verified against any spec beyond the gist "settle" analysis linked below, which only covers
`Settle`): it looks like the counterpart that would credit a `Reserve`d amount back to the sender, but that hasn't
been designed - don't guess at this from field names again without checking whether a spec has since landed in
`hearth-specs`/`hearth-tokenomics-spec`. `StartBoostTransaction`, `ReserveTransaction`, `BindApiKeyTransaction` and
`SettleTransaction` are no longer in this bucket - see "StartBoost: TDX quote verification and enclave
registration", "Reserve and BindApiKey: locking funds and binding enclave-sealed API keys" and "Settle: retiring
reserved funds" below.

## DCAP collateral registry

The first real semantics landed for one of the five stub transaction types above: `UpdateCollateralTransaction`, a
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
in the right order (see "Predefined snapshots"): `PredefinedSnapshotSettings` gained the same six optional/repeated
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
there) rather than only synthetic ones, matching this repo's "Cross-language test vectors" convention of preferring
real, independently-produced fixtures over ones the test itself constructs. `IntelPkiTest`'s "against real
Intel-signed fixtures" group exercises the actual accept path against production code's pinned `rootCaPublicKey`;
every reject path (wrong trust anchor, non-self-issued claimed root, wrong CRL signer, garbage bytes) is covered by
synthetic fixtures built in-test with BouncyCastle (`bcpkix-jdk18on`, test-scope only), since a handful of fixed
real artifacts can't exercise every rejection path on their own. `UpdateCollateralTransactionDiffTest` covers all
six fields end-to-end: genesis seeding, permissionless update, idempotent resubmit, rollback, and the reject paths
above.

## StartBoost: TDX quote verification and enclave registration

`StartBoostTransaction` (`sender`, `validator: Address`, `tdxQuote: ByteStr`, `generationPeriodStart: Height`) is
the second stub transaction type from "Transaction schema" above to grow real semantics: a permissionless proof
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
  either value. The transaction sender is deliberately *not* bound into the quote: the sender becomes the enclave's
  `operator` on a first-registration-wins basis (see below), so a hijacked registration is escaped by restarting the
  enclave (which mints a fresh key) rather than contested. A quote whose enclave key already has an entry in
  `registeredEnclaves(next)` is rejected outright, before signature verification runs;
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

**Testing gap, also flagged rather than silently accepted:** no real, currently-valid PCK CRL fixture exists to
vendor - Intel's DCAP PKI has no static "PCK CRL for this specific PCK CA" sample the way a Root CA CRL or TCB
Signing CA chain does, and even upstream `dcap-rs`'s own test suite fetches this collateral live from Intel PCS at
test time rather than vendoring it, which this repo's tests never do (see "Cross-language test vectors"/real-fixture
convention above). `StartBoostTransactionDiffTest`'s deepest deterministically-reachable reject path is therefore
"PCK CRL not set" - the accept path, and the PCK-chain/QE/ISV signature verification beyond it, are exercised only
indirectly: `IntelPkiTest`'s synthetic-fixture groups cover `verifyIssuerChain`/`verifyCrl` in isolation with an
injectable trust anchor, and `DcapQuoteTest` confirms `isvSignedMessage`/`qeReportBodyMessage` slice the exact
expected byte ranges against real quote fixtures - but nothing currently exercises `StartBoostTransactionDiff`'s
full signature-chain wiring end to end against a quote that actually verifies. A future pass wanting to close this
would need either a live Intel PCS fetch (a new kind of test dependency this repo doesn't have) or threading an
injectable trust anchor through `StartBoostTransactionDiff` itself (mirroring `IntelPki.verifyIssuerChain`'s own
`trustAnchor` parameter) so a synthetic PCK chain built in-test with BouncyCastle could stand in for Intel's real
one.

### REST: signing and broadcasting

`StartBoostTransaction`/`UpdateCollateralTransaction`/`ReserveTransaction`/`BindApiKeyTransaction`/
`SettleTransaction` are the five stub types from "Transaction schema" above that are actually signable through
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

The third and fourth stub transaction types from "Transaction schema" above to grow real semantics, both reading
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
"HRTH emission curve"'s premine `TODO: replace before launch` notes) - a node with real old-format
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
division first, matching every other `Fraction` in this codebase - see "Block fees"'s truncation warning) and
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
(see "Why fixed-point BigInt, not `Math.pow`" under "HRTH emission curve").

**Why simplified, not the full spec formula.** hearth-tokenomics-spec S7.1's real curve is EMA-smoothed
(`w_i(E) = (1-α)w_i(E-1) + α·r_i(E)`), normalized against the **median** of every active validator's work that
period (not a sum-based share), then passed through a saturating function (`B_max · g/(g+κ)`). None of `α`/`κ`
has a value anywhere in the spec (unlike `B_max`'s explicit "2-3"), and a per-period median across every
committed generator is meaningfully more machinery than a sum. Given the project is pre-launch (see the emission
curve/retirement-split sections' own "TODO: replace before launch" precedent - nothing on a live chain depends on
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
committing a generator for a *later* period - see "node-it fixtures"'s own extensive notes on how fiddly that is
- well beyond what this is testing). `generationPeriodLength = 1` makes the genesis period exactly `[1, 1]`, so
its predecessor is already well-defined and a height-1 lookup already draws on it, with no need to advance the
chain past genesis at all. `SettleTransactionDiffTest` covers the tracking side (attributed to the validator, not
the miner; accumulates within a batch and across transactions, mirroring the node-credit tests' structure).

## Protobuf package migration

Generated protobuf code lives under `tech.hearth.*` now, not `com.wavesplatform.*`. If a local `.proto` file's
`option java_package` is ever left stale (pointing at the old package), the symptom is a wave of "not found"/
type-mismatch errors in whatever hand-written code depends on it - fix the `java_package`, don't chase each site.

A `package object` re-export shim (`type X = tech.hearth.foo.X` / `val X = ...` for the companion, used so callers
can name a not-yet-renamed generated type unqualified) becomes a circular self-reference once the hand-written
package and the generated package actually converge - scalac reports this as `[E161] Naming Error: X is already
defined as class/object X in .../target/src_managed/main/...`. That specific shape (a *Naming* error pointing at
`target/src_managed`, not a plain "not found") is the tell that a shim has gone stale and its `type`/`val` alias
lines need deleting, not that a type is missing.

Package renames also live as string literals in sbt files, not just Scala imports - `mainClass`
(`project/RunApplicationSettings.scala`), `extensionClasses` (`grpc-server/build.sbt`), `V.scalaPackage`
(`node/build.sbt`), and `-Wconf:...&origin=...` filters (`build.sbt`) all embed a fully-qualified class name as a
string and don't fail compiling Scala sources - a stale `mainClass`/`extensionClasses` only breaks at runtime
(`ClassNotFoundException`), a stale `-Wconf` origin only breaks `-Werror` once the class it no longer matches emits
a fresh warning. Check both by hand after any package rename.

A file reduced to just a `package` line with no members doesn't error (`-Wunused`/`-Werror` catches it as "No
class, trait or object is defined in the compilation unit" only if referenced nowhere) - `grep -rl` for zero
references before deleting.

## SBT 2 unused-settings lint

`compilePR` warns of 24 sbt keys "not used by any other settings/tasks" - not new dead weight from the SBT 2
migration, just a stricter lint: `sbt-native-packager`'s settings graph (`Rpm`/`Debian`/`Universal`-scope bridges
like `Linux/javaOptions`, `Debian/executableScriptName`, the Rpm daemon-user block) is byte-for-byte identical
under sbt 1 and sbt 2 (confirmed by running the pre-migration build under real sbt 1.12.14), but sbt 1's own lint
never caught these particular keys. Each was confirmed genuinely dead by reading the plugin source and
cross-checking with `inspect tree`/`inspect uses` against this build, not by assumption - `Rpm/*` in particular
stays dead even where Rpm packaging is used on any project, a real (harmless) gap in the plugin itself, since this
repo never runs an Rpm task regardless (Debian + Universal tarballs only). None of the 24 are fixable beyond
`excludeLintKeys` (`build.sbt`): a key is defined the moment its owning plugin is enabled, and sbt has no API to
retract one. `gitDescribedVersion`'s `excludeLintKeys` entry needs the `git.` prefix (`git.gitDescribedVersion`,
`SbtGit.GitKeys`), unlike the others.

## SBT 2 action-cache: side-effecting tasks need `Def.uncached`

sbt 2's `ActionCache` treats every task as cacheable by default and, on a cache hit, replays the cached result
*without re-running the body*. A task whose only job is an out-of-band filesystem write sbt's output tracking can't
see (e.g. `buildTarballsForDocker`'s `IO.copyFile` into `docker/target/*.tgz`, not a declared task output) silently
no-ops on a cache hit - `setup-java`'s `cache: 'sbt'` persists that cache *across* CI runs, so a fresh checkout with
an empty `docker/target/` can still hit stale and skip the copy, breaking `node-it/docker`'s later `docker build`.
Same class of bug already fixed for `classpathOrdering`, `compilePRRaw`, `IntegrationTestsPlugin`'s
`logDirectory`/`testGrouping`, and `benchmark/build.sbt`'s `Jmh / compile`. Fix: wrap the task body in
`Def.uncached`. Any task in this build that writes to a path outside its own declared outputs is a candidate for
the same bug.

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

## docker/private: static genesis configs rot silently

`docker/private/hearth.custom.conf` and similar checked-in genesis configs aren't exercised by CI (unlike node-it's
fixtures, which run every PR), so a migration that changes the config schema or address format leaves them broken
with nobody noticing until someone actually builds and runs the image. Verify by doing exactly that (`docker buildx
build`/`docker run`, real `curl` against the REST API), not by reading the config and reasoning about it. When
rebuilding one after this kind of rot, the account, its generator keys, and the genesis commitments it credits all
need rebuilding together from scratch (old base58 addresses and pre-migration key material don't carry over) - see
`docker/private/README.md`'s "Rebuilding genesis" for the `Block.genesis(...)`-based process.

A stale genesis timestamp is a separate, non-obvious production bug: a block's target timestamp is `parentTimestamp
+ delay` (PoS-derived, not the system clock), so a genesis timestamped years in the past makes the miner treat
every subsequent block as already overdue and mine as fast as it can compute them, each one still stamped near the
stale date - real transactions then fail `max-transaction-time-forward-offset` against a chain tip that hasn't
caught up, for however many blocks it takes to close a gap that can be years wide. Retimestamp genesis close to
now. This is also why predefined DCAP collateral (see "DCAP collateral registry") can't just be grafted onto a
config's genesis: collateral validity is checked against genesis's own pinned timestamp, and a real Intel-signed
fixture's validity window won't reach into whatever "now" a rebuilt genesis uses - `docker/private/README.md`
documents the mechanism and constraint for whoever fetches fresh collateral and rebuilds genesis to match it.

Nothing sets a default bech32 HRP in `Application.scala` (`-Dhearth.hrp` has no fallback) - a real node crashes
rendering its first address without it. `docker/private` works around this in its own Dockerfile; the
mainnet/testnet/stagenet-targeting `docker/Dockerfile`/`entrypoint.sh` still doesn't, a genuine open gap.
