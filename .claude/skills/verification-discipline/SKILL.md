---
name: verification-discipline
description: Verify work before reporting it complete. Use before saying a task is done, before committing, when a command's exit status is being checked, when writing a test assertion, or when summarizing what was delivered.
---

# Verification discipline

Accuracy outranks security, which outranks token cost. An inaccurate report makes the other
two unauditable — nobody can trust a security claim from a process that reports unrun work as
done.

Every rule below comes from a real failure in **this** workspace, dated so it can be checked.

## Claim only what you ran

State one of three things, never a blend:

- "I ran X; it passed" — you saw the output.
- "I ran X; it failed with Y" — say so immediately, do not soften it.
- "I have not run X" — an honest gap beats a confident guess.

**Failure this prevents (2026-08-12):** four dispatched sessions in one day committed their work,
bumped the project version, and in two cases opened a PR, without ever running `./mvnw test`. Three
of those branches were red. One PR body stated a test count that had never been produced. The gate
is not the commit; the gate is the run.

Before reporting done, re-read the original request and answer each point separately. Partial
completion is stated, never averaged away.

## Pipes hide failures

A pipeline exits with the status of its **last** command. `tail` and `grep` almost always succeed:

```bash
./mvnw test 2>&1 | tail -5 && git commit      # commits even when tests fail
```

Use one of:

```bash
set -o pipefail; ./mvnw test 2>&1 | tail -5   # pipeline fails if any stage fails
./mvnw test > /tmp/out.log 2>&1; echo $?      # capture status, then inspect
./mvnw test                                    # no pipes, status propagates
```

**Failure this prevents (2026-08-12):** a loop pushing three branches used
`git push -q ... 2>&1 | tail -1`. One push failed; the loop reported success and continued,
because the exit status belonged to `tail`. `set -e` cannot save you here — it sees the pipeline's
status, not the failing stage's.

## Silence is not success

A command that prints nothing for a long time is indistinguishable from one that has hung. Decide
in advance what progress looks like, and watch *that* rather than the exit code.

**Failure this prevents (2026-08-12):** a test run that normally takes 1 minute sat for **57
minutes** before anyone noticed. The process was alive and the terminal was quiet, so nothing
looked wrong. The tell was on disk: surefire had stopped writing reports after 60 seconds. A
later run of the same change was caught in 4 minutes by watching that instead:

```bash
watch -n60 'ls target/surefire-reports/*.txt | wc -l'
```

When a database is involved, ask it what it is waiting on before theorising:

```sql
select state, count(*), max(now()-state_change) from pg_stat_activity
where datname = current_database() group by state;
select wait_event_type, wait_event, left(query, 80) from pg_stat_activity
where datname = current_database() and state <> 'idle';
```

That single query replaced two confident, wrong diagnoses — "connection exhaustion" twice, when
the answer was a lock held by an `idle in transaction` session.

## Verify a premise before asserting it

Check the actual value before writing an assertion or a finding around it. A claim that is true of
the wrong comparison is worse than no claim, because it is specific and confident.

```bash
git diff origin/main...HEAD    # three-dot: what the branch adds since the merge base
git diff origin/main..HEAD     # two-dot: difference between tips — NOT what merges
```

**Failure this prevents (2026-08-12):** a review reported two BLOCKERs claiming a PR would revert a
merged security fix and delete its regression test. Both came from reading the two-dot diff: `main`
had advanced past the branch's fork point, so main's newer commits rendered as deletions. The
merge-base diff — what GitHub actually merges — contained none of those files. Acting on it would
have "fixed" a revert that did not exist.

## Use absolute paths

The working directory persists between shell calls, and a `cd` inside a compound command relocates
every later call in that session.

**Failure this prevents (2026-08-12):** `cd gastosai-web && …` in one call left the shell there, so
the next call's `gastosai-web/.maestro` resolved to `gastosai-web/gastosai-web/.maestro` and failed
with "no such file or directory". Prefer absolute paths in any command that writes.

## Prove an enforcement mechanism actually fires

A check that has never failed is not known to work. When adding a guard, break the thing it guards
once, confirm it refuses, then restore. Test **both** directions: a guard that never fires and a
guard that always fires are both broken, and the first is silent.

**Failure this prevents (2026-08-12):** permission rules were added denying edits to `.env`. Half
of them — every `Write(...)` entry — were never consulted, because Claude Code only matches
`Edit(path)` against file permission checks. The rules looked right in the file and did nothing.
Probing with a throwaway path surfaced it immediately; reading the file never would have.

The same day, a test asserting two matcher factories agree was proven by rebuilding one on a
different engine and watching the assertion fail with a precise message. A green run alone would
not have distinguished a working test from a vacuous one.

## Run the real gate

```bash
./mvnw test          # full suite; also regenerates contract/openapi.json
```

No pipes, so the status is real. There is no `make` target in this repository — `./mvnw` is the
build. Run it before claiming green and before any commit that touches `src/`.

For a targeted loop while implementing, `./mvnw test -Dtest=ClassName`; the full suite belongs in
the PR, once.

## Correct plainly

When something you said turns out to be wrong and it changes the reader's decisions, say so in one
sentence, fix it, and continue. No apology sequence, no re-litigating how it happened. Silent
correction of a material error is worse than a blunt one.

**Applied (2026-08-12):** a claimed API defect (`/expenses?month=` "silently ignored") was
retracted on the PR and the issue once the endpoint's real contract — `from`/`to`, never `month` —
was checked. The retraction cost two paragraphs; leaving it would have sent someone chasing a bug
that did not exist.
