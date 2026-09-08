# FIRST_WEEK.md — short-term checklist

> The first-week plan, ordered by leverage. Every item names its issue
> and its proof line. Items already done this cycle are kept for the
> record (checked) — the checklist stays honest.

## Day 1-2 — make the CI claim true

- [ ] **#25 CI smoke workflow**: GitHub Action — JDK 17 → `mvn -DskipTests package`
      → `--demo --selftest` → assert `40 passed, 0 failed` + `Demo fixtures loaded: 12 rooms`
      *Proof: a PR touching data/demo_repos.json gets auto-verified.*
- [ ] **#23** Quick Start `--demo` line — **DONE** (cba13f9, issue closed)
- [ ] **README hero + GIF + How to help** — **DONE** (2749b8f/363c689)

## Day 3-4 — harden the core (Tier 1)

- [ ] **#10 slider fidelity + layout determinism selftest checks** (selftest 40→42)
      *Proof: two new checks in the RESULT line; SUCCESS_METRICS rows close.*
- [ ] **#12 CME defensive sweep** — synchronizedList + snapshot audit across
      rooms/books/agents/votes
      *Proof: stress bot zero CME-class regressions; the freeze class dies.*
- [ ] **#11 installer QA + release hygiene** — retire v1.0.0-coldshot, run the
      installer fresh end-to-end, write INSTALLER.md
      *Proof: latest exe == HEAD, documented fresh-install run.*
- [ ] **Tag v1.2.0-beta1** with the frustum + minimap build once #10/#12 land
      *Proof: GitHub Release with installer asset, release notes = step-log digest.*

## Day 5 — open the outreach gates

- [ ] **Post the concept thread** — tweet 7-post + r/localLLaMA ready in
      docs/OUTREACH_POSTS.md; **gated on #11** (never announce unverified)
      *Proof: posts live; Discussion #21 starts receiving answers.*
- [ ] **#20 clean-machine demo run** (any volunteer's machine — Windows 11 row
      in the environment matrix)
      *Proof: wall-clock time + friction noted in #20.*

## Day 6 — contributor funnel

- [ ] Funnel check: ≥10 open good-first-issues — **DONE** (11 open: #13-18, #22-26)
- [ ] **#13 INSTALLER.md skeleton** (feeds #11's QA)
- [ ] Weekly digest runs: `python scripts/feedback_digest.py --days 7`
      appended to the Sunday review pack
      *Proof: digest log shows the week's issues/discussions + funnel health.*

## Day 7 — review + step-log

- [ ] Sunday review: scorecard (`scout_bot --progress`, 5/5 exit-0 gate),
      digest, SUCCESS_METRICS deltas, next-week Tier 1 ordering
      *Proof: step-log #9 carries the week's evidence entries.*
- [ ] **#24 Win11 gotcha row** if #20's run produced data

## Already done this cycle (for the record)

- [x] Demo fixture mode + `--demo` + zero-network fix (495dc30, a612fb3)
- [x] M2: layout module, API TTL cache, FPS overlay (495dc30)
- [x] M3: commit time slider, activity heatmap (bdffe41)
- [x] Frustum culling + minimap toggle + search gold-flash (51b309b)
- [x] SUCCESS_METRICS + `--progress` scorecard (98f0e97, 04b1ef3)
- [x] Community stack: CoC, SUPPORT, NORMS, LADDER, CONTRIBUTORS, templates,
      PR template, welcome workflow, feedback digest (through 8462996)
- [x] Hosted demo page live (11/11 checks), concept post = Discussion #21
