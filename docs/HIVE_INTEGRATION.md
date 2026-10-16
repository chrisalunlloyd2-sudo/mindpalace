# HIVE_INTEGRATION.md — the viper hive: cells, OAuth, swarm decisions

The **viper hive** is the distributed half of MindPalace: the game runs in one
process (the lab), while *hive cells* — lightweight workers named
`kernel-localhost`, `kernel-sandbox`, `kernel-<hostname>` — execute packaged
flows (see FLOWS.md) on **local models only**. This doc maps what integration
surface exists today, and what the hive topology adds on top of it.

Legend: **[SHIPPED]** = grounded in code as of d4b67dc. **[SPEC]** = the
card-defined hive concept not yet implemented in this repo.

## 1. Hive topology [SPEC, evidenced by live fleet data]

```
        ┌─────────────────────────────────────────────┐
        │        fleet gist 04debe0724a26fdee12a9e6d82c4eb56        │
        │  fleet_status.jsonl │ workflow_logits.jsonl │ (flow packs) │
        └───────────────▲───────────────▲──────────────▓────────────┘
                        │ status        │ logits        │ packs [SPEC]
        ┌───────────────┴───┐   ┌───────┴──────────┐
        │  kernel-localhost │   │  kernel-sandbox  │   kernel-<hostname>
        │  SOPHIA/AEGIS/BDI │   │  replay + e2e    │   (join template:
        │  cascade finding  │   │  corpus 1444     │    first line a
        └───────────────────┘   └──────────────────┘    workflow_logits
                                                         "[null]" bootstrap)
```

Live-observed roles (2026-10-16): `kernel-localhost` writes `status: cascade`
findings ("286 findings/0 proposals/tuned") and heartbeat-cycle logits;
`kernel-sandbox` writes `status: green` health with repo `heads` maps
(`{"BDI":{"head":"ca61ad7","dirty":0}, "Aegis":…, "Sophia":…}`) and a corpus
count. The template line `"agent": "kernel-<hostname>"` in
`workflow_logits.jsonl` is the join recipe for a new cell: on first run a cell
substitutes its hostname and appends a `bootstrap` workflow entry — the oldest
live entry (`workflow: "bootstrap", steps: ["create gist"]`) is exactly that
first flight.

## 2. The decision substrate: WeightedQuorumVote [SHIPPED]

Swarm decisions from multiple local models already run inside the game through
`com.mindpalace.agent.sims.WeightedQuorumVote` (SIMS1337 parity). Facts pinned
from source (`src/main/java/com/mindpalace/agent/sims/WeightedQuorumVote.java`:

- Models vote with weights; ties fall to a designated tie-breaker role.
  The local-model roster and role binding is documented in AGENTS.md
  ("Game roles: tool=llama3.2:1b, critic=qwen2.5:0.5b,
  tie-breaker=deepseek-r1:1.5b") — all Ollama-local, respecting the
  local-models-only directive.
- FOW-gated visibility (FOWGate) bounds which voters may see a proposal —
  the swarm is not all-see-all.

Where the proposals come from — `QuorumProposalSource`
(`src/main/java/com/mindpalace/agent/sims/QuorumProposalSource.java`) mints
`RealWorkProposal` records:

- `source` ∈ `"kanban" | "github" | "check"`; `sourceId` = card id / `#num` /
  `check-<slug>`; `concreteAction` names the file/command/check voters vote on.
- `publicSourced` (GitHub issues) → triage gate: "APPROVED public issue →
  `hermes kanban create --triage --idempotency-key …`; REJECTED public issue →
  ledger only (GitHub stays add-only, no mutation)" (source header comments).

**Hive mapping [SPEC]**: a cell replays the flow a card's pack contains, then
participates in quorum the same way — its vote is a `fleet_status.jsonl` line
carrying the workflow verdict. In-game quorum (engine process) remains the
authority; cells extend the voter pool. No code change exists yet for remote
voting — the transport IS the fleet gist.

## 3. What cells must never see [SHIPPED invariant]

- Cell kernels run **local models only** (OLLAMA_NO_CLOUD=1 doctrine). A cell
  that would call a hosted model is a bug to pause, not a fallback to invoke.
- Tool execution stays behind the `ToolSandbox` seam (`docs/TOOL_CONTRACT.md`
  §3): in-process today, `FleetSandbox` (worker process per card t_89442886)
  is the open half. A hive cell is the fleet-side executor of that same
  contract — containment, limits L-1/L-2/L-3, and the 5-class failure taxonomy
  apply verbatim.
- Credentials: the PAT lives on the host (Credential Manager /
  `MIND_PALACE_GITHUB_TOKEN`); it authorizes gists read/write. Cells pull
  raw gist URLs — anonymous reads work for public gists, and no token ever
  travels inside a flow pack.

## 4. OAuth/Reddit bridge [SHIPPED]

Hive cells reach external surfaces (currently Reddit) through the bridge stack
in `src/main/java/com/mindpalace/integration/`:

- `RedditOAuthClient` — device/user-app OAuth for Reddit; fetches an access
  token and calls Reddit endpoints with it.
- `OAuthCallbackServer` — the local loopback receiver for the OAuth
  redirect; binds a short-lived HTTP listener so the dance never needs a
  browser on the cell host.
- `RedditToQuorumBridge` — the poller: `new RedditToQuorumBridge(reddit,
  quorum, ledgerPath, pollIntervalMs)` (constructor line 29), `startPolling()`
  (line 40), `shutdown()` (line 122). Every fetched Reddit item lands in an
  append-only ledger (`ledgerPath`) and is offered into `WeightedQuorumVote`
  as a proposal — external signals become ordinary swarm votes, reviewed by
  the same quorum as card work.

This is the hive's external sense organ: reddit → ledger → proposal → quorum.
Cells replicate the ledger append format; nothing outside the ledger path is
mutated (GitHub stays add-only; Reddit actions stop at the ledger too).

## 5. Cell join checklist [SPEC]

To stand up `kernel-<newhost>`:

1. Clone `chrisalunlloyd2-sudo/BDI_FSM_DAGs` (word library ledger) locally —
   it is the source of the `sop` patterns a cell replays.
2. Verify local-model reachability (Ollama on 127.0.0.1) before anything
   else; a cell with no local model is dark and must write nothing.
3. First flight: append the bootstrap line to `workflow_logits.jsonl`
   (`{"ts":<now>,"agent":"kernel-<hostname>","workflow":"bootstrap",
   "steps":["create gist"],"logit":0.0,"threshold":0.0,"outcome":"success"}`).
4. Join the heartbeat cadence — append a `fleet_status.jsonl` green line with
   your repo `heads` map on every cycle (schema in FLOWS.md §6).
5. Subscribe to flow packs (FLOWS.md §2) and replay them; report via the same
   two files. The 60s GistWall cooldown and CDN Source-Age lag apply — treat
   the gist as eventually-consistent.

6. Health reporting: a cell that cannot reach Ollama writes
   `status: degraded` and stops writing logits — never write success lines
   from a broken cell. Observed statuses in the live ledger: `green`, `ok`,
   `active`, `healthy`, `degraded`, `syncing`.