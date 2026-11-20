# LIVE-4 — Cron provider registry (per-job model/provider, 2026-11-20)

Companion to the `Cron Status Watchdog` job (`cron_status_watchdog.py`,
every 15m → `AIGEN_SYS/reports/cron_status.json`). This file is the
committed, human-readable record of every Hermes cron job's model/provider
and of the LOCAL-ONLY compliance rules for them.

## Compliance rules (task t_9bc9210e acceptance)

1. **Enabled LLM cron jobs** pin `provider=ollama-cloud, model=glm-5.3-flash`
   — Hermes' own reasoning model, explicitly authorized by the Architect's
   scope ruling of 2026-10-11 (Hermes spectates and does outside work; the
   LOCAL-MODELS-ONLY directive applies to what runs inside the game).
2. **Deterministic jobs** run `no_agent=true` with a script and no model at
   all (pure stdlib Python / bash; no LLM, no network model calls).
3. **Disabled/paused legacy jobs** may carry older pins; any with a model pin
   are normalized to the authorized pair so a re-enable cannot reintroduce a
   violation (both `game_agent_*` jobs were normalized 2026-11-20).
4. Nothing inside the game (agents, NPCs, bots, quorum voters, gateway) may
   call any model through a cron job — game runtime stays on local Ollama
   via the :11435 gateway (UNIFY-1), Hermes cron never touches it.

## Per-job registry (as of 2026-11-20, `hermes cron list`)

| Job | Schedule | Mode | Provider / model | Status |
|---|---|---|---|---|
| TODO Tracker | every 360m | LLM | ollama-cloud / glm-5.3-flash | ok |
| TODO_Resolution_Tracker | every 360m | LLM | ollama-cloud / glm-5.3-flash | ok |
| TODO_Harvest_6h | every 360m | LLM | ollama-cloud / glm-5.3-flash | ok |
| Daily_Maintenance_0000 | 0 0 * * * | LLM | ollama-cloud / glm-5.3-flash | ok |
| Weekly_Maintenance_Sunday_0300 | 0 3 * * 0 | LLM | ollama-cloud / glm-5.3-flash | ok |
| Orthogonal_Analysis_Sunday_0500 | 0 5 * * 0 | LLM | ollama-cloud / glm-5.3-flash | ok |
| AIGEN task watch | 0 * * * * | LLM (script+agent) | ollama-cloud / glm-5.3-flash | ok |
| BDI_FSM_AGENT task watch | 30 * * * * | LLM (script+agent) | ollama-cloud / glm-5.3-flash | ok |
| dream_correlation_job | 0 0,3,.. * * * | script-only | none (no_agent) | paused, ok |
| MindPalace Auto-Sync | every 30m | script-only | none (no_agent) | paused |
| MindPalace Morning Digest | 0 7 * * * | script-only | none (no_agent) | ok |
| MindPalace Health Monitor | every 15m | script-only | none (no_agent) | ok |
| MindPalace Scout Bot | every 30m | script-only | none (no_agent) | ok |
| MindPalace USB Backup | 0 4 * * * | script-only | none (no_agent) | ok (explained skip; see below) |
| mp-jar-swap-ncdfe-watchdog-2h | once | script-only | none (no_agent) | completed, paused |
| claude-liveness-watchdog | 47 * * * * | script-only | none (no_agent) | ok |
| mail-queue-drain | 0 * * * * | script-only | none (no_agent) | ok |
| Cron Status Watchdog | 15 * * * * | script-only | none (no_agent) | ok |
| game_pipeline_rag_lora | 0 */6 * * * | script-only | none (no_agent) | ok |
| bot_standard_audit | 0 6 * * * | script-only | none (no_agent) | ok |
| swarm_learn | 30 6 * * * | script-only | none (no_agent) | ok |
| box_bot | every 60m | script-only | none (no_agent) | ok |
| kanban_dispatch_mindpalace | every 30m | script-only | none (no_agent) | ok |
| game_agent_rag_builder | 0 */4 * * * | LLM (paused) | ollama-cloud / glm-5.3-flash (pinned 2026-11-20) | paused |
| game_agent_lora_judge | 30 */8 * * * | LLM (paused) | ollama-cloud / glm-5.3-flash (pinned 2026-11-20) | paused |
| AI_Prioritization_6h | every 360m | LLM (paused) | ollama-cloud / glm-5.3-flash (pinned 2026-10-27) | paused (was the 402 job) |
| SIMS1337-Heartbeat | every 60m | LLM (paused) | ollama-cloud / glm-5.3-flash | paused |
| SIMS1337 Hourly Heartbeat | 0 * * * * | LLM (paused) | ollama-cloud / glm-5.3-flash | paused |
| SIMS1337 Governor | every 30m | LLM (paused) | ollama-cloud / glm-5.3-flash | paused |
| aegis_daily_job | 0 0,3,.. * * * | LLM (paused) | ollama-cloud / glm-5.3-flash | paused (Aegis owner; cloud violation pause 2026-10-15) |

## Historical: the HTTP 402 incident

Four jobs (AI_Prioritization_6h, Daily_Maintenance_0000,
Weekly_Maintenance_Sunday_0300, Orthogonal_Analysis_Sunday_0500) failed with
`HTTP 402: usage credits auto reload payment failed` on 2026-10-10 — they
ran on a paid cloud model before the scope ruling. All were re-pinned to
`ollama-cloud/glm-5.3-flash` (the authorized Hermes reasoning model) on
2026-10-27; three re-enabled and run ok since. **Zero 402s since 2026-10-27.**
No paid provider is enabled anywhere; no job spends money.

## MindPalace USB Backup — explained skip (2026-11-20)

Root cause of the nightly timeouts: pure-bash `cp -ru` over the whole
AIGEN_SYS + hermes trees onto a **100%-full HDD (D: 32G/32G, 8KB free)**
while Ollama drives CPU inference (~10 tok/s, 2 models resident) — the run
blew the 1800s script timeout; merge-only copy cannot free space itself.
Hardened 2026-11-20: robocopy /XO with excludes (.git, worktrees, target/,
node_modules, caches, WAL/SHM), stage ordering, honest exit codes;
`bash -n` clean; robocopy path verified. Until the operator prunes D:
(the mirror keeps 79 repo copies incl. dead clones), the job exits 0 with a
log line "D: effectively full … skipping backup (explained skip)" — an
honest, reported skip, not a silent failure.

## Watchdog

`Cron Status Watchdog` regenerates `AIGEN_SYS/reports/cron_status.json`
every 15 min with per-job provider/model/no_agent/enabled, staleness vs
expected cadence (flags jobs whose last run is >1.5x interval old, per the
2026-10-10 comment request), compliance flag, and the configured
`script_timeout_seconds` (1800). Summary counts include `noncompliant_model`
— must stay 0.
