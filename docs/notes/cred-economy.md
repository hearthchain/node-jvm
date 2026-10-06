---
purpose: Implementation notes for the Cred economy (HRTH staking via StakeTransaction, the per-period payout to stakers, the cred asset setting)
---

# Cred economy: staking and the per-period payout

The consumption half of the Cred lifecycle already existed before this: `ReserveTransaction` locks an asset against a registered TEE miner, `SettleTransaction` retires it against an enclave-signed cumulative counter and credits the serving node `phi_n = 0.30`, and `SettleTransactionDiff.BurnedWorkPart` turns `phi_b = 0.60` into the `workDone(validator, period)` signal. See "Settle: retiring reserved funds" and "workBoost" in `docs/notes/hearth-transactions.md` for all of it. What this adds is the other end: somewhere for that tracked work to go, and a reason to hold HRTH.

`StakeTransaction` is the sixth of the stub transaction types from "Transaction schema" in `docs/notes/keys-and-signatures.md` to grow real semantics, and the first new one added since - it has no stub predecessor, so `TransactionType.Stake` (id 12) and `StakeTransactionData` (oneof case 112, in the sibling `protobuf-schemas` repo) were both added here.

## A stake is a balance

A stake is a per-address amount that changes at a height and stays put until it changes again, which is exactly what a HRTH balance is, so it is stored as one: `Keys.stakeBalance(addressId)` holding `CurrentStake(amount, height, prevHeight)`, plus a `prevHeight`-linked `StakeNode` per change. Nothing is keyed by generation period.

**The period is derived from the change height, not stored.** A StakeTransaction always names the period after the one it lands in, so an amount written at height `h` is in force for every period starting above `h`:

```
Blockchain.stakeAt(address, period) = the most recent amount set at a height < period.start
```

That one rule is the whole design, and every required behaviour falls out of it:

