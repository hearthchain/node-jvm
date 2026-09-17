---
purpose: Implementation notes for the Cred economy (HRTH staking via StakeTransaction, the per-period payout to stakers, the cred asset setting)
---

# Cred economy: staking and the per-period payout

The consumption half of the Cred lifecycle already existed before this: `ReserveTransaction` locks an asset against a registered TEE miner, `SettleTransaction` retires it against an enclave-signed cumulative counter and credits the serving node `phi_n = 0.30`, and `SettleTransactionDiff.BurnedWorkPart` turns `phi_b = 0.60` into the `workDone(validator, period)` signal. See "Settle: retiring reserved funds" and "workBoost" in `docs/notes/hearth-transactions.md` for all of it. What this adds is the other end: somewhere for that tracked work to go, and a reason to hold HRTH.

`StakeTransaction` is the sixth of the stub transaction types from "Transaction schema" in `docs/notes/keys-and-signatures.md` to grow real semantics, and the first new one added since - it has no stub predecessor, so `TransactionType.Stake` (id 12) and `StakeTransactionData` (oneof case 112, in the sibling `protobuf-schemas` repo) were both added here.

## A stake is a stake *for a period*

There is no per-address stake record and no "current stake". `Blockchain.stakes(period)` is the whole set staked for one generation period, and that is the only shape the ledger has. `StakeTransaction` records exactly its own two fields, against the sender, under the period it names - nothing is derived, and nothing already on chain is read to write it. Every required behaviour falls out of the keying:

- **the last one wins.** Several StakeTransactions in one period all name the same next period, and a period's entries are folded with later ones replacing earlier (`Stake.applied`), so the last one applied is simply the one in force. Nothing detects or rejects the earlier ones.
- **HRTH is reserved immediately.** `Blockchain.lockedStake` is the larger of this period's stake and the next one's - the same "this period and the next" window `generationDeposit` counts a generator's deposits over. Raising a stake locks the new amount as soon as the transaction applies, because it already counts for the next period.
- **`amount == 0` releases at the start of the next period, not now.** A zero entry for the next period leaves this period's larger stake as the `max`, so nothing is freed until this period ends.
- **a stake earns only from the next period on.** The payout and the forging-weight charge both read the period they are in, and a transaction only ever writes the next one.

**A stake stays in force until a transaction changes it, which period keying alone does not give you**, since a period holds only what was staked *for* it. `StakingPayout.carriedForward` closes that: at each boundary every surviving stake is restated for the period now starting, skipping any address that already has an entry there (it sent a StakeTransaction, and that is precisely the stake that supersedes the old one, including when it staked 0) and skipping zero amounts (a released stake is not resurrected). This is the one place the `committedGenerators` analogy breaks: a generator re-commits every period, a stake does not have to.

`periodStart` has to name the next period exactly, checked the same way and with the same message as `CommitToGenerationTransactionDiff`'s. A stake for an arbitrary later period would sit unreachable behind the carry-forward, and one for the current period would contradict "starting with the next epoch".

`amount` is a `TxNonNegativeAmount`, not the `TxPositiveAmount` every other transaction's amount is, because 0 is the *unstake*. Same on the wire: `StakeTransactionData.amount` is a bare `int64` rather than an `Amount` message, since a stake is always HRTH. `ProtoVersionTransactionsSpec` pins that a zero amount survives the round trip rather than reading back as an absent field.

## Staking costs forging weight, not just liquidity

`Portfolio.staked` is shaped like the existing `generationDeposit`: a view field, filled in by
`Blockchain.hearthPortfolio` from the stake ledger rather than accumulated by any diff, and `StateSnapshot.balances`
ignores it. Unlike `generationDeposit` it is subtracted from *both* `spendableBalance` and `effectiveBalance` - the
project decision is that staked HRTH stops counting toward forging weight, so staking and mining compete for the
same embers. This is the one part of the feature that changes a consensus rule rather than extending state.

**Spendability and forging weight read different periods, and that split is load-bearing.** Spendability follows
`lockedStake` = the larger of this period's stake and the next one's, so a raised stake is unspendable at once.
Forging weight (`GeneratingBalanceProvider.unstakedEffectiveBalance`) follows `stakedForPeriod` = *this* period's
stake alone, which is the amount the address is also earning on, so the same embers buy the yield and pay for it.

Charging forging weight on the lock instead was the first implementation and is wrong, caught in review (security
audit, Pass 2). This period's stake cannot change once the period has started, so forging weight changes only where
the committee itself does. The lock moves the instant a transaction lands, which would
let any committed generator zero its own generating balance mid-period for the price of one Stake transaction and
no fund movement at all - a lever over `EndorsementFilter`'s 2/3 quorum denominator, and a way to reach the
`validGenerators.nonEmpty` case in `appender.findBlockAndGetGenerators` (its own TODO) without moving funds or
waiting out the 1000-block window. The HRTH a raised stake locks is still unspendable in the meantime; it just
keeps counting as skin in the game until the period it was staked for actually starts.

That is also what makes it safe to subtract a *current* value from a *windowed* `effectiveBalance`: a period's own
stake is constant for that whole period, so there is no window for it to disagree with. `math.max(0L, ...)` clamps the result,
which is genuinely reachable - an address credited inside the 1000-block window and staking most of it has a
windowed minimum far below its stake.

Reading the stake off `blockchain` rather than as of `blockId` resolves to the same state because every consensus
caller hands in a blockchain positioned at the block it is asking about (`appender.appendBlock` does it explicitly,
with `blockchainUpdater.referencedBlockchain(block.header.reference)`). That is an invariant, not a coincidence, and
`workContext`'s own `workDone` read already depends on it - but it is not enforced by a type, so a future caller
passing a `blockId` older than the tip would need the stake resolved at that height instead.

