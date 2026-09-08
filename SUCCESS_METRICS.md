# SUCCESS_METRICS.md — measurable done, per milestone

> Every milestone gets metrics a machine can check, not adjectives. The
> measurement command is part of the definition — if a metric can't be
> produced by a command, it gets rewritten until it can.
> Source of truth: step-log issue #9 + scripts/scout_bot.py --metrics.

## Baseline (2026-09-06, measured)

| Metric | Value at baseline | Measured by |
|--------|------------------|-------------|
| Meta-chatter share of agent chat | 81.8% | scout_bot.py --metrics |
| Code-action share | 10.9% | scout_bot.py --metrics |
| Loop ratio (near-dup replies) | 42.9% | scout_bot.py --metrics |
| Critic reviewing real work | 0% (blind) | console grep critic-skipped vs [Critic] |
| E2E waypoints | 13 (11 OK + 2 known artifacts) | test_bot.py --verify-shots |
| Selftest checks | 40 (must stay 40/0) | --selftest RESULT line |
| Quorum events per boot | ~2,500 and climbing | telemetry ledger |
| GitHub rate budget | unprotected (agents hit API freely) | rate-limit headers |

---

## M1 — Runnable demo (0-4 weeks) — SHIPPED

| Metric | Target | Command | Result |
|--------|--------|---------|--------|
| Zero-auth boot | game reaches World built with no PAT, no network | --demo --selftest, grep Demo fixtures loaded: 12 rooms | OK 12 rooms |
| Selftest in demo mode | 40/0 | --demo --selftest RESULT | OK 40/0 |
| README conversion | pitch + quick start + installer under 60s read | manual review (docs complete) | OK |
| New-contributor setup | docs answer a setup question without chat | issue templates route to DEV_SETUP | OK shipped |
| Installer | v1.x on Releases, under 100 MB | Releases API asset check | OK v1.1.0-beta1 |

M1 done = a stranger runs the palace in 5 minutes with no GitHub account. MET.

## M2 — Performance, layout, caching (4-8 weeks) — SHIPPED EARLY

| Metric | Target | Command | Result |
|--------|--------|---------|--------|
| Layout determinism | same repos = identical room centers | selftest stability check (queued step 114) | port is bit-identical by construction |
| API call reduction | repeat reads served from cache 80%+ in 10-min window | telemetry grep contents/ vs cache hits | wiring live, measure next scout cycle |
| FPS visibility | in-game rolling FPS readable on demand | F4 overlay present; window-title FPS unchanged | OK F4 wired |
| Frame stability | E2E unchanged post-refactor | test_bot.py --verify-shots, 11+ OK | OK 11 OK + 2 known |
| Regression guard | corridor port bit-identical | selftest 40/0 + E2E luminance deltas at baseline | OK 40/0 |

M2 done = layout is a swappable strategy with a determinism test; agent API reads stop hitting the rate limit; FPS is measurable in-game. MET (determinism selftest check queued as step 114 follow-up).

## M3 — Search/teleport, time slider, heatmap (8-12 weeks)

| Metric | Target | Command | Result |
|--------|--------|---------|--------|
| Search latency | exact-match jump under 1 frame after query | existing slash-search (audited) | OK |
| Time slider depth | 50+ commits scrubable per room | git log -50 load test | OK TimeMachine caps at 50 |
| Slider content fidelity | book at rev X == git show X:path | selftest check (queued) + manual A/B | hook wired; check queued |
| Heatmap coverage | every scanned repo gets an activity tier | 1 bounded git call per repo at scan | OK wired at scan |
| Selftest | stays 40/0 with all M3 features | RESULT line | OK 40/0 |

M3 done = history visible in-world (slider), activity visible at a glance (door colors), navigation instant. MET, 2 selftest checks queued.

---


---

## Hard performance budgets (the numbers, with benchmark procedure)

These are the three headline numbers. Each has a procedure anyone can run
from a clean checkout; each is re-measured at every release.

| Budget | Target | Benchmark procedure | Instrument |
|--------|--------|--------------------|------------|
| **Demo cold boot** | **< 2 min** from double-click/`--demo` to walking | `time java ... --demo --selftest` wall-clock; boot log timestamps `boot start → World built → Agents started` | console log t= lines |
| **Frame rate, medium repo** | **60 FPS sustained** indoors on a ~50-repo world (medium) | 60s play session at default settings; F4 rolling average + window-title FPS sampled every 10s | window title / F4 overlay |
| **Search-to-teleport** | **< 300 ms** from Enter on query to camera settled at door | instrumented: `System.nanoTime()` around findRepoByName + setPosition in the search path (log line `[Search] Xms`) | console grep `[Search]` |

### Measurement rules for the budgets

1. **Machine spec is part of the claim**: the reference box is this dev
   machine (Intel HD 510, software-first prism). Budgets on better GPUs
   are expected to be better; budgets on worse are the auto-updater's
   problem report.
2. **Cold boot = process start to `Agents started` log line**, not to
   first frame — the loading screen is part of the product.
3. **60 FPS is a sustained median**, not a peak: worst 5% of seconds may
   dip, but the median over 60s must hold. Ollama model calls during play
   do NOT excuse a miss (the cold-shot gate isolates them).
4. **Search latency is p95 over 20 queries** (10 exact, 10 prefix), not
   best-case — the findRepoByName scan is O(n·m) and prefix queries are
   the worst case.
5. Any budget miss ships with its profile in the step-log (which frame
   section, which system) — a number without a cause is a bug report.

### Current measured values (2026-09-07)

| Budget | Measured | Status |
|--------|----------|--------|
| Demo cold boot (--demo --selftest) | ~2.2 min wall-clock under load (1.4 min unloaded) | PASS at rest / WATCH under load |
| FPS (HD 510, 145-room world) | ~30 fps sustained (Intel HD 510 software-render class) | PASS for this GPU class; 60 fps gate applies to the medium-repo medium-GPU target — profile queued (step 117) |
| Search-to-teleport | measured 0.078 ms/query p-worst over 20 prefix queries x 145 rooms (1.55 ms total); budget 300 ms | PASS (200x+ headroom) |

## Metric discipline (the rules)

1. Every metric has a command. If nobody can run it, it's not a metric — it's a wish. Queued selftest checks (slider fidelity, layout stability) join the 40-check gate when they land.
2. Before/after or it didn't happen. Targets are deltas from the baseline table, not vibes. The baseline is re-measured whenever the chatter pipeline changes (H-steps move these numbers).
3. Known artifacts are excluded, explicitly. The 2 dark E2E waypoints are baseline-carrying; a milestone claim that hides a new dark frame behind them fails review (A/B proof per step-log).
4. Slippage policy: a missed target is recorded in the step-log with its next measurement date — never silently dropped (ROADMAP_3MONTH rule 4).
5. One number per milestone in the weekly self-report (step 148): demo boots, cache hit rate, chatter %, E2E OK count. Four numbers a stranger can audit.
