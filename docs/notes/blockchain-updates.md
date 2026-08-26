---
purpose: Implementation notes for the BlockchainUpdates stream extension (reserves and settlements mirroring, StateUpdate wiring)
---

# BlockchainUpdates stream

## Reserves and settlements on the BlockchainUpdates stream

`events.StateUpdate` mirrors only a subset of `StateSnapshot`; every hearth-specific field it doesn't name is
silently dropped from the event stream. Two of them are now carried: `reserves`/`settlements`
(`StateUpdate.ReserveUpdate`/`SettleUpdate`, protobuf `StateUpdate.reserves`/`settlements` in the sibling
`protobuf-schemas` repo), mirroring `snapshot.reservedAmounts`/`settledAmounts`. The rest (`workDone`,
`apiKeyBindings`, `registeredEnclaves`, ...) are still dropped - adding one means touching all five places at once:
the domain case class (plus `reverse`, or a ROLLBACK event reports the wrong direction), `fromPB`/`toPB`, the
`Monoid` merge, `atomic`, and **`isEmpty`** - `serde`'s `Some(blockStateUpdate).filterNot(_.isEmpty)` drops the
whole key-block/microblock update when `isEmpty` says so, so a field missing from `isEmpty` makes a block carrying
only that field emit nothing at all.

Both ledgers hold the final absolute total, not a delta, so `atomic` reads each "before" off the pre-snapshot
`Blockchain` (`reservedAmount`/`settledAmount`), the same way it already does for balances, and skips entries whose
before equals their after. Both merge by `(client, operator, asset)`, the key the node's own ledgers use.
`operator` is the address `ReserveTransaction` names in its `miner` field (`RegisteredEnclave.operator`), never the
enclave's `validator` - see "Reserve and BindApiKey" in `docs/notes/hearth-transactions.md` for why conflating the two is a real bug, not a naming
preference.

Testing hits the same constraint as `ReserveTransactionDiffTest`/`SettleTransactionDiffTest`: no fixture can drive
a `StartBoostTransaction` to its accept path, so no real `Reserve`/`Settle` can be appended through a subscription.
`ReserveSettleStateUpdateSpec` instead calls the two diffs directly against `blockchainWithRegisteredEnclave` and
feeds the resulting snapshot to `StateUpdate.atomic`, covering the combine and reverse paths by composing the
per-transaction updates the way `BlockAppended.reverseStateUpdate` does.

`docker/private` now enables the extension (`hearth.extensions`, gRPC on 6881, also `EXPOSE`d by `docker/Dockerfile`).
It keeps its own RocksDB at `<hearth.directory>/blockchain-updates` and refuses to start when its height or last
update id disagrees with the node's, so that directory has to be wiped together with the chain.

