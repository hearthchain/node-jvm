---
purpose: Implementation notes for economics (block fees and carry, HRTH emission curve, premine, miner/DAO split, reward API)
---

# Economics: fees and the HRTH emission curve

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
snapshots instead - see "Predefined snapshots" in `docs/notes/state-and-blocks.md`), so height 2 is `h = 0`. `fullRewardAt` returns `0L` for `h < 0`
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
for genesis (see "Genesis commitments" in `docs/notes/state-and-blocks.md`) - derive once, pin, never recompute. `RewardsSettings.initialReward` (`R0`)
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
unimplemented (see "Node Tests" in `docs/notes/testing.md`) - replaced by `cEmit`, `halfLifeBlocks` (display/documentation only, not
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

