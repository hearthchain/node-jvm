---
purpose: Build, run and exercise the docker/private Hearth node (private chain id R, genesis and DCAP notes, live StartBoost check)
---

# Hearth private node

Image for developing dApps and other smart contracts on the Hearth blockchain.

## Getting started

Build the base node image first (see [`../README.md`](../README.md), "Building Docker image") - it's tagged `hearth-node` by default, which [`Dockerfile`](./Dockerfile) here builds on top of:\
`docker build -t hearth-private-node docker/private` (from the repository root)

Run:\
`docker run -d --name hearth-private-node -p 6869:6869 hearth-private-node`

Node API documentation: http://localhost:6869/

## Preserve blockchain state

To keep the blockchain state, stop the container instead of killing it:\
`docker stop hearth-private-node`
`docker start hearth-private-node`

## Configuration details

The node is configured with:

- faster block generation (**10 sec** interval)
- feature 1 (`SmallerMinimalGeneratingBalance`) pre-activated - the only transaction/consensus feature this fork currently implements gating for; nothing else is
- custom chain id - **R**, bech32 address prefix `phrth`
- api_key `hearth-private-node`
- a single pre-funded, pre-committed generator account (`phrth1gxv7se8ueq623ukgwxmesapatdmhay84f0sfk0`, nonce 0 of `wallet.seed`) - the node mines with it immediately and controls it from its own wallet: it generates the wallet's first account itself, so `GET /addresses` already lists it and `POST /addresses` yields nonce 1 onwards, never this one

Full node configuration: [`hearth.custom.conf`](./hearth.custom.conf).

### Why the node needs `-Dhearth.hrp`

Nothing in the node picks a default bech32 address prefix (HRP) - it crashes the first time it renders or parses any address unless one is set. [`Dockerfile`](./Dockerfile) sets `-Dhearth.hrp=phrth` via `JAVA_OPTS`; a config-only setup needs the same system property however it launches the node.

### The BlockchainUpdates extension and its own state

`hearth.extensions` enables `tech.hearth.events.BlockchainUpdates`, which streams block/microblock appends and rollbacks (balances, leases, assets, reserves, settlements) over gRPC on port **6881**.

The extension keeps its own RocksDB at `/var/lib/hearth/blockchain-updates`, inside the node's data directory but entirely separate from the node's own state. It refuses to start when the two disagree: a lower extension height than the node's, or a last-update id that does not match the block at the node's height, aborts startup with an `IllegalStateException`. So wipe the two together - dropping the chain while keeping `blockchain-updates/` (or the reverse) leaves a node that will not come up.

## Predefined DCAP collateral at genesis

A `StartBoostTransaction` (TEE miner boosting; root [`CLAUDE.md`](../../CLAUDE.md), "DCAP collateral registry" and "StartBoost" sections) can verify a quote only once Intel DCAP collateral (Root CA CRL, PCK CRL plus its issuer chain, per-platform TCB Info, QE Identity plus its issuer chain) is on chain. A permissionless `UpdateCollateralTransaction` can add it later, or genesis can seed some or all of it, like balances and committed generators, via six `dcap-*` fields in a `predefined-snapshots` entry:

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

Every field is optional and independent, exactly like `UpdateCollateralTransaction`'s own six fields. `dcap-tcb-info` is the one repeated field (a list): a network can have miners on more than one platform model, and each entry's FMSPC is read from its payload, not given separately.

**Only real, Intel-signed collateral works.** `IntelPki`'s Root CA public key is pinned in code, not configurable, so anything not signed by Intel's real DCAP PKI fails verification at genesis exactly as in a live `UpdateCollateralTransaction`: synthetic or placeholder collateral cannot be seeded. Fetch current collateral from Intel's Provisioning Certification Service (PCS), as `node/tests/src/test/resources/dcap/SOURCE.md`'s vendored fixtures were, hex-encode each document, and paste it in.

