# FEATURE_BACKLOG.md — prioritized backlog with scoring

> Label contract: every issue carries one **area/** + one **high/medium/low**
> label (docs/LABELS.md). This file's tiers consume the same priorities:
> `high` -> Tier 1 candidate, `medium` -> Tier 2, `low` -> Tier 4.

> Consolidated from: BACKLOG.md (items 5–21), NEXT_100_STEPS.md unshipped
> steps, SUCCESS_METRICS queued checks, and milestone follow-ups. Every
> item carries a priority score, so ordering is defensible, not vibes.
>
> Scoring: P = player value (1–5), C = confidence we can ship it clean
> (1–5), E = effort (1 = hours, 2 = a day, 3 = multi-day), R = risk
> (1 = none … 5 = CME-class or quota-walled). Order by P·C / (E·R).

## Status legend
- DONE — shipped with cascade evidence (step-log #9)
- READY — next up, spec'd, no blocker
- BLOCKED — waiting on another item or external state
- SOMEDAY — real, but no date

## Tier 0 — DONE this cycle (2026-09-07, for the record)

| Item | Origin | Evidence |
|------|--------|----------|
| Chat-repetition loop fix (item 21, was [bugs, high]) | BACKLOG #21 | H02 commit 36bdb69 — bigram-Dice gate + system-prompt directive; chatter pipeline triple-locked |
| Meta-chatter gate | H08 | commit 134d42e, console-verified zero filler broadcast |
| Critic-blind fix (critic reviews real actions) | H01 | commit 108d06b, live console proof |
| Solid world colliders (walk-through buildings) | pre-H13b bug | commit bae3794 |
| Roofs/parapets, forest ×3 | H14/H16 | commit 8e4b476 |
| Demo mode, layout module, API cache, FPS overlay | M1/M2 | commit 495dc30 |
| Commit time slider, activity heatmap | M3 | commit bdffe41 |
| SUCCESS_METRICS + --progress scorecard | metrics ask | commits 98f0e97, 04b1ef3 |

## Tier 1 — READY (next 3 actions, scored)

| # | Item | Cat | P | C | E | R | Score | Note |
|---|------|-----|---|---|---|---|-------|------|
| 1 | **Slider fidelity + layout determinism selftest checks** — book@rev == git show rev:path; same-repos-same-centers | bugs/tests | 5 | 5 | 1 | 1 | **25** | closes the 2 queued metrics; joins the 40-check gate |
| 2 | **Release hygiene + installer QA** (BACKLOG 18+19): retire v1.0.0-coldshot, run installer fresh end-to-end, INSTALLER.md | github/installer | 4 | 4 | 2 | 1 | 8.0 | every release claim then audit-proof |
| 3 | **CME defensive sweep** (BACKLOG 10): synchronizedList+snapshot audit over rooms/books/agents/votes | bugs | 5 | 4 | 2 | 3 | 3.3 | the one bug class that froze the game |

## Tier 2 — SHAPED (spec'd, waiting for slot)

| # | Item | Cat | P | C | E | R | Score | Blocker |
|---|------|-----|---|---|---|---|-------|---------|
| 4 | Frame-time profiling pass on HD 510 (BACKLOG 13, feeds the 60fps budget) | performance | 4 | 4 | 2 | 2 | 4.0 | none — needs an idle hour on the box |
| 5 | Editor upgrade: syntax highlight, undo/redo, multi-file (BACKLOG 8, step 118) | menus | 4 | 4 | 3 | 2 | 2.7 | after Tier 1 |
| 6 | Memory fencing: RAM cap + model-unload policy (BACKLOG 14) | performance | 4 | 3 | 3 | 3 | 1.3 | SIMS unload pattern study |
| 7 | Live search overlay upgrade (in-HUD typeahead on /) | gameplay | 3 | 4 | 2 | 1 | 6.0 | after Tier 1 (low risk, nice UX) |
| 8 | Chunked outside-world streaming + weather sync (BACKLOG 16) | performance/visuals | 3 | 3 | 3 | 2 | 1.5 | profiling first (item 4) |

## Tier 3 — PROMOTED / CONTRACTS (multi-day, milestone-gated)

| # | Item | Cat | Gate |
|---|------|-----|------|
| 9 | Multiplayer client contract (BACKLOG 7, step 147) | gameplay | M5; contract doc before any socket code (the port-probe lesson) |
| 10 | BDI_FSM agent as a player (BACKLOG, Architect 2026-09-01) | gameplay | bridge suite stays green; needs BDI repo cloned into repos/ |
| 11 | Quorum push gate (step 96, M3 of 3-month plan) | github | after slider determinism lands |
| 12 | Control Panel subapp (BACKLOG 9) | subapps | after installer QA (item 2) proves install surface |

## Tier 4 — SOMEDAY (parked, cheap to revisit)

- Spring-damper orbital camera + 6-DOF astral walk (BACKLOG 5)
- Bezier portal conduits + warp-on-teleport (BACKLOG 15)
- SIMS1337 4-tier warm pool + LoRA-on-real-calls (BACKLOG 6 — partially shipped via ModelRouter/ContextKit)
- Backup-to-usb coverage audit (BACKLOG 20)

## Queue rules (why this ordering)

1. **Tests before features**: the two selftest checks sit above every
   feature because they convert 'claimed' into 'machine-verified' — the
   program's own currency.
2. **Score = P·C/(E·R)**: confidence multiplies value; risk divides it. A
   P5 item with CME-class risk (R3) drops below a P4 clean port (R1).
3. **One TASK at a time**: the watcher executes a single TASK_Hxx file;
   a backlog is a source list, never a parallel queue.
4. **Grep before draft** (the doc-drift rule): several BACKLOG items died
   already-done — every new TASK file carries 'verify before build'.
5. **Dropped with evidence**: item 12 (chat logs) dropped after the daily
   logs were confirmed live; multiplayer quick-poke dropped after the
   port scan showed nothing exists. Dropping is a decision, not forgetting.
