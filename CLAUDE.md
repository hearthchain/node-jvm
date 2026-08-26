---
purpose: Canonical agent instructions for the node-jvm repo (AGENTS.md is a thin pointer)
---

# node-jvm: agent instructions

Hearth chain node: a Scala 3 fork of the Waves node (consensus, state, REST/gRPC API, RIDE). sbt multi-module build, JDK 25. README.md covers node quickstart; keep this file short and laconic.

## Build / test

- `sbt compilePR`: clean, `scalafmtCheck`, compile everything (Test included) with `-Werror`; warnings block.
- `sbt checkPR`: `compilePR` + unit tests (`node-tests`, `grpc-server`) + `node/assembly` + docker tarballs. This is what CI (`check-pr.yaml`) runs.
- `sbt node-tests/test`: unit tests only; single suite via `sbt "node-tests/testOnly *SuiteName"`.
- `sbt "node-it/docker;node-it/test"`: integration tests (needs Docker); slow, don't run by default. `node-it/docker`
  builds the image from whatever `node`/`grpc-server` currently compile to; it is not rebuilt automatically, so re-run
  it by hand after touching either module's sources before `node-it/testOnly ...`.
- Sandboxed dev environments: the default Docker builder here cannot do nested overlayfs mounts, so `node-it/docker`
  (or any `docker build`/`docker buildx build` using the default `docker` driver) fails every `RUN` layer with
  `mount source: "overlay", ... err: operation not permitted`, even for a trivial `RUN echo`. Switch to a
  docker-container buildx builder first (`docker buildx create --use` if none exists, or `docker buildx use
  <existing-container-builder>` — check `docker buildx ls` for one already running), then build the image directly:
  `cd docker && docker buildx build --load -t hearth/node-it:latest .`. `sbt node-it/docker` itself always
  uses the default builder and cannot be told to use buildx, so it will keep failing in this environment; run the
  `docker buildx build` command by hand instead (the tarballs it needs are still produced by
  `sbt node-it/docker`'s dependency on `buildTarballsForDocker`, which itself works fine — only the final
  `docker build` step needs the workaround, so letting `sbt node-it/docker` fail once first to produce fresh
  tarballs before the manual `buildx build` is a reasonable way to sequence it).
- node-it test suites run with `-Dhearth.it.max-parallel-suites=N` to cap Docker resource usage (each suite starts
  its own set of containers); pass this as a JVM property on the `sbt` command line, not inside the sbt shell.
  Per-suite failures are deterministic (confirmed identical across parallelism 3 and 6 on the same code), so lowering
  parallelism only helps with wall-clock/resource pressure, not with distinguishing real failures from flakiness.
  Per-suite logs land in `node-it/target/logs/<run-id>/<abbreviated.suite.Name>/`, but the real assertion/exception
  message for a failure is only in the sbt console output (the per-suite `test.log` is DEBUG-level container/HTTP
  traffic and rarely contains the failure reason); grep the sbt output for `*** FAILED ***`/`*** ABORTED ***` and the
  lines immediately following instead.
- `sbt scalafmtAll`: auto-format before committing.
- sbt 2's thin client keeps the server JVM alive across `sbt --batch` runs; a suite that loads the Amazon Corretto crypto provider after a recompile can abort with `IllegalAccessError: ... ZombieClassLoader` (the provider registers once per JVM, from the old layer). `sbt --client shutdown`, then rerun.

## Modules

`node` (core), `node/testkit` (shared test base classes + generators, published), `node/tests` (unit tests), `grpc-server`, `node-it` (Docker integration tests), `node-generator`, `benchmark`, `repl` (cross JVM/JS).

## Conventions

- In-repo skills live in `.claude/skills/`: `scala-conventions`, `engineering-philosophy`, `running-tdd-cycles`, `committing-changes`, `reviewing-changes`. They are the source of truth for code style, TDD, and review; `scala-conventions` is grounded in this repo's actual tooling.
- In-repo review subagents live in `.claude/agents/` (code-reviewer, security-auditor, architect-review, acceptance-auditor). Each runs one pass of `reviewing-changes` in a clean context for an independent, reproducible verdict; `/review` launches all four in parallel and aggregates.
- Comments are short and explain why, not what.
- Every `.md` file carries YAML frontmatter with `purpose:` (SKILL.md files use skill frontmatter, `name:` + `description:`, instead); paragraphs are single unwrapped lines.
- No em-dash (U+2014) in any file.
- GitHub Actions pinned by commit SHA (`@<sha> # vX.Y.Z`).

## PR workflow

- Default branch is `hearth-chain`. Never push to it; feature branch + PR, human merges.
- Before pushing: `sbt compilePR` locally; fix formatting with `sbt scalafmtAll`, never by hand-matching the checker. Then run `/review` and fix Critical/Major findings in the same branch.
- After pushing: watch `gh pr checks` until green; a red check is yours to fix or explicitly hand over.
- On a mistake repeated twice, encode the rule (lint config, test, CI, `docs/notes/` for implementation learnings, or this file for build/conventions/workflow rules only); never just fix the instance.

## Implementation notes

Hard-won implementation knowledge lives in `docs/notes/`, one file per subsystem; the matching file MUST be read before working in its area. New learnings go into the matching topic file plus one trigger line here, never as new always-loaded text in CLAUDE.md.

- Touching StartBoost, Reserve, BindApiKey, Settle, workBoost, or the DCAP collateral registry (UpdateCollateral): read `docs/notes/hearth-transactions.md` first.
- Touching keys or addresses (SigningKey, VrfKey, BLS, bech32), transaction JSON, the protobuf transaction schema, or proof verification: read `docs/notes/keys-and-signatures.md` first.
- Touching fees, the carry, BlockRewardCalculator, EmissionCurve, RewardsSettings, or RewardApiRoute: read `docs/notes/economics.md` first.
- Writing or fixing tests (node-it suites and fixtures, grpc-server specs, node-tests helpers like withDomain/TestBlock/TxHelpers): read `docs/notes/testing.md` first.
- Touching StateSnapshot, predefined snapshots, BlockDiffer, genesis settings, or balance snapshots: read `docs/notes/state-and-blocks.md` first.
- Touching the BlockchainUpdates extension or events.StateUpdate: read `docs/notes/blockchain-updates.md` first.
- Touching build.sbt, sbt plugins or tasks, the crypto/protobuf-schemas dependencies, or doing a package or naming migration: read `docs/notes/build-tooling.md` first.
- Touching docker/private configs or rebuilding its genesis: read `docs/notes/docker-private.md` first.