- **the last one wins.** Several StakeTransactions in one period are several writes to one key; the last one is what the key holds. There is no fold, no ordering rule and no dedup anywhere.
- **a stake stays in force until changed.** The value persists on its own. **Nothing carries it forward**, because it was never filed under a period to begin with, so a period boundary writes no stake state at all.
- **HRTH is reserved immediately.** `lockedStake` is `max(stakeAt(current), stakeAt(next))`. Lowering a stake mid-period leaves the old, larger amount in force for the current period (its height is below that period's start) while the key already holds the new one, so the lock does not move until the period ends and the two converge.
- **`amount == 0` releases at the start of the next period.** A release is just a write of 0, in force from the next period on, and subject to the same `max`.
- **a stake earns only from the next period on.** The payout and the forging-weight charge both ask for the period they are in, which by construction excludes anything set during it.

This replaced two earlier designs, and the history is worth keeping because both looked reasonable. The first stored a per-address `StakeRecord(active, pending)` plus a separate whole-set `stakers` blob; three reviewers found the blob, which made every membership change an O(stakers) rewrite and - since the state hash is computed per transaction - made a block of `k` joins cost O(k · stakers) to hash. The second keyed entries by generation period, like `committedGenerators`: that removed the blob and made the period's own rows the enumerable set, but it needed a carry-forward at every boundary to keep a stake alive, a "last wins" fold shared across three read paths, a two-period write with its own rollback case, and it left point lookups scanning the period's set (a Critical in the second review round). Storing a balance as a balance deletes all of it.

`periodStart` stays on the wire and is still validated against the next period's start, because it is what the sender signed - but the storage layer records only the amount and the height.

## Staking costs forging weight, not just liquidity

`Portfolio.staked` is shaped like the existing `generationDeposit`: a view field, filled in by `Blockchain.hearthPortfolio` from the stake ledger rather than accumulated by any diff, and `StateSnapshot.balances` ignores it. Unlike `generationDeposit` it also costs forging weight - the project decision is that staked HRTH stops counting toward it, so staking and mining compete for the same embers. This is the one part of the feature that changes a consensus rule rather than extending state.

**A stake is a lock, and forging weight follows the lock.** "Takes effect at the next period" describes only when a stake starts *earning*. What it locks is `lockedStake` = the larger of this period's stake and the next one's, from the block that raises it; a cut or a release unlocks only at the next period start. Spendable, effective and generating balance all subtract that same lock, exactly like a generation deposit, which is likewise locked at its transaction's height and released at a period start. The generating balance is therefore a plain minimum over the window of `regular + leaseIn - leaseOut - generationDeposit - staked`, where `staked` is the lock at each height. So a released stake regains forging weight only as the window moves past the period it was locked through, like any other credit.

`BalanceSnapshot` carries the lock as `staked`, with no default, so a new constructor cannot leave it out. `RocksDBWriter.balanceSnapshots` derives its history (`collectStakeLocks`) from the stake key's `prevHeight`-linked `StakeNode` chain: the lock changes at stake writes and at period starts, so it walks the writes back to the last one before the period `from` falls in and evaluates the lock at each write and each period start. `withStakeLocks` then folds those change points into the deposit's, so `merge3` still merges three histories. An address that never had a stake in the window gets exactly the snapshots it got before. `SnapshotBlockchain` fills the liquid block's snapshot from `lockedStake`.

**One rule, two readers.** The tip (`Blockchain.lockedStake`) and every stored height (`collectStakeLocks`) both resolve through `StakeLock`, and "derives the same lock from stored history as it did for the liquid block" checks the two against each other at every height of a stake/restake/release run. Forging weight that disagreed across the liquid block would be a fork.

**The walk is attacker-sized, so it is cached and linear.** An address can restake every block, so the walk is up to one period plus the window long (11,000 nodes on MAINNET), and it runs for every generating balance of a committed staker and for `/addresses/balance/details`. Nodes go through `stakeAtHeightCache` (invalidated on rollback like the balance and lease node caches), lookups are `TreeMap.rangeTo` rather than scans, and `resolveStake`, `loadStakes` and `collectStakeLocks` share one walk (`stakeWrites`). The walk starts from the stake key read in the same `readOnly` view, not the stake cache, which can be ahead of it.

There is no zero clamp on the result: `slice` cuts each history to the value in force at `from`, so every merged snapshot is a consistent state, and validation keeps the lock covered by the balance. A negative generating balance would mean a bug in the lock history, which a clamp would hide.

Two designs came first. The original charged forging weight with the stake in force for the current period, subtracted from the window's minimum, and the security audit (Pass 2) had rejected charging the lock: a committed generator could cut its own generating balance mid-period with one transaction and no fund movement, moving `EndorsementFilter`'s 2/3 denominator, which is recomputed for every block from generating balances. That lever already exists: leasing out keeps ownership, lowers the windowed minimum at once, and takes the window to recover, which is exactly what a stake does under the lock rule. So the objection does not single staking out. Subtracting a current-period value from a windowed minimum was also wrong at every boundary: a stake released for the next period counted in full from its first block. A short-lived second version netted each period's slice of the window against that period's stake, which fixed the boundary but still ignored the lock until the stake began earning.

Pinned by `StakeTransactionDiffTest`: "takes a stake out of the sender's generating balance as soon as it is locked", "keeps a released stake out of the generating balance until the window has passed it", "keeps charging the larger amount after a stake is lowered mid-period", and the storage-level "records the lock in balance snapshots from the block that sets it to the period start that releases it".

`/addresses/effectiveBalance`, `Blockchain.effectiveBalance` and `unstakedEffectiveBalance` were removed along with this; the window lives in `GeneratingBalanceProvider.windowedBalance`. `balanceDetails.effective` is still `Portfolio.effectiveBalance`, the same lock-netted value at the tip alone, not forging weight.

Enforcement of spendability is `BalanceDiffValidation`, which folds the stake into the same "locked" term the generation deposit already used, maxing any stake this snapshot restates for the next period against what the current period already locks - a transaction can raise the lock but never lower it. The two locks are summed with `safeSum`, not `+`: `stakedAfter` comes straight off a transaction and is bounded only by `TxNonNegativeAmount`, so a raw sum could wrap negative and turn every check below it into a pass. Two new messages distinguish the two locks - `not enough funds to stake` and `trying to spend a stake` - and the existing deposit messages are unchanged byte-for-byte, because several suites assert on them.

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

## The cred asset

`FunctionalitySettings.credAsset` (base16, `Option`, defaulted to `None`) names the asset the payout mints. It lives there rather than in a new per-network `CredSettings` object specifically because `FunctionalitySettings`' fields all have defaults, the way `daoAddress` does - so **no CUSTOM config template needed migrating**, unlike `RewardsSettings`' own rollout, which had to touch `custom-defaults.conf`, `network-defaults.conf`'s `devnet` alias, `docker/private/hearth.custom.conf` and `node-it/src/test/resources/template.conf` or fail config parsing outright (see "CUSTOM-network config files" in `docs/notes/economics.md`). Anything added here later that does *not* have a default reopens that whole migration.

TESTNET points at the existing genesis ORCRED asset (`PredefinedSnapshotSettings.TestnetORCRED`, which stopped being private for this). MAINNET and STAGENET name none, so their payout does nothing - they have no Cred asset yet, and a placeholder id would be worse than an absent one. Both halves are checked before anything is paid: an unset setting and a configured id that no predefined snapshot ever issued both pay nothing rather than failing every boundary block. Stakes are still activated either way - only the payout is skipped, never the activation, or a network without a Cred asset could never release a stake.

## Storage

Two `KeyTag`s, shaped exactly like a HRTH balance:

```
Keys.stakeBalance(addressId)           -> CurrentStake(amount, height, prevHeight)
Keys.stakeBalanceAt(addressId, height) -> StakeNode(amount, prevHeight)
```

Rollback is `rollbackStake`, a transcription of the existing `rollbackBalanceHistory`: if the current entry was written at the height being rolled back, restore it from the node at `prevHeight` and delete that node. No history lists, no per-height key index, no period special-cases.

Reads come in two shapes, deliberately separated because their costs differ by orders of magnitude:

- `stakeAt(address, period)` is the hot one - every HRTH balance check resolves it twice, through `lockedStake`, for every address of every transaction. One cached read in the common case (`current.height < period.start`, i.e. an untouched stake), falling back to a walk back over restatements made *inside* that period. The walk is as long as the number of times that address restaked during the period, which is bounded by its own fee spend and paid only by its own lookups.
- `stakes(period)` enumerates, by prefix scan over the current-stake keys, and is called **once per period boundary** by `StakingPayout` and nowhere else.

The cache is a per-address `LoadingCache[Address, CurrentStake]`, sized and invalidated like `leaseBalanceCache`. Note what is *not* here: no period is ever materialised in memory, which is the memory cost the period-keyed design carried.

**Enumeration goes through a candidate index, not the stake keys themselves.** A balance key is never deleted when it reaches zero, so scanning `StakeBalance` would walk every address that has ever staked. `Keys.activeStake(addressId) -> lastChangeHeight` is scanned instead, and an entry is kept while the address either still holds a stake or changed one during the current period.

That second half is the subtle part, and getting it wrong underpays people. "Currently staking" is the wrong predicate: an address that releases mid-period is owed a payout for the period it was still staked in, and deleting its entry on the release would drop it from the very boundary that settles it. Keeping it one period longer is what the stored height decides, with no need to read the stake to make the call.

The index is a hint, not consensus state. The payout resolves every candidate through the stake history anyway, so an extra entry costs one read and filters to nothing - only under-inclusion could change a result. That means pruning need not be rollback-exact. `rollbackStake` restores the entry precisely anyway, since it already has the value to restore it from and two lines is cheaper than reasoning about drift between a node that rolled back and one that never saw the block.

Pruning runs in `doAppend` at a period boundary, which is after the payout has read the set for that same block - the order matters, and it is the order `BlockDiffer.mkInitialSnapshot` (payout) then `Caches.append` (prune) already gives.

`TxStateSnapshotHashBuilder` hashes `tag("stake") ++ address ++ periodStart ++ amount` - the transaction's own declared fields, the way `nextCommittedGenerators` hashes a commitment's rather than the deposit it implies. `periodStart` is in the preimage because it is what the sender signed, even though storage derives the same thing from the height.

`loadStakes` throws rather than skipping when an `AddressId` has no address, matching `loadCommittedGenerators`: this is consensus state, and a silently dropped staker changes a spend lock, a forging weight and a payout share while the node keeps following a chain it now computes differently from its peers.

**Not mirrored onto the BlockchainUpdates stream**, and **not carried in the light-node snapshot wire format**. `nextStakes` appears in neither `events.StateUpdate` nor `PBSnapshots`, the same gap `workDone`, `reservedAmounts`, `apiKeyBindings` and `registeredEnclaves` all already have - `PBSnapshots` carries none of this fork's own ledgers, while `TxStateSnapshotHashBuilder` hashes all of them. A node rebuilding a per-transaction snapshot from network-supplied `txSnapshots` computes a state hash that does not match, so the block is rejected: a loud stall, not silent divergence. Pre-existing and systemic, but `Stake` is the first *permissionless* transaction type able to reach it. Closing it needs a `protobuf-schemas` change of its own.

## Testing

Split in two along the line of what a fixture can produce. `StakeTransactionDiffTest` drives everything through a real domain, since nothing about the transaction or the locking needs state a transaction cannot write. `StakingPayoutTest` cannot: `workDone` is written only by `SettleTransactionDiff`, and no fixture in this repo can drive a `StartBoost` to its accept path (see "Testing" under "DCAP collateral registry" in `docs/notes/hearth-transactions.md`), so the work, the committee and the periods' stakes are injected through `WithState.blockchainWithCommitteeWork`/`blockchainWithStakes` and `StakingPayout` is called directly - the same technique `GeneratingBalanceProviderTest` and `SettleTransactionDiffTest` use.

Two fixture traps cost real time getting `StakeTransactionDiffTest` green, both worth knowing before writing another period-crossing test:

- **A transaction is validated against the period of the block that carries it, not the tip's period.** `BlockDiffer` builds a `SnapshotBlockchain` at `height + 1`, so a `CommitToGenerationTransaction` for the next period is already too late in the boundary block itself - "the next period" has moved on by one there. The test's `generationPeriodLength = 4` exists purely so the block after a boundary block is still inside the same period, leaving room for both the commitment and the stake transactions to land before the next boundary.
- **The staker must not be the miner.** A miner's balance grows by the block reward and its fee share on every block it forges, so any balance assertion against it silently depends on how many blocks the case happened to append. `defaultSigner` mines and `signer(11)` stakes, funded separately.
