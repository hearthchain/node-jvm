---
purpose: Canonical agent instructions for the node-jvm repo (AGENTS.md is a thin pointer)
---

# node-jvm: agent instructions

Hearth chain node: a Scala 3 fork of the Waves node (consensus, state, REST/gRPC API, RIDE). sbt multi-module build, JDK 17. README.md covers node quickstart; keep this file short and laconic.

## Build / test

- `sbt compilePR`: clean, `scalafmtCheck`, compile everything (Test included) with `-Werror`; warnings block.
- `sbt checkPR`: `compilePR` + unit tests (`node-tests`, `grpc-server`) + `node/assembly` + docker tarballs. This is what CI (`check-pr.yaml`) runs.
- `sbt node-tests/test`: unit tests only; single suite via `sbt "node-tests/testOnly *SuiteName"`.
- `sbt "node-it/docker;node-it/test"`: integration tests (needs Docker); slow, don't run by default.
- `sbt scalafmtAll`: auto-format before committing.

## Modules

`node` (core), `node/testkit` (shared test base classes + generators, published), `node/tests` (unit tests), `grpc-server`, `node-it` (Docker integration tests), `node-generator`, `benchmark`, `repl` (cross JVM/JS).

## Conventions

- In-repo skills live in `.claude/skills/`: `scala-conventions`, `engineering-philosophy`, `running-tdd-cycles`, `committing-changes`, `reviewing-changes`, `shell-discipline`. They are the source of truth for code style, TDD, and review; `scala-conventions` is grounded in this repo's actual tooling.
- Comments are short and explain why, not what.
- Every `.md` file carries YAML frontmatter with `purpose:` (SKILL.md files use skill frontmatter, `name:` + `description:`, instead); paragraphs are single unwrapped lines.
- No em-dash (U+2014) in any file.
- GitHub Actions pinned by commit SHA (`@<sha> # vX.Y.Z`).

## PR workflow

- Default branch is `hearth-chain`. Never push to it; feature branch + PR, human merges.
- All commits must be authored and committed by `swell-a2a <swell_ai@pm.me>`; the repo-local `git config` sets this and the author-guard hook enforces it. After a fresh clone: `git config user.name swell-a2a && git config user.email swell_ai@pm.me && cp .githooks/pre-commit .git/hooks/pre-commit`.
- Before pushing: `sbt compilePR` locally; fix formatting with `sbt scalafmtAll`, never by hand-matching the checker.
- After pushing: watch `gh pr checks` until green; a red check is yours to fix or explicitly hand over.
- On a mistake repeated twice, encode the rule (lint config, test, CI, or this file); never just fix the instance.
