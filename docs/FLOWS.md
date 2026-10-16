# FLOWS.md — bytecode → flow packaging → gist versioning

MindPalace is a bytecode testing lab for distributed swarm intelligence. This doc
is the map of the **flow pipeline**: how a workflow proven inside the game (or by
a hive cell) becomes a distributable *flow package*, how packages ride the fleet
gist, and how gist revisions are the versioning story.

Legend: **[SHIPPED]** = grounded in code/git as of d4b67dc (file:line given).
**[SPEC]** = the card-defined contract for the piece that does not exist in code
yet. Never build a [SPEC] section without re-verifying the [SHIPPED] facts first
(AGENTS.md hard rule 1).

## 0. The pipeline in one view

```
game/cell workflow (heartbeat_cycle, e2e_suite, learning_loop, ...)
        │  each proven run appends one entry
        ▼
workflow_logits.jsonl ─────► fleet gist 04debe0724a26fdee12a9e6d82c4eb56
        │                        (also fleet_status.jsonl — cell health)
        │                        (also word_library/success.jsonl — BDI_FSM_DAGs)
        ▼
GistWall renders the wall in-game (renderGistWall(), 60s cooldown)
        │
        ▼  [SPEC] extraction step (scripts/export_card_flows.sh)
flow pack  docs/flows/<card>.json  = workflow + steps + evidence refs
        │
        ▼  [SPEC] cells pull the gist, replay workflows on local models
viper hive cells (kernel-localhost, kernel-sandbox, kernel-<hostname>)
```

## 1. What a flow is **[SPEC]**

A *flow* is a named, ordered list of steps a cell can execute autonomously, plus
the evidence that proves it works. The canonical example is already visible in
the fleet data — every `workflow_logits.jsonl` entry **is** a flow trace:

> `{"ts": 1786889378, "agent": "kernel-localhost", "workflow": "n_retry_fix",
>  "steps": ["dpll_prove_design", "implement_budget", "gate_give_up",
>  "park_at_exhaustion", "suite_680"], "logit": 12.79, "threshold": 0.301,
>  "outcome": "success"}`

So the packaging rule: a flow pack = `workflow` name + ordered `steps[]` +
success criterion (the `outcome` seen in the trace) + pointer to evidence
(card id / DONE line in `task_watch.log` / commit SHA).

Observed workflow names to normalize into the flow library (live sample,
2026-10-16): `bootstrap`, `heartbeat_cycle`, `heartbeat_e2e`, `heartbeat`,
`fleet_heartbeat`, `15min_fleet_heartbeat`, `fleet_sync`, `e2e_suite`,
`e2e_suites`, `e2e_drive`, `e2e_drive_aegis`, `suite_drive`, `learning_loop`,
`bdi_suite`, `aegis_suite`, `bdi_stats_board_fix`, `n_retry_fix`,
`heartbeat_cycle40`, `hb_cycle`. Observed step vocabulary: `sophia` `aegis`
`bdi` `server` `dream` `train`/`training` `tune` `steer` `e2e` `self_extend`
`heads` `gist`. The library's first job is deduplication of these aliases
(`heartbeat_cycle` vs `hb_cycle` vs `heartbeat_cycle40` are one flow).

## 2. Bytecode extraction **[SHIPPED source, SPEC for the extractor]**

The bytecode/DAG layer lives in the `BDI_FSM_DAGs` project (word-library side
reachable on GitHub at `chrisalunlloyd2-sudo/BDI_FSM_DAGs`,
`word_library/success.jsonl`; local mirror repo `BDI_FSM_AGENT`). Two record
kinds ship in that ledger today:

- `event` records — real runs, e.g. an agent heartbeat noting
  `e2e 697/50/55 green; fleet gist +2 (85 entries)`, tagged with its contract
  (`"contract": "fleet-heartbeat"`).
- `sop` records — the extractable flows: `{"kind": "sop",
  "pattern": ["intent:comparison", "layout:table"], "score": 0.787,
  "words": [..]}`. A `sop` with a high `score` is a workflow pattern proven by
  repetition — exactly what a flow pack wraps.

