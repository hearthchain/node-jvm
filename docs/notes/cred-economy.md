---
purpose: Implementation notes for the Cred economy (HRTH staking via StakeTransaction, the per-period payout to stakers, the cred asset setting)
---

# Cred economy: staking and the per-period payout

The consumption half of the Cred lifecycle already existed before this: `ReserveTransaction` locks an asset against a registered TEE miner, `SettleTransaction` retires it against an enclave-signed cumulative counter and credits the serving node `phi_n = 0.30`, and `SettleTransactionDiff.BurnedWorkPart` turns `phi_b = 0.60` into the `workDone(validator, period)` signal. See "Settle: retiring reserved funds" and "workBoost" in `docs/notes/hearth-transactions.md` for all of it. What this adds is the other end: somewhere for that tracked work to go, and a reason to hold HRTH.

`StakeTransaction` is the sixth of the stub transaction types from "Transaction schema" in `docs/notes/keys-and-signatures.md` to grow real semantics, and the first new one added since - it has no stub predecessor, so `TransactionType.Stake` (id 12) and `StakeTransactionData` (oneof case 112, in the sibling `protobuf-schemas` repo) were both added here.

## Two amounts, not one

`StakeRecord(active, pending)` is the whole design, and every behaviour the feature has falls out of it:

- `active` is what the period the chain is currently in pays out on; `pending` is what the next period will.
- `StakeTransactionDiff` writes `tx.amount` into `pending` and never touches `active`. Several StakeTransactions in one period therefore each overwrite `pending`, and the last one applied wins with no bookkeeping to detect the earlier ones.
- `locked = max(active, pending)` is the HRTH that cannot be spent. Raising a stake locks the larger amount at once; lowering one keeps the larger `active` locked until the boundary. Forging weight is charged on `active` instead, not on `locked` - see "Staking costs forging weight" below for why the two differ.
- `activated = StakeRecord(pending, pending)` runs at the first block of every period. That is what makes a stake set during period E start earning in E+1, what finally frees the HRTH of a stake that was lowered, and what keeps an untouched stake earning forever without a renewing transaction.

A stake set during E therefore earns for E+1 and every later period until it is changed, which is what "the staking reward is accrued only in the next and the following epoch" means. `periodStart` has to name the next period exactly, checked the same way and with the same message as `CommitToGenerationTransactionDiff`'s: accepting an arbitrary future period would mean storing more than one pending amount per address, and accepting the current one would contradict the rule above.

`amount` is a `TxNonNegativeAmount`, not the `TxPositiveAmount` every other transaction's amount is, because 0 is the *unstake*. Same on the wire: `StakeTransactionData.amount` is a bare `int64` rather than an `Amount` message, since a stake is always HRTH. `ProtoVersionTransactionsSpec` pins that a zero amount survives the round trip rather than reading back as an absent field.

## Staking costs forging weight, not just liquidity

`Portfolio.staked` is shaped like the existing `generationDeposit`: a view field, filled in by
`Blockchain.hearthPortfolio` from the stake ledger rather than accumulated by any diff, and `StateSnapshot.balances`
ignores it. Unlike `generationDeposit` it is subtracted from *both* `spendableBalance` and `effectiveBalance` - the
project decision is that staked HRTH stops counting toward forging weight, so staking and mining compete for the
same embers. This is the one part of the feature that changes a consensus rule rather than extending state.

**Spendability and forging weight are driven by different halves of the record, and that split is load-bearing.**
Spendability follows `locked = max(active, pending)`, so a raised stake is unspendable at once. Forging weight
(`GeneratingBalanceProvider.unstakedEffectiveBalance`) follows `active` - the half the address is also *earning* on,
so the same embers buy the yield and pay for it.

