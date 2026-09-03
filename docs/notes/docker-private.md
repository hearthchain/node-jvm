---
purpose: Implementation notes for docker/private (genesis config rot, live StartBoost runbook)
---

# docker/private

## docker/private: static genesis configs rot silently

`docker/private/hearth.custom.conf` and similar checked-in genesis configs aren't exercised by CI (unlike node-it's
fixtures, which run every PR), so a migration that changes the config schema or address format leaves them broken
with nobody noticing until someone actually builds and runs the image. Verify by doing exactly that (`docker buildx
build`/`docker run`, real `curl` against the REST API), not by reading the config and reasoning about it. When
rebuilding one after this kind of rot, the account, its generator keys, and the genesis commitments it credits all
need rebuilding together from scratch (old base58 addresses and pre-migration key material don't carry over) - see
`docker/private/README.md`'s "Rebuilding genesis" for the `Block.genesis(...)`-based process.

A stale genesis timestamp is not a catch-up bug: `Miner.forgeBlock` stamps a block `max(parentTimestamp + delay, now - 1 minute)`, so the chain bursts roughly 35 blocks in its first minute and then runs at the configured pace, with transactions timestamped "now" accepted throughout (measurements in `docker/private/README.md`, "Why genesis timestamp should stay close to now"); the cost is a third of the K=100 StartBoost freshness window, so keep genesis reasonably fresh. This is also why predefined DCAP collateral (see "DCAP collateral registry" in `docs/notes/hearth-transactions.md`) can't just be grafted onto a config's genesis: collateral validity is checked against genesis's own pinned timestamp, and a real Intel-signed fixture's validity window won't reach into whatever "now" a rebuilt genesis uses - `docker/private/README.md` documents the mechanism and constraint for whoever fetches fresh collateral and rebuilds genesis to match it.

The node pins its own bech32 HRP from `hearth.blockchain.custom.network-id` at startup, so the private image no
longer needs the `-Dhearth.hrp=phrth` `JAVA_OPTS` its Dockerfile used to carry. `network-id` and the HRP of the
addresses a config credits at genesis have to agree: `phrth` here, and the genesis balances are `phrth1...`.

## Live StartBoost on docker/private

The StartBoost accept path has been exercised end to end against a real Intel-signed TDX v4 quote (`report_data` = the private image's genesis block id ++ a known enclave key) and live Intel PCS collateral. The runbook is a live Go test in the miner repo (`internal/nodecompat`, run against this repo's docker/private stack); `docker/private/README.md` ("Live TEE-miner cycle check") has the fixture contract, measurements and run log. What the code does not say by itself:

- Collateral goes in two transactions: `DcapCollateral` resolves the Root CA CRL for revocation checking from chain state (`blockchain.dcapRootCaCrl`), never from the transaction being applied, so `rootCaCrl` is mined first and the rest (`pckCaIssuerChain` + `pckCrl`, `tcbSigningIssuerChain` + `tcbInfo` + `qeIdentity`) follows in one. The PCK CRL must come from the CA that issued the quote's PCK leaf (Intel publishes a Platform and a Processor one). Formats: CRLs DER, issuer chains PEM, TCB Info / QE Identity the raw signed JSON as served, all hex in the request. Validity is checked at the transaction timestamp, so the collateral must be fresh even when the quote is old.
- `utils.byteStrFormat` writes base16 unconditionally, never `ByteStr.toString`'s `base64:` form at 1024+ bytes, so `/transactions/sign` output round-trips through `/transactions/broadcast` for quotes and collateral blobs (`TransactionFactorySpec`, "round-trips a signed transaction's own JSON").
- `GET /blockchain/finality` carries `currentRegisteredEnclaves` / `nextRegisteredEnclaves` (`FinalityApiRoute`, `FinalityApiRouteSpec`), the per-period shape of `currentGenerators` / `nextGenerators`; a registration always targets the next period.
- The private node generates its wallet's nonce-0 account (the funded generator) at startup: `GET /addresses` lists it and `POST /addresses` returns nonce 1. `CommitToGeneration` via `/transactions/sign` needs only `{"type": 6, "sender": <addr>}`; the node fills the period start and generator keys from `hearth.miner.accounts`.
- The runbook covers the whole TEE-miner cycle: after StartBoost it runs Reserve, BindApiKey and two miner-signed Settle batches plus a no-op replay; adversarial rejections stay in `SettleTransactionDiffTest`. `/transactions/sign` fills the default fee for every type involved except Transfer, whose request demands explicit `fee` and `timestamp`. Settle's genesis prerequisite holds from block 1: the genesis-committed generator counts as a committed generator of the first period (`SnapshotBlockchain`), which `SettleTransactionDiff` requires of the registered validator.