**[SPEC]** The extractor converts a completed kanban card into a `sop`-shaped
pack: read the DONE line from `AIGEN_SYS/todo_management/task_watch.log`
(append-only, one line per finished card), collect the card's changed files and
verdict, emit `docs/flows/<card>.json`, and commit it. `scripts/export_card_flows.sh`
implements this; its output schema matches the logit envelope (section 1) so
cells can replay it verbatim.

## 3. Transport: the fleet gist **[SHIPPED]**

The gist is the only long-lived transport between game and cells. Hard facts
from `src/main/java/com/mindpalace/github/GistWall.java`:

- `FLEET_GIST_ID = "04debe0724a26fdee12a9e6d82c4eb56"` (line 27) — one gist
  carries **all three files**: `fleet_status.jsonl`, `workflow_logits.jsonl`,
  and (in early versions) the fleet_status short name `fleet_status`.
- `fetchGistLines("fleet_status.jsonl", next, MAX_STATUS=4, true)` and
  `fetchGistLines("workflow_logits.jsonl", next, MAX_LOGIT=3, false)` (lines
  79–81) — the wall keeps only the newest 4 status lines and 3 logit lines in
  memory.
- The raw fetch follows the per-file `raw_url` from
  `GET /gists/<FLEET_GIST_ID>`; auth is `Authorization: token <token>`
  (Credential Manager PAT or `MIND_PALACE_GITHUB_TOKEN`).
- The word library is fetched from the repo contents API, not the gist:
  `API_BASE + "/repos/chrisalunlloyd2-sudo/BDI_FSM_DAGs/contents/word_library/success.jsonl"`
  (line 132), `MAX_WORDLIB = 3`.

## 4. Gist versioning **[SHIPPED]**

Gists version by revision: every edit to any file mints a new revision, and the
API returns the current `raw_url` per file whose URL contains the revision
sha-prefix (e.g. `.../raw/fdeeb71e119a8bc7e5491497fcd85493a3f79944/workflow_logits.jsonl`).
Consequences the pipeline relies on:

- **Append-only wins.** Cells append lines; nothing ever rewrites history. The
  live sample had 30+ revisions on the fleet gist.
- **Readers are revision-pinned per fetch.** GistWall resolves current raw URLs
  on every fetch cycle, so stale revision URLs are self-healing after the 60s
  cooldown (`COOLDOWN_MS = 60_000L`, line 29).
- **CDN lag is real.** `raw_url` responses sit behind a CDN cache (observed
  `Source-Age` headers up to a minute); cells must treat "last line" as
  eventually-consistent, never as a barrier.

## 5. In-game wall (the read side) **[SHIPPED]**

`GameEngine.java:559` constructs `gistWall = new GistWall()`; the null-guarded
consumer sits at `GameEngine.java:2828`; the e2e tour asserts wall lines at
`GameEngine.java:6823`. Roadmap §18 marks the wall `DONE` at commit `380c49c`
(surface: fleet_status + workflow_logits + word-library success paths, rendered
by `renderGistWall()`). Without a token the wall shows
`[gist wall: no token]` (GistWall.java line 63) instead of blank — that line in
a screenshot is the signal the PAT/`MIND_PALACE_GITHUB_TOKEN` wiring broke.

## 6. What cells do with a pack **[SPEC]**

A cell that observes a flow pack on the gist replays it against local models
only (PRIMARY GAME DIRECTIVE) and answers on `fleet_status.jsonl`:

> `{"ts":1789337777,"agent":"kernel-sandbox","status":"green","heads":{"BDI":{"head":"ca61ad7","dirty":0},"Aegis":{"head":"315e120","dirty":0},"Sophia":{"head":"d0da1ee","dirty":0}}}`

Replay success appends a `workflow_logits` line with the pack's `workflow`
name — closing the loop: gist → cell replay → gist. The tool-execution half of
cell containment is specced in `docs/TOOL_CONTRACT.md` §3 (`FleetSandbox`,
card t_89442886); flow replay is the workflow-level half of the same seam.