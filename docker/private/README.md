---
purpose: Build, run and exercise the docker/private Hearth node (private chain id R, genesis and DCAP notes, live StartBoost check)
---

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
- a single pre-funded, pre-committed generator account (`phrth1gxv7se8ueq623ukgwxmesapatdmhay84f0sfk0`, nonce 0 of `wallet.seed`) - the node mines with it immediately and its own wallet controls it from startup: the node generates the wallet's first account itself, so `GET /addresses` already lists it and `POST /addresses` yields nonce 1 onwards, never this one

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

### Why genesis timestamp should stay close to now

A block is stamped `max(lastBlock.timestamp + delay, now - 1 minute)` (`Miner.scala`), so a stale genesis does not make the chain replay the whole gap: the first mined block lands about a minute behind wall clock and the miner then produces blocks as fast as it can (roughly 1.6 s each, ~35 blocks) until block time catches up with wall clock, after which it settles at the configured pace. Observed on 2026-08-21 against a genesis 4.3 days old: height 20 after 30 s, height 42 after 2 min. Transactions timestamped "now" are accepted throughout (the 90 min forward offset covers that minute), so a stale genesis costs nothing for ordinary use, but the burst eats a third of StartBoost's 100-block quote-freshness window (see below), so rebuild genesis (see above) rather than let it drift for long.

## Live TEE-miner cycle check (StartBoost, Reserve, BindApiKey, Settle)

The runbook lives in the miner repo as a live Go test (`internal/nodecompat`, `TestLiveMinerNodeCompat`): the node side is fixed, and the miner reruns the test after its own changes to prove the wire contracts still hold. It drives the whole TEE-miner cycle against a freshly started container on `localhost:6869` over REST: Root CA CRL, then the rest of the DCAP collateral (`UpdateCollateral`, two transactions because every issuer chain is revocation-checked against the Root CA CRL already on chain), `CommitToGeneration` for the next period, two fresh wallet accounts funded by `Transfer` (a client and a distinct operator, so balance asserts see no mining rewards), `StartBoost` from the operator with a real quote, a read of the enclave registry through `GET /blockchain/finality` (`currentRegisteredEnclaves`/`nextRegisteredEnclaves`), then `Reserve` (client locks funds against the operator) and `BindApiKey` (client binds the HPKE api-key envelope to the enclave key). From there the execution-node stack takes over: the test, playing the client, makes metered chat calls to the gateway with the API key inside that envelope, polls the miner's `GET /v1/settlement` until the miner has opened the on-chain envelope, provisioned the key and metered the spend, broadcasts the miner-signed `Settle` batches (two rounds) and replays one as a no-op. It asserts exact balances (the operator earns the 30% node share per settled delta) and the on-chain `reserved`/`settled` counters after every settle; adversarial rejections (rewound counter, over-reservation, wrong-operator signature) stay covered by `SettleTransactionDiffTest`, so the live check is happy-path only.

It needs a fixture directory with `quote.hex` (an Intel-signed TDX quote whose `report_data[0:32]` is this image's genesis block id `98dad961...` and `report_data[32:64]` the enclave key to register) and `collateral/` with the matching Intel PCS material (`rootca.crl.der`, `pck-ca-issuer-chain.pem`, `pckcrl-platform.pem`/`pckcrl-processor.pem` for the CA that issued the quote's PCK leaf, `tcb-signing-issuer-chain.pem`, `tcbinfo.json` for the quote's FMSPC, `qeidentity.json`), all currently valid: validity is checked against the transaction timestamp, so yesterday's genesis is fine but last month's collateral is not. The test mirrors node-side definitions by hand (`StartBoostTransactionDiff.FreshnessWindowBlocks`, the TD report layout in `DcapQuote` for the report_data offset, `TransactionType` ids, `FeeConstants` defaults); those literals are the compatibility surface it pins, and a change to any of them here needs a matching edit there. Start from a fresh volume and run it within the first few minutes: the quote is fresh only while height < 101, and the catch-up burst above spends ~35 of those blocks in the first minute.

Where the fixture comes from: `quote.hex` is a quote minted on a TDX guest with `report_data` built as above (the quote generator only needs the genesis block id and the key; measurements are irrelevant, StartBoost does not check them), and the collateral is fetched from Intel PCS v4 for the quote's FMSPC (`tdx/certification/v4/tcb?fmspc=<fmspc>`, `tdx/certification/v4/qe/identity`, `sgx/certification/v4/pckcrl?ca=platform|processor&encoding=pem`, `sgx/certification/v4/rootcacrl`; the PCK and TCB-signing issuer chains come URL-encoded in the response headers of the CRL and TCB Info calls). Bind the node to loopback: the image's api_key is documented above, so it must never be reachable off-host.

Besides the node it needs the execution-node stack from the miner repo: `docker compose up gateway db` there brings up the LiteLLM gateway with its dev spend ledger on `localhost:4000`, and the identity service runs with the demo seed and settlement wired, e.g. `HEARTH_ENCLAVE_SEED=hearth-integration-demo-seed-001 HEARTH_LITELLM_MASTER_KEY=<master key> ./build/miner -node http://localhost:6869 -litellm http://localhost:4000 -cred-per-usd 1000000000`. The test reads `HEARTH_COMPAT_FIXTURES` (the fixture directory; unset skips the test), `GATEWAY_MASTER_KEY` (required), `NODE_URL`, `GATEWAY_URL`, `MINER_URL`, `CRED_PER_USD` and `PCK_CRL` from the environment.

```
docker rm -f hearth-private-node; docker run -d --name hearth-private-node -p 127.0.0.1:6869:6869 hearth-private-node
HEARTH_COMPAT_FIXTURES=<fixture-dir> GATEWAY_MASTER_KEY=<master key> go test ./internal/nodecompat -v -count=1 -timeout 30m   # in the miner repo; PCK_CRL=processor if the quote's PCK leaf is Processor-CA issued
```

Runs on 2026-08-24 (image from this branch; settlement signed by the miner's identity service, reads served by SettlementApiRoute): two fresh stacks (node, gateway with a clean spend ledger and the dev mock model, miner with the demo seed) gave the same result: collateral in blocks 23-24, StartBoost at 25 (enclave key `4421c67f...` registered for period [1000001, 2000000]), Reserve and BindApiKey in block 26. A first settlement pull then provisioned the client's gateway key (budget-capped to the reservation) with no settlement emitted, eight metered `hearth/mock-demo` calls in two rounds followed: the miner opened the on-chain envelope, metered 2e-4 USD per round and signed cumulative batches of 200000 and 400000 embers (at 1e9 embers/USD), confirmed on chain with the operator earning exactly 30% of each delta (120000 total). The replayed batch confirmed as a no-op (fee only, counters unchanged), and all three forged batches (rewound counter, over-reserve, wrong-operator signature) were rejected at broadcast.
