# 3-Month Roadmap — MindPalace (milestones)

> Months are working months from 2026-09-07. Anchored on the real step
> numbers in NEXT_100_STEPS.md and the release policy (version bump +
> installer exe per phase, binaries updated continuously on green).

## North star (90 days out)

A palace that **builds its own world, checks its own work, and ships its
own releases**: all 143 houses finished (walls/roofs/paths/bloom), agents
whose chat is >70% concrete code work, autonomous pushes gated by quorum
approval, a flash-tier so AI calls never hit quota walls, and a public
evidence trail (step-log + Releases) a stranger can audit in 10 minutes.

---

## Month 1 — "The palace becomes real" (steps 64–88)

**Theme: world finishing + bot swarm.** The game looks and plays finished.

| Week | Milestone | Steps | Proof it's done |
|------|-----------|-------|-----------------|
| 1 | **M1.1 — Demo mode + forest floor** | 112 pulled early, 64 | `--demo` boots fixture palace with zero auth (hermetic CI); forest floor detail ships |
| 2 | **M1.2 — Paths + room heights** | 65, 62 | cobblestone paths connect towns↔mansion↔hospital; varied ceiling heights; 15 E2E waypoints |
| 3 | **M1.3 — Bloom presets + quorum flares** | 71, 72 | 3 presets (day/dusk/night); APPROVED = gold flare riding rotor carries |
| 4 | **M1.4 — Scout NPCs + stress bot** | 80, 82, 85 | firefly scouts report real repo files into agent chat; CME stress bot runs nightly; **v1.2.0-beta installer** |

**Month 1 exit criteria**: E2E has 16 waypoints, scout daily metrics show
meta-chatter <40% (from 81.8%), installer updated on Releases, all
through the cascade.

---

## Month 2 — "The agents become trustworthy" (steps 89–113)

**Theme: governance deepens — agents earn push rights.**

| Week | Milestone | Steps | Proof |
|------|-----------|-------|-------|
| 5 | **M2.1 — Issues become crystals + commit engravings** | 89, 90 | open issues spawn TODO crystals at their repo's room; merged commits engrave plaques |
| 6 | **M2.2 — Push gate on quorum-approved diffs** | 96 | autonomous pushes ONLY after 3-voter quorum approves the exact diff; agent branches land via PR |
| 7 | **M2.3 — Energy budget + cost telemetry** | 99–102 | every LLM call priced in DePIN credits; idle decay; cost panel in HUD |
| 8 | **M2.4 — Flash tier + quota ledger** | 104–107 | local flash model routes cheap calls; cloud burst OFF by default; **v1.3.0-beta installer** |

**Month 2 exit criteria**: an agent push exists in git history that went
through quorum gate end-to-end; credit ledger reconciles against the
telemetry log; flash tier handling ≥60% of model calls at <1s.

---

## Month 3 — "The palace opens to others" (steps 113–150)

**Theme: modules, docs-drift defense, multiplayer contract — make the
project contributable and self-reporting.**

| Week | Milestone | Steps | Proof |
|------|-----------|-------|-------|
| 9 | **M3.1 — Dedupe subprogram + doc-drift sentinel** | 138, 139 | duplicate-code detector ships as a bot; ROADMAP/README staleness alerts weekly |
| 10 | **M3.2 — Layout module extraction** | 113 | `RoomLayout` interface + corridor extraction as pure refactor; stability selftest (same repos → same centers) |
| 11 | **M3.3 — Multiplayer contract draft** | 147 | protocol doc + read-only spectator room prototype; BDI_FSM bridge suite stays green |
| 12 | **M3.4 — Weekly self-report + v1.4.0** | 148–150 | cron composes the week's step-log digest as a Release note; **v1.4.0-beta installer** + 90-day retrospective on the step-log |

**Month 3 exit criteria**: a stranger can go issue → PR → evidence in the
step-log without asking anything in chat (templates + docs carry it);
self-report digest ships automatically for 2 consecutive weeks.

---

## Standing rules for the whole quarter

1. **Cascade or it didn't happen** — build → selftest (40/0) → E2E →
   commit → push → step-log evidence, every step (CONTRIBUTING.md).
2. **Nothing lives forever, nothing runs for free, always advancing** —
   every week ends with a pushed artifact, even if the milestone slipped.
3. **One game instance, frozen jar, one model in flight** — the three
   landmines that cost us hours (DEV_SETUP.md).
4. **Slippage policy**: a missed milestone moves; it never silently
   disappears. The step-log records the slip + reason (heartbeat crons
   read the step-log first).
5. **Version bumps per phase** (A→0.2 … G→0.8), installer on every green
   phase, tags `v0.x.0-betaN`.

## Dependencies & risks (watched)

| Risk | Mitigation | Owner |
|------|-----------|-------|
| ollama-cloud quota walls the watcher LLM | bots stay script-only; agent executes steps directly from source | scout cron |
| model-gate contention stalls selftests | selftest suppresses agent cycle (shipped); DEV_SETUP gotcha documented | done |
| Intel HD 510 draw-call ceiling | LOD + chunk culling + ≤40-call budget rule (PERFORMANCE.md) | budget rule |
| doc drift (planning vs code) | "grep first" rule + staleness sentinel (M3.1) | step 139 |
