# LOGIT_BACKUP_DISTRIBUTION.md — logits, distribution, backup automation

Three subjects, one doc, because they share one spine: the **fleet gist**
(`04debe0724a26fdee12a9e6d82c4eb56`) is the transport, `workflow_logits.jsonl`
is the telemetry, and backup automation is what keeps all of it recoverable.

Legend: **[SHIPPED]** = grounded in code/data as of d4b67dc. **[SPEC]** = the
card-defined component not yet implemented.

## 1. What a logit is here [SHIPPED schema]

A logit line is one proven workflow execution, JSON-escaped inside the gist
file. Full schema as observed in the live ledger:

```json
{"ts": 1786889378, "agent": "kernel-localhost", "workflow": "n_retry_fix",
 "steps": ["dpll_prove_design", "implement_budget", "gate_give_up",
           "park_at_exhaustion", "suite_680"],
 "logit": 12.79, "threshold": 0.301, "outcome": "success"}
```

| field | meaning | observed values |
|---|---|---|
| `ts` | epoch seconds of the run | 1.79e9 range |
| `agent` | writing cell | `kernel-localhost`, `kernel-sandbox`, template `kernel-<hostname>`, historical `hive-agent` |
| `workflow` | flow name (aliases collapse in the library, see FLOWS.md §1) | 19 distinct names incl. `heartbeat_cycle`, `e2e_suite`, `learning_loop`, `bootstrap`, `n_retry_fix` |
| `steps` | ordered step vocabulary | `sophia aegis bdi server dream train tune steer e2e self_extend heads gist`, plus named gates (`dpll_prove_design`, `gate_give_up`, `park_at_exhaustion`, `suite_680`) |
| `logit` | strength/confidence of the run | −4.55 … 12.79 |
| `threshold` | acceptance bar at run time | ≈0.30 typical |
| `outcome` | verdict | `success`, `fail` (legacy spellings `seccess`/`fail` exist — do not "fix" historical lines) |

One exception row exists in the live ledger (an early `fleet_heartbeat`
line with `"result": "…"` instead of `outcome`) — treat `outcome` as the
canonical field; readers must tolerate both.

Where logits feed the game: `GistWall` pulls the newest `MAX_LOGIT = 3` lines
(`fetchGistLines("workflow_logits.jsonl", next, MAX_LOGIT, false)`,
GistWall.java:81) onto the in-game wall. Evolution telemetry is a separate,
local store: `GenomeArchive` writes `<dataDir>/evolution/gen-<N>.json` +
`gen-<N>.wav` per generation (GenomeArchive.java:11-13) — genome fitness is
not a logit and never goes to the gist.

## 2. Distribution [SPEC, transport SHIPPED]

Distribution = how proven flows spread from one cell to all cells. The
transport exists (the gist, revision-versioned — FLOWS.md §4); the pack
extractor and replicator do not yet.

- **Write path [SPEC]** — `scripts/export_card_flows.sh` (FLOWS.md §2) turns a
  completed card into `docs/flows/<card>.json` and appends the pack to
  `workflow_logits.jsonl` on the gist. Writes are append-only, one line per
  pack, never a rewrite.
- **Read path [SHIPPED]** — any cell (or the game) `GET /gists/<id>`, resolves
  current per-file `raw_url`s, pulls `workflow_logits.jsonl`. Anonymous read
  works; the host PAT is only needed to append.
- **Conflict rule [SHIPPED law]** — gist revisions serialize concurrent
  appends last-writer-wins per revision. Two cells appending inside one
  revision window can drop a line; mitigation is cadence jitter (stagger
  appends by ≥15s per cell, e2e-observed concurrent-edit detection in the
  word library ledger shows this actually happens) and idempotent replay
  (a cell must detect it already replayed a `(ts, workflow)` pair).
- **Rate ceiling [SHIPPED]** — the gist is one asset for the whole fleet;
  every append is a full-file rewrite. Keep appends batched (per heartbeat
  cycle, not per event) and respect GitHub's 5,000 req/h REST ceiling; at
  fleet scale that ceiling, not bandwidth, is the binding constraint.

## 3. Backup automation [SHIPPED primitives + SPEC wiring]

What must survive: (a) the fleet gist, (b) the word-library ledger, (c) local
game state (`evolution/` archives, kanban DBs, task_watch.log).

### 3a. The gist is not durable — snapshot it [SHIPPED + SPEC]

Gists are single-copy GitHub objects with no built-in export. The backup
primitive is the same read path GistWall uses: `GET /gists/<id>` returns every
file **plus full revision history is reachable via revision-pinned raw URLs**
(FLOWS.md §4). The backup job:

- **Frequency [SPEC]**: daily, off-peak, before the 07:00 email digest so the
  digest's BOARD STATUS section can reference the freshest snapshot.
- **Action [SPEC]**: `scripts/backup_gist.sh` — auth via runtime
  `git credential fill` (protocol=https, host=github.com) exactly as every
  other fleet tool does; never persist the token; fetch the three files to
  `AIGEN_SYS/backups/gist/<UTC-date>/` and commit the dir in a dedicated
  backup repo (add-only). Retention: keep 90 daily + monthly forever.
- **Verify step [SHIPPED requirement, house doctrine]**: a backup without a
  restore test is a rumour. After each snapshot, `grep -c '"outcome"'
  workflow_logits.jsonl` and require ≥ the previous snapshot's count —
  monotonic append means line count only grows; a shrink is corruption.

### 3b. What already backs up locally [SHIPPED]

- `GenomeArchive` archives every generation's genome + wav under
  `<dataDir>/evolution/` in-repo (GenomeArchive.java:25 `dir =
  dataDir.resolve("evolution")`), best-effort — evolution continues in memory
  if the write fails (comment at line 54). Repo commits therefore carry the
  archives.
- `todo_management/task_watch.log` is append-only evidence (AGENTS.md rule 5
  analogue: add-only GitHub carries step-log issue #9). Back it up by the
  same daily dir-copy as the gist snapshots.
- The kanban boards live in SQLite (`boards/<board>/kanban.db`) — the daily
  backup job copies the DB file while a checkpoint is quiet (between
  dispatcher ticks), not mid-write.

### 3c. Restore runbook [SPEC]

1. `git clone <backup-repo>` (or `git fetch` if already cloned).
2. Recreate the gist from the newest snapshot by PATCH/POST `PUT /gists`
   (one-time, from the host PAT) — or accept the loss window and let cells
   re-bootstrap (FLOWS.md §6, the `bootstrap` template line exists exactly
   for this).
3. Word-library repo restore = `git push` of the re-cloned mirror.
4. Verify: game up → `--selftest` 40/40 → wall renders 4 status + 3 logit +
   3 wordlib lines (GistWall limits, section 1).

## 4. Failure taxonomy for this pipeline [SHIPPED classes, SPEC binding]

Reuse TOOL_CONTRACT taxonomy F-2/F-3/F-4 for the distribution path:
- **F-2 timeout** — CDN Source-Age lag, credential-fill hang: retry once, then
  skip the cycle (wall shows stale lines by design, 60s cooldown).
- **F-3 malformed** — a non-JSON or mixed-schema line: skip the line, keep the
  ledger (never rewrite history to "clean up" — append a correction line).
- **F-4 crash-loop** — a cell writing logits in a loop: its last valid line is
  the recovery point; the 90-day gist snapshots (3a) are the outer ring.