Subtracting `locked` from forging weight instead was the first implementation and is wrong, caught in review
(security audit, Pass 2). `active` only ever moves in `StakeRecord.activated`, at a period boundary, so forging
weight changes only where the committee itself does. `locked` moves the instant a transaction lands, which would
let any committed generator zero its own generating balance mid-period for the price of one Stake transaction and
no fund movement at all - a lever over `EndorsementFilter`'s 2/3 quorum denominator, and a way to reach the
`validGenerators.nonEmpty` case in `appender.findBlockAndGetGenerators` (its own TODO) without moving funds or
waiting out the 1000-block window. The HRTH a raised stake locks is still unspendable in the meantime; it just
keeps counting as skin in the game until the period it was staked for actually starts.

That is also what makes it safe to subtract a *current* value from a *windowed* `effectiveBalance`: `active` is
constant for a whole period, so there is no window for it to disagree with. `math.max(0L, ...)` clamps the result,
which is genuinely reachable - an address credited inside the 1000-block window and staking most of it has a
windowed minimum far below its stake.

Reading the stake off `blockchain` rather than as of `blockId` resolves to the same state because every consensus
caller hands in a blockchain positioned at the block it is asking about (`appender.appendBlock` does it explicitly,
with `blockchainUpdater.referencedBlockchain(block.header.reference)`). That is an invariant, not a coincidence, and
`workContext`'s own `workDone` read already depends on it - but it is not enforced by a type, so a future caller
passing a `blockId` older than the tip would need the stake resolved at that height instead.

Keeping the stake ledger a single current value per address, rather than the `prevHeight`-linked node chain
(`CurrentBalance`/`BalanceNode`, `Keys.hearthBalanceAt`) that `balanceSnapshots` walks, is what that buys. Going the
other way would also need the `writeKeyed` history to survive `maxRollbackDepth` trimming, which it does not:
`updateHistory` keeps only the heights at or above the safe rollback threshold plus the single most recent one below
it, so a `maxRollbackDepth` under the 1000-block generating-balance depth (`RocksDBWriterSpec` runs at 4) would
silently lose change heights inside the window.

Enforcement of spendability is `BalanceDiffValidation`, which folds the stake into the same "locked" term the
generation deposit already used, reading the new value off `snapshot.stakes` and falling back to
`Blockchain.lockedStake`. The two locks are summed with `safeSum`, not `+`: `stakedAfter` comes straight off a
transaction and is bounded only by `TxNonNegativeAmount`, so a raw sum could wrap negative and turn every check
below it into a pass. Two new messages distinguish the two locks - `not enough funds to stake` and `trying to spend
a stake` - and the existing deposit messages are unchanged byte-for-byte, because several suites assert on them.

## The payout

`StakingPayout.atPeriodBoundary` runs from `BlockDiffer.mkInitialSnapshot` at the first block of every period, driven off the period that has just ended. Issuance is that period's total tracked work, split pro-rata by `active` stake and minted into the cred asset, whose `assetVolume` rises by exactly what was credited.

That sum is `Blockchain.totalWork(period)`, shared with `GeneratingBalanceProvider.workContext` rather than written out twice. Two consensus paths read it - one sets forging weight through `WorkBoost`, the other mints supply - so two copies would have to agree forever, and a future change to what counts as a period's work (excluding a slashed generator, say) applied to only one of them is a fork. It returns `BigInt` because a sum over an unbounded committee is not `Long`-safe, and **both callers must reject rather than wrap**: `WorkBoost` has `require(isValidLong)`, and `StakingPayout` fails the block with `Staking issuance ... overflowed a Long`. Without that, a single staker's share - which is the whole of `issued` - would truncate through `BigInt.toLong` into an arbitrary minted amount, silently, on a supply-minting path. That was a Critical finding in review (code quality, Pass 1).

So the Cred paid out in a period is a direct function of what the miners demonstrably did in the period before, with no controller and no governance constant in between. **This deliberately stands in for `hearth-tokenomics-spec` S3.2's EMA-smoothed capacity controller** (`C-hat(E) = C-hat(E-1) + lambda*(C(E) - C-hat(E-1))`, `Q(E+1) = min((1+g)*C-hat(E), max(C(E), Q_floor))`), which needs values for `lambda`, `g` and `Q_floor` that the spec does not provide, plus a `credConsumed` ledger measuring the *full* settled delta rather than the burned share. Switching to it later replaces `StakingPayout.distribute` and adds those ledgers; it changes nothing about the stake records, the locking, or the boundary hook, which is why this was worth shipping first rather than blocking on constants that have to be picked against a running testnet.

