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

## Tier 1 — READY (next 3 actions, scored, with the WHY)

### 1. Slider fidelity + layout determinism selftest checks — score 25
**Why (user value):** Today, when you scrub to an old commit, you're
trusting that the book you're reading is *actually* that commit's text —
nothing proves it. And when a contributor experiments with a new layout
algorithm, nothing proves the palace didn't silently rearrange itself.
These two checks turn both "trust me" moments into machine-verified
facts: what you read in the past IS the past, and the world you walk is
the world that was built. They also close the last two open rows in
SUCCESS_METRICS — objective tracking becomes fully self-contained.

### 2. Release hygiene + installer QA — score 8.0
**Why (user value):** A new contributor's first 5 minutes are the
installer. Right now we can't prove the exe on Releases matches the code
in main — a stale binary would send every newcomer into bugs we already
fixed, and they'd blame the project, not the cache. After this: one
auditable INSTALLER.md ("ran it fresh, here's what happened"), retired
stale tags, and every future release is installed-verified before it's
announced. This is the item that makes the "good first issue" funnel real.

### 3. CME defensive sweep — score 3.3
**Why (user value):** The one crash class that ever froze the palace for
hours was a ConcurrentModificationException — agent thread mutating a
list while the render thread walked it. We fixed the one instance we
found; three more collections of the same shape exist (rooms, books,
agents, votes). Each is a future random freeze: the game just dies,
the scout says "game down", and nobody knows why. Sweeping them converts
"occasionally the palace vanishes" into "the palace doesn't do that".

## Tier 2 — SHAPED (spec'd, waiting for slot)

> WHYs in brief: profiling (#4) converts the 60fps budget from a promise
> into a measured fact before anyone optimizes blind. Editor upgrade (#5)
> makes the in-game editor usable for real work instead of peeking.
> Memory fencing (#6) stops Ollama models from starving the game's RAM.
> Live search overlay (#7) turns the console-only search into something
> discoverable by players who never read the docs. Chunked streaming (#8)
> stops the outside world from drawing itself whole when you can only see
> a slice.

| # | Item | Cat | P | C | E | R | Score | Blocker |
|---|------|-----|---|---|---|---|-------|---------|
| 4 | Frame-time profiling pass on HD 510 (BACKLOG 13, feeds the 60fps budget) | performance | 4 | 4 | 2 | 2 | 4.0 | none — needs an idle hour on the box |
| 5 | Editor upgrade: syntax highlight, undo/redo, multi-file (BACKLOG 8, step 118) | menus | 4 | 4 | 3 | 2 | 2.7 | after Tier 1 |
| 6 | Memory fencing: RAM cap + model-unload policy (BACKLOG 14) | performance | 4 | 3 | 3 | 3 | 1.3 | SIMS unload pattern study |
| 7 | Live search overlay upgrade (in-HUD typeahead on /) | gameplay | 3 | 4 | 2 | 1 | 6.0 | after Tier 1 (low risk, nice UX) |
| 8 | Chunked outside-world streaming + weather sync (BACKLOG 16) | performance/visuals | 3 | 3 | 3 | 2 | 1.5 | profiling first (item 4) |

## Tier 3 — PROMOTED / CONTRACTS (multi-day, milestone-gated)

> WHYs in brief: multiplayer (#9) is the Architect's promoted next-week
> ask — shared palaces are the viral feature; but the contract doc comes
> first because the port-probe attempt proved nothing exists to poke.
> BDI-as-player (#10) puts a deterministic zero-LLM agent inside the world
> — the Architect's own idea, and a unique demo of the symbolic-AI stack.
> Quorum push gate (#11) is the governance milestone: agents earn the
> right to push only when 3 voters approve the exact diff — that's what
> makes autonomous contribution safe enough to leave running. Control
> Panel (#12) gives non-technical players a launcher that doesn't require
> reading DEV_SETUP.md.

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
