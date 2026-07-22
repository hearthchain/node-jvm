---
name: shell-discipline
description: Shell discipline. One command per call, no inline env vars, gh auth for accounts.
---

## Shell Commands

- **One command per call**: keep commands small, readable, and atomic. Don't chain with `&&`, `;`, or `cd dir && command`. Use separate calls: first `cd`, then the command.
- **No inline env vars**: don't use `VAR=value command`. Set env separately or use proper auth tools.
- **sbt batches are the exception**: `sbt "node-it/docker;node-it/test"` is one sbt invocation, not a shell chain; prefer it over two JVM startups.

## Git Auth

- Use `gh auth login` / `gh auth switch` to switch GitHub accounts, never prefix with `GH_TOKEN=...`. This repo pushes as `swell-a2a` over HTTPS; verify with `gh auth status` before pushing.

## Why

Each chained command is one opaque action to the permission layer; splitting them gives one auditable tool call per intent. Inline env vars hide configuration in the command line and leak secrets into shell history; explicit auth tools keep credentials in the keyring where they belong.

## Failure modes

- **`gh auth login` fails or token expired.** Re-run `gh auth login -h github.com` interactively, then `gh auth status` to verify. Don't paste the token into a shell command.
- **Account switch needed.** `gh auth switch -u <user>`; re-auth first if that user's token is invalid.
- **Shell aliases that hide what runs.** Spell out the real command so it's auditable.
