# Hearth private node

The image is useful for developing dApps and other smart contracts on the Hearth blockchain.

## Getting started

Build the base node image first (see [`../README.md`](../README.md), "Building Docker image") - it's tagged `hearth-node` by default, which [`Dockerfile`](./Dockerfile) here builds on top of:\
`docker build -t hearth-private-node docker/private` (from the repository root)

To run the node,\
`docker run -d --name hearth-private-node -p 6869:6869 hearth-private-node`

To view node API documentation, open http://localhost:6869/

## Preserve blockchain state

If you want to keep the blockchain state, then just stop the container instead of killing it, and start it again when needed:\
`docker stop hearth-private-node`
`docker start hearth-private-node`

## Configuration details

The node is configured with:

- faster generation of blocks (**10 sec** interval)
- feature 1 (`SmallerMinimalGeneratingBalance`) pre-activated - the only transaction/consensus feature this fork currently implements gating for; nothing else is
- custom chain id - **R**, bech32 address prefix `phrth`
- api_key `hearth-private-node`
- a single pre-funded, pre-committed generator account (`phrth1gxv7se8ueq623ukgwxmesapatdmhay84f0sfk0`, nonce 0 of `wallet.seed`) - the node mines with it immediately, and its own wallet controls it as soon as it's registered via `POST /addresses` (a fresh wallet's first generated address is always nonce 0)

Full node configuration is available in [`hearth.custom.conf`](./hearth.custom.conf).

### Why the node needs `-Dhearth.hrp`

Nothing in the node itself picks a default bech32 address prefix (HRP) - it crashes the first time it renders or parses any address unless one is set some other way. [`Dockerfile`](./Dockerfile) sets `-Dhearth.hrp=phrth` via `JAVA_OPTS` for exactly this reason; a config-only setup (no Dockerfile) needs the same system property set however it launches the node.

## Predefined DCAP collateral at genesis

A `StartBoostTransaction` (TEE miner boosting, see the root [`CLAUDE.md`](../../CLAUDE.md)) needs Intel DCAP collateral (a Root CA CRL, a PCK CRL plus its issuer chain, per-platform TCB Info, QE Identity plus its issuer chain) already on chain before it can verify a single quote - see CLAUDE.md's "DCAP collateral registry" and "StartBoost" sections for the full design. Collateral can always be added later via a permissionless `UpdateCollateralTransaction`, but a network can also seed some or all of it at genesis, the same way genesis seeds balances and committed generators, by adding the six `dcap-*` fields to a `predefined-snapshots` entry:

```hocon
predefined-snapshots = [
  {
    height = 1
    # ... generators, balances, as usual ...
    dcap-root-ca-crl              = "<hex DER Root CA CRL>"
    dcap-pck-crl                  = "<hex DER PCK CRL>"
    dcap-pck-ca-issuer-chain      = "<hex PEM PCK CA -> Root CA chain>"
    dcap-tcb-info                 = ["<hex signed-JSON TCB Info, one per platform model>"]
    dcap-qe-identity              = "<hex signed-JSON QE Identity>"
    dcap-tcb-signing-issuer-chain = "<hex PEM TCB Signing CA -> Root CA chain>"
  }
]
```

Every field is optional and independent, exactly like `UpdateCollateralTransaction`'s own six fields; `dcap-tcb-info` is the one repeated field (a list, not a single value) since a network can have miners on more than one platform model, and each entry's own FMSPC is read out of its payload, not given separately.

**This only works with real, genuinely Intel-signed collateral.** `IntelPki`'s Root CA public key is pinned in code, not configurable, so a CRL or cert chain that isn't actually signed by Intel's real DCAP PKI fails verification the same way at genesis as it would in a live `UpdateCollateralTransaction` - there is no way to seed synthetic or placeholder collateral. Fetch current collateral from Intel's Provisioning Certification Service (PCS) the same way `node/tests/src/test/resources/dcap/SOURCE.md`'s vendored fixtures were originally obtained, hex-encode each document, and paste it into the fields above.

**Collateral validity is checked against genesis's own pinned `timestamp`, not wall-clock time at node startup** (see CLAUDE.md, "The genesis-timestamp bug") - so genesis's `timestamp`/`block-timestamp` has to fall inside every included document's own validity window (`issueDate`/`nextUpdate` for TCB Info/QE Identity, `thisUpdate`/`nextUpdate` for the two CRLs), not the date the node actually first runs. This is a real tension with `hearth.custom.conf`'s own genesis (deliberately timestamped close to whenever the image was last built, so mining starts immediately - see "Why genesis timestamp has to stay close to now" below), which is exactly why it doesn't ship any DCAP fields itself: fetch fresh collateral and rebuild genesis together, don't graft old collateral onto a freshly-timestamped genesis or vice versa.

Adding or changing any `predefined-snapshots` field changes the state genesis commits to, so `genesis.signature`/`state-hash`/`block-id` have to be recomputed together with it, the same as changing balances or generators would - see the next section.

### Rebuilding genesis (balances, generators, or DCAP collateral)

`GenesisBlockGenerator` (`node/src/main/scala/tech/hearth/GenesisBlockGenerator.scala`, run via `sbt "node/runMain tech.hearth.GenesisBlockGenerator <input.conf> <output.conf>"`) builds balances/generators from a list of seed phrases and computes the matching pinned `genesis`/`predefined-snapshots` block for you - see its `genesis-generator { }` input format in the file itself. It does not yet accept the `dcap-*` fields, so a genesis that needs predefined DCAP collateral currently has to be assembled by hand: build a `BlockchainSettings` with the desired `predefined-snapshots` entry (DCAP fields included) directly and call `Block.genesis(blockchainSettings)` to get the real `signature`/`state-hash`/`block-id` to pin, the same way `GenesisBlockGenerator.mkGenesisSettings` does internally. `hearth.custom.conf`'s own account (address, generator keys, genesis commitments) was produced this way, from a throwaway `sbt "node/Test/console"` session - regenerate it the same way after changing anything under `predefined-snapshots`, or the running node fails closed at startup with a "Genesis state hash mismatch"/"Genesis block id mismatch" error (see CLAUDE.md, "Genesis commitments").

### Why genesis timestamp has to stay close to now

A block's target timestamp is computed as `lastBlock.timestamp + delay` (a PoS-derived delay, independent of wall-clock time) - not from the system clock directly. If genesis's own `timestamp` is old, every block after it inherits that staleness and the miner never waits (its target is already overdue), so the chain mines as fast as it can compute blocks rather than at the configured 10-second interval, and every one of those blocks is still timestamped in the past - meaning a real transaction (timestamped at whatever time it's actually submitted) fails the max-transaction-time-forward-offset check against a chain tip still stuck near the old genesis date, until enough blocks have been mined to physically close the gap. `hearth.custom.conf`'s genesis timestamp needs rebuilding (see above) with each new image build for exactly this reason - don't reuse an old genesis timestamp across a long-dormant rebuild.