**Collateral validity is checked against genesis's pinned `timestamp`, not wall-clock time at node startup** (CLAUDE.md, "The genesis-timestamp bug"): `timestamp`/`block-timestamp` must fall inside every document's validity window (`issueDate`/`nextUpdate` for TCB Info/QE Identity, `thisUpdate`/`nextUpdate` for the two CRLs). This tension with `hearth.custom.conf`'s genesis (deliberately timestamped near the last image build so mining starts immediately, see below) is why it ships no DCAP fields: fetch fresh collateral and rebuild genesis together, never graft old collateral onto a freshly-timestamped genesis or vice versa.

Any `predefined-snapshots` change alters the state genesis commits to, so `genesis.signature`/`state-hash`/`block-id` must be recomputed with it, same as changing balances or generators - see the next section.

### Rebuilding genesis (balances, generators, or DCAP collateral)

`GenesisBlockGenerator` (`node/src/main/scala/tech/hearth/GenesisBlockGenerator.scala`, run via `sbt "node/runMain tech.hearth.GenesisBlockGenerator <input.conf> <output.conf>"`) builds balances/generators from seed phrases and computes the matching pinned `genesis`/`predefined-snapshots` block; its `genesis-generator { }` input format is in the file. It does not yet accept the `dcap-*` fields, so a genesis with predefined DCAP collateral is assembled by hand: build a `BlockchainSettings` with the desired `predefined-snapshots` entry (DCAP fields included) and call `Block.genesis(blockchainSettings)` to get the real `signature`/`state-hash`/`block-id` to pin, as `GenesisBlockGenerator.mkGenesisSettings` does internally. `hearth.custom.conf`'s own account (address, generator keys, genesis commitments) came from a throwaway `sbt "node/Test/console"` session this way - regenerate after any `predefined-snapshots` change, or the node fails closed at startup with "Genesis state hash mismatch"/"Genesis block id mismatch" (CLAUDE.md, "Genesis commitments").

### Why genesis timestamp should stay close to now

A block is stamped `max(lastBlock.timestamp + delay, now - 1 minute)` (`Miner.scala`), so a stale genesis does not make the chain replay the whole gap: the first mined block lands about a minute behind wall clock, the miner produces blocks as fast as it can (roughly 1.6 s each, ~35 blocks) until block time catches up, then settles at the configured pace. Observed on 2026-08-21 against a genesis 4.3 days old: height 20 after 30 s, height 42 after 2 min. Transactions timestamped "now" are accepted throughout (the 90 min forward offset covers that minute), so a stale genesis costs nothing for ordinary use, but the burst eats a third of StartBoost's 100-block quote-freshness window (see below), so rebuild genesis (see above) rather than let it drift for long.

## Live TEE-miner cycle check (StartBoost, Reserve, BindApiKey, Settle)

The runbook is a live Go test in the miner repo (`internal/nodecompat`, `TestLiveMinerNodeCompat`): the node side is fixed; the miner reruns it after its own changes to prove the wire contracts still hold. Against a fresh container on `localhost:6869` it drives, over REST: Root CA CRL, then the remaining DCAP collateral (`UpdateCollateral`, two transactions: issuer chains are revocation-checked against the Root CA CRL already on chain); `CommitToGeneration` for the next period; a client and a distinct operator account funded by `Transfer` (so balance asserts see no mining rewards); `StartBoost` from the operator with a real quote; the enclave registry via `GET /blockchain/finality` (`currentRegisteredEnclaves`/`nextRegisteredEnclaves`); `Reserve` (client locks funds against the operator); `BindApiKey` (client binds the HPKE api-key envelope to the enclave key). Then, on the execution-node stack, the test as client makes metered chat calls to the gateway with the API key from that envelope, polls the miner's `GET /v1/settlement` until it has opened the on-chain envelope, provisioned the key and metered the spend, broadcasts the miner-signed `Settle` batches (two rounds), and replays one as a no-op, asserting exact balances (the operator earns the 30% node share per settled delta) and the `reserved`/`settled` counters after every settle. Adversarial rejections (rewound counter, over-reservation, wrong-operator signature) stay in `SettleTransactionDiffTest`; the live check is happy-path only.

