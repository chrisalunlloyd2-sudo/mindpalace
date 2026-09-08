# LABELS.md — canonical issue/PR labeling

> Two axes: **area** (where the work lives) and **priority** (how soon).
> Every issue gets exactly one area label + one priority label. The area
> maps 1:1 to ARCHITECTURE.md packages; priority feeds the --progress
> scorecard and FEATURE_BACKLOG tiers.

## Priority (pick exactly one)

| Label | Meaning | SLA |
|-------|---------|-----|
| `high` | blocks a milestone, CME-class crash, or data-loss risk; or an active regression | current week |
| `medium` | real user value, no blocker, in the next milestone window | current month |
| `low` | nice-to-have, polish, or parked-someday with a named trigger | no SLA — revisit at milestone planning |

Priority is set by impact on a metric (SUCCESS_METRICS), not by loudness.
A crash with zero players is still `high`; a shiny feature nobody asked
for stays `low`.

## Area (pick exactly one — the owning package)

| Label | Area | Maps to |
|-------|------|---------|
| `area/ux` | what the player sees and touches | ui/, entity/, controls, HUD, editor UX |
| `area/perf` | frame time, memory, draw calls | render/, LOD, caches, budgets (PERFORMANCE.md) |
| `area/infra` | build, installer, releases, crons, collision/world plumbing | scripts/, maven, cascade, OutsideWorld colliders |
| `area/docs` | ARCHITECTURE/DEV_SETUP/README/roadmap accuracy | every .md + the templates |
| `area/agents` | SLM cognition, quorum, DePIN, tools | agent/, agent/sims/, economy/ |
| `area/world` | rooms, hallways, rotor, courtyard, forest | world/ |
| `area/audio` | sound, music, GA evolution | audio/, genetics/ |

Cross-area issues take the area where the FIX lands, not where the
symptom shows (a crash in render from a world-mutation bug = `area/world`).

## Type (optional third, when obvious)

`bug` / `enhancement` / `documentation` — GitHub defaults, kept.

## Automation contract

- The `hypothesis-step` template already pre-assigns
  `autonomous-program`; its step metadata names the area — labelers add
  priority only.
- `--progress` reports open `high` issues count as a milestone-health
  row (queued, step 149).
- A `high` with no area label after triage is a process bug — the
  scorecard counts unlabeled-highs as MISS.
