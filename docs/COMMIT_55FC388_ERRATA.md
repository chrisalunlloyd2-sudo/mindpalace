# Errata: commit 55fc388 promised unshipped deliverables (issue #96)

Commit `55fc388` ("Docs: Anomaly detection + strategic improvements (GUI +
agent scaling)", 2026-09-24) advertised three deliverables that were **never
shipped**. Recording the errata here so commit-log readers don't assume they
exist (per the issue's suggested-fix option 2 — the work was abandoned, so a
clarifying note is the chosen workaround, not a late implementation).

## What the commit claimed vs. what actually exists

| Claimed in 55fc388 message | Status |
| --- | --- |
| `STRATEGIC_IMPROVEMENTS_GUI_AGENTS.md` | Never committed (no path, any branch) |
| `DEPLOY_ALL_SYSTEMS.md` | Never committed (no path, any branch) |
| `AnomalyDetector.java` — real-time scan of `game_console.log` + `task_watch.log` for voting anomalies / execution stalls / bridge failures, posting to `~/AIGEN_SYS/.hermes/blackboard.md` | Never committed (no path, any branch) |

The commit's real diff is scout-economy only (+`awardUniqueVisit()` /
`uniqueRoomsCredited()` in `ScoutNPC`, selftest check #32) — content that
already landed two commits earlier in `f7c172e` (step 84 / H30).

## The "pre-existing build failure" claim

The message also said: "Note: Build failure in GameEngine.java
(pre-existing duplicate var names) unrelated to new code." This does **not**
reproduce on current `main` — `mvn compile` is clean. Treat any future build
break as a fresh bug, not a "known pre-existing" one.

## Closest existing capability today

Log-anomaly monitoring as described does not exist in-repo. The nearest live
mechanisms: `Telemetry` (backup/, event ledger) and the AIGEN_SYS-side
watchdogs outside this repo. If the anomaly detector is ever built for real,
delete this errata note and link the commit that ships it.