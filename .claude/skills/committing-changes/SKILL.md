---
name: committing-changes
description: Commit via feature branch + PR; never push hearth-chain, never merge.
allowed-tools: Read, Bash(git status *), Bash(git diff *), Bash(git log *), Bash(git add *), Bash(git commit *), Bash(git push *), Bash(git checkout *), Bash(git branch *), Bash(git fetch *), Bash(git merge *), Bash(git rev-parse *), Bash(gh pr *), Bash(sbt *)
---

## Workflow

1. **Branch check.** The default branch is `hearth-chain`. If on it, switch to a feature branch:
   ```
   git checkout -b <type>/<description>
   ```
   Valid `type` prefixes: `feat`, `fix`, `refactor`, `docs`, `test`, `chore`, `infra`.

2. **Auto-fix before commit.** `sbt scalafmtAll`, then `sbt compilePR` (format check + compile with `-Werror`) for any code change.

3. **Commit & push.**
   ```
   git add <specific paths>
   git commit -m "<subject conforming to rules below>"
   git push -u origin <branch>
   ```

4. **Sync with the default branch** when it moved:
   ```
   git fetch origin hearth-chain
   git merge origin/hearth-chain
   ```
   Resolve conflicts; commit the merge; push.

5. **PR creation** (first push only): `gh pr list --head <branch>`, then `gh pr create --fill` if no PR exists yet. Base is `hearth-chain`.

6. **Branch cleanup** only after the PR has been merged by a human.

## Rules

- **Never push directly to `hearth-chain`.** Always feature branches + PRs.
- **Never merge branches or PRs.** Always let a human merge.
- **Never force-push.** No `--force`, no `--force-with-lease`. Create new commits instead.
- **One logical change per commit.**
- **Commit-message subject**: capital start, imperative mood ("Add", "Fix", not "added"), <= 72 chars, no trailing period, no `Co-Authored-By:` lines.

## Why this discipline

- *No direct push to the default branch*: every change is reviewable, CI gates every merge.
- *No agent-side merge*: the human keeps the merge decision.
- *No force-push*: preserves history; reviewers can trust commit hashes.
- *One logical change per commit*: bisect works; reverts are surgical.

## Cross-references

- `engineering-philosophy`: "Small Steps" and "Investigate, Don't Mask" map to one-logical-change-per-commit and don't-bypass-failing-hooks.