Keeping the stake out of `BalanceSnapshot` and the `prevHeight`-linked node chain (`CurrentBalance`/`BalanceNode`,
`Keys.hearthBalanceAt`) that `balanceSnapshots` walks is what that buys.

Enforcement of spendability is `BalanceDiffValidation`, which folds the stake into the same "locked" term the
generation deposit already used, maxing any stake this snapshot restates for the next period against what the
current period already locks - a transaction can raise the lock but never lower it. The two locks are summed with `safeSum`, not `+`: `stakedAfter` comes straight off a
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

One ledger, one `KeyTag`, shaped exactly like `committedGenerators`:

```
Keys.stakes(stakePeriod, stakeHeight) -> Option[Seq[(AddressId, Long)]]
key = Stakes ++ Int(period.start) ++ Int(height)
```

An append-only log filed under the period a stake is *for*. Reading a period is one prefix scan with the height suffix dropped (`RocksDBWriter.loadStakes`), folding entries in height order so later ones replace earlier; rolling a height back is one `delete` per period key; writing costs only what changed at that height. **The period's entries are also the enumerable set** `StakingPayout` walks, so there is no second ledger recording who the stakers are.

That is the whole point of the keying, and it replaced a first implementation that stored a per-address `StakeRecord(active, pending)` plus a separate whole-set `stakers` blob. Three reviewers independently found the blob: it made every membership change an O(stakers) read and rewrite, and - because the state hash is computed *per transaction* - it made a block of `k` joins cost O(k · stakers) to hash, on a set anyone can grow for one fee and one ember. None of that survives period keying.

A block can write **two** periods at once, which the `committedGenerators` template does not do: the first block of a period carries both the carry-forward (for the period just starting) and any Stake transactions in it (for the one after). So `Caches` groups the snapshot's entries by the period each names, and rollback deletes both `stakes(currentPeriod, h)` and `stakes(currentPeriod.next, h)`.

`Stake.applied` is the single definition of "a later entry replaces an earlier one", shared by the three places that resolve a period independently: `loadStakes` folding what is on disk, `SnapshotBlockchain` layering a not-yet-persisted snapshot over it, and `Caches` keeping a warm period current. Deduping only on the way to disk is not enough, and missing one of the three is a real bug - three stakes in one block read back as three entries from the liquid snapshot until this was shared.

The cache mirrors `committedGeneratorsCache` exactly, including the "only this and next period" restriction, which the payout stays inside: at the boundary block the chain is still positioned at the last block of the finished period, so the period being paid out is "current" and the one being carried into is "next".

`TxStateSnapshotHashBuilder` hashes `tag("stake") ++ address ++ periodStart ++ amount` - a transaction's own declared fields, the way `nextCommittedGenerators` hashes a commitment's rather than the deposit it implies. `periodStart` is in the preimage because the same address and amount mean different things for different periods, and because it is what the sender signed. Nothing about membership is hashed separately: an amount of 0 is a release and anything else is a stake, so a period's set is a function of these entries.

**What is still O(stakers):** the boundary block pays out and carries forward every stake, so it is the most expensive block of each period, and its cost is set by how many addresses have staked. There is no minimum stake and no cap, a deliberate project decision. Nothing pages it; a minimum stake or a real fee for `TransactionType.Stake` (still `1 // TODO: decide` in `FeeConstants`) would price it.

**Not mirrored onto the BlockchainUpdates stream**, and **not carried in the light-node snapshot wire format**. `stakes` appears in neither `events.StateUpdate` nor `PBSnapshots`, which is the same gap `workDone`, `reservedAmounts`, `apiKeyBindings` and `registeredEnclaves` all already have - `PBSnapshots` carries none of this fork's own ledgers, while `TxStateSnapshotHashBuilder` hashes all of them. A light node rebuilding a per-transaction snapshot from network-supplied `txSnapshots` therefore computes a state hash missing those entries. Pre-existing and systemic rather than introduced here, but `Stake` is the first *permissionless* transaction type to reach it: any user can now broadcast a transaction that trips it, where before it took a `Reserve` or `Settle`. Closing it needs a `protobuf-schemas` change of its own.

## Testing

Split in two along the line of what a fixture can produce. `StakeTransactionDiffTest` drives everything through a real domain, since nothing about the transaction or the locking needs state a transaction cannot write. `StakingPayoutTest` cannot: `workDone` is written only by `SettleTransactionDiff`, and no fixture in this repo can drive a `StartBoost` to its accept path (see "Testing" under "DCAP collateral registry" in `docs/notes/hearth-transactions.md`), so the work, the committee and both periods' stakes are injected through `WithState.blockchainWithCommitteeWork`/`blockchainWithStakes` and `StakingPayout` is called directly - the same technique `GeneratingBalanceProviderTest` and `SettleTransactionDiffTest` use. Injecting *two* periods is what lets a case express "staked for the finished period" and "already restated for the starting one" independently, which is the whole of the carry-forward's behaviour.

Two fixture traps cost real time getting `StakeTransactionDiffTest` green, both worth knowing before writing another period-crossing test:

- **A transaction is validated against the period of the block that carries it, not the tip's period.** `BlockDiffer` builds a `SnapshotBlockchain` at `height + 1`, so a `CommitToGenerationTransaction` for the next period is already too late in the boundary block itself - "the next period" has moved on by one there. The test's `generationPeriodLength = 4` exists purely so the block after a boundary block is still inside the same period, leaving room for both the commitment and the stake transactions to land before the next boundary.
- **The staker must not be the miner.** A miner's balance grows by the block reward and its fee share on every block it forges, so any balance assertion against it silently depends on how many blocks the case happened to append. `defaultSigner` mines and `signer(11)` stakes, funded separately.