Four behaviours at the edges, all pinned by `StakingPayoutTest`:

- nobody staked, or no work done, emits nothing rather than accumulating it - carrying it forward would hand a windfall to whoever stakes first;
- each share floors independently and the truncation dust is never minted, so `assetVolume` rises by the sum actually credited, not by the issuance it was computed from;
- a share that floors to zero writes no balance entry at all, keeping no-op writes out of the state hash;
- a stake that is only `pending` earns nothing, which is the payout's half of "a stake never earns in the epoch it was set in".

The hook runs **after** the reward part and any predefined snapshot at the same height, over a `SnapshotBlockchain` carrying both. That ordering is load-bearing: `StateSnapshot.assetVolumes` holds a resulting total rather than a delta, so a predefined snapshot re-issuing the cred asset at the very same height (which TESTNET's height-7000 entry does for ORCRED) would otherwise have its mint silently overwritten by the payout's, or vice versa, depending only on which side of the monoid it landed on.

The boundary block is O(stakers), and it is the most expensive block of each period. Nothing pages it yet. A staker whose record does not change writes nothing, so a steady staker set costs only the reads.

## The cred asset

`FunctionalitySettings.credAsset` (base16, `Option`, defaulted to `None`) names the asset the payout mints. It lives there rather than in a new per-network `CredSettings` object specifically because `FunctionalitySettings`' fields all have defaults, the way `daoAddress` does - so **no CUSTOM config template needed migrating**, unlike `RewardsSettings`' own rollout, which had to touch `custom-defaults.conf`, `network-defaults.conf`'s `devnet` alias, `docker/private/hearth.custom.conf` and `node-it/src/test/resources/template.conf` or fail config parsing outright (see "CUSTOM-network config files" in `docs/notes/economics.md`). Anything added here later that does *not* have a default reopens that whole migration.

TESTNET points at the existing genesis ORCRED asset (`PredefinedSnapshotSettings.TestnetORCRED`, which stopped being private for this). MAINNET and STAGENET name none, so their payout does nothing - they have no Cred asset yet, and a placeholder id would be worse than an absent one. Both halves are checked before anything is paid: an unset setting and a configured id that no predefined snapshot ever issued both pay nothing rather than failing every boundary block. Stakes are still activated either way - only the payout is skipped, never the activation, or a network without a Cred asset could never release a stake.

## Storage

Two ledgers, both on the `reservedAmount`/`workDone` history mechanism (`KeyTag` pairs appended at the end of the
enum since the ordinal is the on-disk prefix, `Keys` history plus value keys, `RocksDBWriter.writeKeyed`/
`rollbackKeyed`):

- `stake(address) -> StakeRecord`, 16 bytes, keyed by address, with a `StakeKeysAtHeight` index for rollback;
- `stakers -> Seq[Address]`, one value under one key. The per-address keys are not enumerable (a `...KeysAtHeight`
  index only names what changed at one height) and the boundary payout has to walk the whole set, so the set is
  stored explicitly.

**The set is stored whole but travels as a delta**, which is not a detail. `StateSnapshot` carries
`stakersJoined`/`stakersLeft`, not the set, and `TxStateSnapshotHashBuilder` hashes one preimage per membership
change under separate `stakerJoined`/`stakerLeft` tags. Carrying the whole set was the first implementation and was
a Critical finding in review (security audit, Pass 2): the state hash is computed *per transaction*, so a block of
`k` joins would hash `O(k * stakers)` bytes, on a set anyone can grow for the price of a minimum fee and one ember.
Roughly 5,000 joining transactions in a block would have produced hundreds of megabytes of hash preimage that every
validating node has to redo. As a delta it is `O(1)` per transaction, it merges associatively under `++` (whole-set
replacement relied on each producer having read its predecessor's set through a `SnapshotBlockchain`, which the
monoid does not guarantee), and it matches how every other field here already works - `balances` carries the changed
balances, not the whole map. `StakeRecord.membership` is the single definition of "in the set exactly while the
record is non-empty", shared by `StakeTransactionDiff` and `StakingPayout` so a change to `isEmpty` cannot be
applied to one and missed in the other.

What remains `O(stakers)`: the stored set is re-read and rewritten once per block that moves membership (not per
transaction), and the boundary payout walks every staker. **Both are bounded only by how many addresses have
staked, and there is no minimum stake and no cap** - a deliberate project decision, but it means the boundary block
is the most expensive block of each period and its cost is set by users. Nothing pages it. Closing this properly
means per-address membership keys plus a `Caches`-maintained enumerable set, the way `registeredEnclavesCache`
works; a minimum stake or a real fee for `TransactionType.Stake` (still `1 // TODO: decide` in `FeeConstants`)
would price it instead.

An emptied stake is written as `StakeRecord.empty`, not as an absent entry, so that clearing one is a write both the
storage layer and the state hash see. `readStakeRecord` treats a null value as "never written" but throws on a
wrong-length one: reading corruption as `StakeRecord.empty` would silently unlock someone's staked HRTH.
`TxStateSnapshotHashBuilder` hashes both halves of every record rather than just `locked`, since `active` and
`pending` diverge for a whole period after a change, they drive different things, and a node that disagreed about
which half a value sat in would pay out differently a period later while hashing identically today.

**Not mirrored onto the BlockchainUpdates stream**, and **not carried in the light-node snapshot wire format**.
Neither ledger appears in `events.StateUpdate` or in `PBSnapshots`, which is the same gap `workDone`,
`reservedAmounts`, `apiKeyBindings` and `registeredEnclaves` all already have - `PBSnapshots` carries none of this
fork's own ledgers, while `TxStateSnapshotHashBuilder` hashes all of them. A light node rebuilding a per-transaction
snapshot from network-supplied `txSnapshots` therefore computes a state hash missing those entries. That is
pre-existing and systemic rather than introduced here, but `Stake` is the first *permissionless* transaction type to
reach it: any user can now broadcast a transaction that trips it, where before it took a `Reserve` or `Settle`.
Worth closing before light-node sync is relied on, and it needs a `protobuf-schemas` change of its own.

## Testing

Split in two along the line of what a fixture can produce. `StakeTransactionDiffTest` drives everything through a real domain, since nothing about the transaction or the locking needs state a transaction cannot write. `StakingPayoutTest` cannot: `workDone` is written only by `SettleTransactionDiff`, and no fixture in this repo can drive a `StartBoost` to its accept path (see "Testing" under "DCAP collateral registry" in `docs/notes/hearth-transactions.md`), so the work, the committee and the stake records are injected through a `Blockchain` wrapper and `StakingPayout` is called directly - the same technique `GeneratingBalanceProviderTest` and `SettleTransactionDiffTest` use.

Two fixture traps cost real time getting `StakeTransactionDiffTest` green, both worth knowing before writing another period-crossing test:

- **A transaction is validated against the period of the block that carries it, not the tip's period.** `BlockDiffer` builds a `SnapshotBlockchain` at `height + 1`, so a `CommitToGenerationTransaction` for the next period is already too late in the boundary block itself - "the next period" has moved on by one there. The test's `generationPeriodLength = 4` exists purely so the block after a boundary block is still inside the same period, leaving room for both the commitment and the stake transactions to land before the next boundary.
- **The staker must not be the miner.** A miner's balance grows by the block reward and its fee share on every block it forges, so any balance assertion against it silently depends on how many blocks the case happened to append. `defaultSigner` mines and `signer(11)` stakes, funded separately.
