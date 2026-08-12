---
description: Gate, open the PR, and put it through an independent review loop until it is production ready.
---

# /ship — gastosai-backend

Take the current branch from "I think this is done" to a PR a human can merge.

**Linear issue: $ARGUMENTS** (e.g. `TEN-129`). **Required.** Resolve it before anything else; if
no key was given, or it does not resolve to an issue in the *GastosAI* project, stop immediately:

> `/ship requires a tracked Linear issue. No PR will be opened.`

Do not infer it from the branch name, do not guess from the diff, and never open the PR intending
to link it afterwards. The `Owns` block, the acceptance criteria and the review's scope all come
from the issue — without it the reviewer is judging the change only against itself.

**Full rules: `../docs/ship-loop.md`.** Read it. What follows is only the part
specific to this repo.

## Per pass

1. **Gate** — run the `pre-pr` agent. Red gate: fix, restart the pass, do not review.
2. **Publish** — `gh pr create` (or push to the existing PR), then from the workspace:
   `python3 ../scripts/attach_evidence.py <ISSUE> <file> --caption "..." --pr <n> --repo gastosai-backend`
   to link the PR and attach evidence. Move the issue to `In Review`.
3. **Review** — run the `pr-reviewer` agent with the PR number and the issue key.
4. **Audit** — run the `pr-review-auditor` agent with the reviewer's findings and the PR number.
   **Low-risk changes skip this step**; medium and high always run it. Risk levels and the
   critical-domain list: `../docs/ship-loop.md`. When in doubt, take the higher level.
5. **Decide** — `APPROVE` stops the loop. Otherwise fix the upheld findings and start a new pass.

**Three passes**, and only for high-risk work or while valid blocking findings remain. A fourth is
never allowed — publish what was found and say it did not converge.

## What this repo's gates mean in practice

- **Migrations are expand-only** unless this is the later contract step of a shape already expanded
  and backfilled. State the step in the PR body and confirm `scripts/backup-before-migrate.sh` ran.
  Never edit an applied migration.
- **A changed controller surface means a regenerated contract.** `./mvnw test` rewrites
  `contract/openapi.json`; commit it in the same PR or CI fails on a stale spec.
- **A breaking change is a major version plus `/api/v2` with `/api/v1` kept live.** Publishing a
  break before clients can migrate is a blocker, not a discussion.
- Money is `BigDecimal`, never `float`/`double`. No naive timestamps. No business logic in a
  controller. No provider SDK type in the domain.

## Publish the record

Post the findings-and-resolutions history — every pass, including rejected findings and why — as
both a PR comment and a Linear comment. "Addressed review feedback" is not a record.

Then stop. **Do not merge.** A human does that.
