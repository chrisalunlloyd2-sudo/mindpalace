# CONTRIBUTION_NORMS.md — how we work here

> The norms a contributor agrees to by opening a PR. CONTRIBUTING.md is
> the *how*; this is the *what to expect and what's expected of you*.
> The step-log (#9) is the public record of every norm kept.

## The five norms

### 1. Evidence before opinion

Every claim ships with its proof. Bug reports carry console tails and
RESULT lines; PRs carry cascade output; milestones carry metrics. The
phrase "trust me" has no authority here. The step-log is append-only —
that's what makes trust portable: strangers can audit anything.

### 2. The cascade is for everyone

Your change goes: `mvn package` (BUILD SUCCESS) → `--selftest`
(40 passed, 0 failed) → visible changes get an E2E waypoint → commit →
push → step-log entry. No exceptions for size ("it's just a docs change"
still needs the build line) and no exceptions for rank (the Architect's
commits follow the same path; so do the agents').

### 3. Small, complete, reviewed

One change per PR, complete (nothing half-wired), and small enough that
a reviewer can verify it in one sitting. If your change needs
"afterwards I'll…" — either land the afterwards first, or scope down.
A finished small PR outranks an unfinished big one, always.

### 4. The backlog is the queue

Work comes from FEATURE_BACKLOG.md (scored, WHYs attached) or the
labeled issue board (`high`/`medium`/`low` × `area/*`). Open a new issue
only if it isn't already tracked — grep first; duplicates waste the
triage the templates were built for. Every issue you fix gets its WHY
met, not just its checkbox ticked.

### 5. Leave it better for the next contributor

A gotcha you hit becomes a DEV_SETUP.md row. A pattern you copied
stays copyable (KISS; match surrounding style). A test that would have
caught your bug gets written. New code names things the way the module
already does. The palace's structure is maintained by everyone who
touches it.

## What maintainers commit to

- **Triage within a week** for labeled issues; PRs reviewed against
  these norms, with reasons stated when something bounces.
- **The step-log stays honest.** If a milestone slips, the slip is
  recorded (SUCCESS_METRICS rule 4) — never silently dropped.
- **Your evidence is safe.** A good bug report with imperfect prose is
  accepted; we fix the template usage, not reject the report.
- **Credit in release notes** for testers (3+ accepted reports) and
  first-PR authors — recorded in [CONTRIBUTORS.md](CONTRIBUTORS.md).

## Decision-making

- Code changes: maintainer review against the cascade; the agents'
  proposed changes additionally require the quorum gate (when live, step 96).
- Direction (roadmap, milestones): the Architect decides, informed by
  the scorecard, the digest, and Discussion #21 feedback. Disagreements
  surface as issues with evidence, not as PR-bombs.
- This document changes only via PR, with the change explained in the
  commit message.