The fixture directory holds `quote.hex` (an Intel-signed TDX quote with `report_data[0:32]` = this image's genesis block id `98dad961...` and `report_data[32:64]` = the enclave key to register) and `collateral/` with matching Intel PCS material (`rootca.crl.der`, `pck-ca-issuer-chain.pem`, `pckcrl-platform.pem`/`pckcrl-processor.pem` for the CA that issued the quote's PCK leaf, `tcb-signing-issuer-chain.pem`, `tcbinfo.json` for the quote's FMSPC, `qeidentity.json`), all currently valid: validity is checked against the transaction timestamp, so yesterday's genesis is fine but last month's collateral is not. The test mirrors node-side literals by hand (`StartBoostTransactionDiff.FreshnessWindowBlocks`, the TD report layout in `DcapQuote` for the report_data offset, `TransactionType` ids, `FeeConstants` defaults); that is the compatibility surface it pins, so a change here needs a matching edit there. Start from a fresh volume and run within the first few minutes: the quote is fresh only while height < 101, and the catch-up burst above spends ~35 of those blocks in the first minute.

Fixture provenance: `quote.hex` is minted on a TDX guest with `report_data` as above (the generator needs only the genesis block id and the key; measurements are irrelevant, StartBoost does not check them). Collateral comes from Intel PCS v4 for the quote's FMSPC (`tdx/certification/v4/tcb?fmspc=<fmspc>`, `tdx/certification/v4/qe/identity`, `sgx/certification/v4/pckcrl?ca=platform|processor&encoding=pem`, `sgx/certification/v4/rootcacrl`; the PCK and TCB-signing issuer chains come URL-encoded in the response headers of the CRL and TCB Info calls). Bind the node to loopback: its api_key is documented above, so it must never be reachable off-host.

The test also needs the miner repo's execution-node stack: `docker compose up gateway db` brings up the LiteLLM gateway with its dev spend ledger on `localhost:4000`, and the identity service runs with the demo seed and settlement wired, e.g. `HEARTH_ENCLAVE_SEED=hearth-integration-demo-seed-001 HEARTH_LITELLM_MASTER_KEY=<master key> ./build/miner -node http://localhost:6869 -litellm http://localhost:4000 -cred-per-usd 1000000000`. Environment: `HEARTH_COMPAT_FIXTURES` (fixture directory; unset skips the test), `GATEWAY_MASTER_KEY` (required), `NODE_URL`, `GATEWAY_URL`, `MINER_URL`, `CRED_PER_USD`, `PCK_CRL`.

```
docker rm -f hearth-private-node; docker run -d --name hearth-private-node -p 127.0.0.1:6869:6869 hearth-private-node
HEARTH_COMPAT_FIXTURES=<fixture-dir> GATEWAY_MASTER_KEY=<master key> go test ./internal/nodecompat -v -count=1 -timeout 30m   # in the miner repo; PCK_CRL=processor if the quote's PCK leaf is Processor-CA issued
```

Runs on 2026-08-24 (image from this branch; settlement signed by the miner's identity service, reads served by SettlementApiRoute): two fresh stacks (node, gateway with a clean spend ledger and the dev mock model, miner with the demo seed), same result both times. Collateral in blocks 23-24, StartBoost at 25 (enclave key `4421c67f...` registered for period [1000001, 2000000]), Reserve and BindApiKey in block 26. A first settlement pull provisioned the client's gateway key (budget-capped to the reservation) with no settlement emitted; eight metered `hearth/mock-demo` calls in two rounds followed: the miner opened the on-chain envelope, metered 2e-4 USD per round, signed cumulative batches of 200000 and 400000 embers (at 1e9 embers/USD), confirmed on chain with the operator earning exactly 30% of each delta (120000 total). The replayed batch confirmed as a no-op (fee only, counters unchanged); all three forged batches (rewound counter, over-reserve, wrong-operator signature) were rejected at broadcast.
