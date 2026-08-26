---
purpose: Implementation notes for state and blocks (balance snapshots, BlockDiffer preconditions, genesis commitments, predefined snapshots)
---

# State and blocks: snapshots, BlockDiffer, genesis

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

## Genesis commitments

`GenesisSettings` carries three commitments to what the genesis block must come out as. All are optional, and all are
checked in `Block.genesis` — the single place every path builds it (startup's `checkGenesis`, `WithState`, tests):

- `state-hash` pins the snapshot the settings describe, sourced from the height-1 entry of `predefinedSnapshots`
  (`BlockchainSettings.genesisSnapshot`, see "Predefined snapshots" below), not from `GenesisSettings` itself;
- `block-id` pins the header, so it covers the state hash along with `timestamp` and `initial-base-target`;
- `signature` is verified against the header bytes by `validateGenesis`.

The id is the hash of the header and the signature is not part of it, so `block-id` does not pin `signature` and the
two are independent. node-it's `Docker.genesisOverride` used to conflate them (writing the id into `signature`, and
never pinning `state-hash`/`block-id` at all); it now computes and pins all three separately (see "node-it fixtures" in `docs/notes/testing.md`).

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
because there is no issue transaction any more (see "Transaction JSON" in `docs/notes/keys-and-signatures.md`), so a predefined snapshot bundled in a
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

