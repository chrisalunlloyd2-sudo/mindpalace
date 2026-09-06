# HYPOTHESES_50 — MindPalace Autonomous Finishing Program

> 50 modular hypotheses, each: **H# → change → test → ship**. One per TASK file.
> Queue ONE at a time in `todo_management/todo_files/mindpalace/` (the hourly
> task-watch cron `04ea2996729b` picks it up; workdir = this repo, AGENTS.md
> injected). Every step ends with: build → selftest → E2E → commit → push →
> update GitHub step-log issue. Nothing lives forever, nothing runs for free.

## Why these 50 (evidence, 2026-09-06)
- Chat-log analysis (1,018 msgs, Sep 1–6): **81.8% meta-chatter**, only 10.9%
  code-bearing; code-rate trended to **0%** by Sep 5 (0/164). Root causes
  found in source: critic reviews NOTHING (AgentManager.java:321 prompts
  "evaluate its work" with no work attached); tool loop discards
  `tool_calls` results into a one-line summary; no repetition breaker.
- World: houses lack walls/roofs (WorldBuilder floor-only rooms),
  OutsideWorld forest is sparse/dull; bloom exists (BloomEffect.java, 347
  lines) but is single-threshold — server-effect variants are unbuilt.
- Quorum: votes on "Act on <repo>" abstractions; never sees real GitHub data.

## PHASE A — SLM Cognition Fixes (H01–H12) — "more able to code"
Each step = one java file region + verify via chat-log metrics.

- **H01** Critic reviews REAL work: thread the tool round's actual diff
  (files touched, tool-call results, synthesis line) into the critic prompt
  at AgentManager.java:321. Test: critic messages reference file names /
  actions, not "the tool agent".
- **H02** Repetition breaker: cosine-similarity gate on last-N critic/tool
  outputs (reuse monitor script's SequenceMatcher idea in Java); on match
  ≥0.85 inject "don't repeat, vary approach" system note + bump temperature.
  (This is BACKLOG item #21 — finally executed.)
- **H03** Tool-loop round-2 with results inline: executeToolRound feeds each
  tool result back as a proper assistant/tool message pair (Ollama
  tool-call format) instead of a text summary, so the SLM sees its own
  read_file output. Test: ≥1 read_file → follow-up edit_file chain per day.
- **H04** Bigger tool model budget: TOOL_BUDGET 2000→4000 tokens
  (ModelConfig) so read_file contents actually fit; truncate files to
  head/tail 150 lines in ToolExecutor before sending.
- **H05** Structured code-edit format: edit_file gets `old_string` /
  `new_string` (patch semantics) instead of full-file rewrite (solveOne at
  :646 returns whole files — a 0.5b model cannot do that reliably). Test:
  solve-loop applies edits without breaking compile, verified by `mvn package`.
- **H06** Few-shot anchor: prepend ONE worked example (TODO→read→patch→
  apply) to the tool prompt. SLMs follow patterns; the example teaches the
  loop shape. Test: tool-call success rate up cycle-over-cycle.
- **H07** Router honesty: Complexity.estimate currently sees only context
  text; feed it actual file size + language + TODO density so LOW→qwen
  0.5b only when trivially true. Test: routing log distribution shifts.
- **H075** Quorum proposal = real artifact: replace "Act on <repo>" with the
  tool round's concrete action summary (files, action type, bytes). Votes
  become meaningful (they now approve real diffs).
- **H08** Critic output gate: reject meta-chatter ("Would you like…",
  "Please provide…", "let's continue") via LexicalAnalyzer keyword filter
  before emit; replace with a forced rephrase prompt once, else stay silent.
  Test: meta-chatter % (computed daily by scout_bot) drops <30%.
- **H085** Never-twice on SLM outputs: MemoryManager.recordCode applied to
  agent chat text too (not just file writes) — identical outputs blocked at
  emit time, forcing novelty. Test: top-opener counts flatten.
- **H09** Tool-call JSON schema strictness: TOOLS definitions get
  descriptions + enum-constrained filenames (list actual repo files in the
  prompt); test malformed-call rate drops (executeTool "missing filename").
- **H10** Local-verified SLM fixes: solveOne writes to a scratch copy,
  runs a syntax check (javac/py_compile equivalent per extension), only
  then applies. Test: zero broken files in repos/ tree (git status clean on
  repos the swarm touched).
- **H11** Deepseek tie-breaker as reviewer: when quorum margin < 0.3,
  submit the diff to TIE_MODEL for a second opinion before GitHub push.
- **H12** Chat-log metrics task: scout_bot computes daily meta-chatter %,
  code-rate %, tool-success rate into
  `mindpalace_memory/metrics/slm_quality_YYYY-MM-DD.json` — the feedback
  loop that proves H01–H11 worked.

## PHASE B — World Finishing (H13–H22) — houses, forest, polish
All world edits land in `world/WorldBuilder.java` + `world/OutsideWorld.java`
(+ engine/GameState, render/Renderer only for hooks). Every step ships with
an E2E waypoint screenshot proving visibility.

- **H13** Wall builder: procedural walls around every room perimeter with
  door gaps aligned to hallway openings. Test: E2E shot of a room interior
  with walls; no z-fighting with floors.
- **H14** Roofs: rooms get a flat slab + parapet (mansion aesthetic),
  courtyard open-sky. Test: look-back waypoint shot shows roofline.
- **H15** Room height raise: ceiling 3.2→4.5 where walls meet ceiling, so
  interiors don't feel like crawlspaces. Test: spawn-view shot.
- **H16** Forest fill: OutsideWorld tree count ×3 with species variety
  (cone pine / blob oak / dead branch), density falls off with distance
  from mansion, seeded deterministic (DeterministicSeed) so E2E is stable.
- **H17** Forest floor: grass tufts, fallen-log meshes, rock scatter — 3
  instanced mesh types, not individual draw calls. Test: fps ≥ 30 on Intel
  HD 510 at the courtyard waypoint.
- **H18** Paths: cobblestone strips mansion→forest, connecting the 9
  hallways' exits outside. Test: waypoint shot from forest edge back at
  mansion, path visible.
- **H19** Forest audio: wind rustle layer in audio engine, volume tied to
  player distance to tree density centroid. Test: telemetry event on layer
  first activation.
- **H20** Bloom presets: per-scene bloom (courtyard warm, forest cool, hall
  neutral) — bloom.setIntensity/setThreshold animated on region change.
  "Server effects": bloom + emissive crystals pulse on quorum events
  (APPROVED → gold bloom flare, REJECTED → cold blue dip).
- **H21** Quorum-bloom hook: WeightedQuorumVote.QuorumResult status wired
  to BloomEffect intensity ramp (a 2s ease). Test: telemetry event +
  screenshot mid-flare via --e2e with a forced vote.
- **H22** Finishing sweep: unified material palette pass — all new meshes
  (walls/roof/forest) share the palette table in Renderer so the mansion
  reads as one design language. Test: full E2E tour green.

## PHASE C — Bot Swarm / Scouts (H23–H32) — build bots, enter them in game
Quota-free bots (no LLM) that work FOR the system and appear IN the game.

- **H23** Scout bot core: `scripts/scout_bot.py` — reads game_console.log +
  chat_logs + telemetry.db, produces structured recon (JSON + ASCII map).
  Runs as `python scripts/scout_bot.py --watch` for live tails.
 -- This is built TODAY (see scripts/); H23 = verify + wire metrics output.
- **H24** In-game Scout NPC: entity/ScoutNpc.java — a firefly-style scout
  that flies to a random book/room each cycle, "reports" via chat log line
  `[Scout] VISIT <room> <book> [ctx ...]` — the lexical bridge picks up
  scout reports as topics so SLMs discuss REAL repo files. Quota-free
  world-intelligence feeding the LLM conversation.
- **H25** Scout→quorum: scout report topics enter the quorum as proposals
  (real file names, not abstractions) → approved scout topics spawn TODO
  crystals (already exists: crystals on approved topics).
- **H26** Cascade scripts: `scripts/cascade_dev.sh <H#>` — one command:
  freeze live jar → git branch → apply step → mvn package → selftest → E2E
  → verify shots non-black → commit → push → update the H# GitHub issue
  with "DONE + evidence". Bounded, resumable, quota-free (bash + python).
- **H265** Bot self-test harness: `scripts/test_bot.py` — runs the game
  headless with --selftest, parses RESULT, and writes a pass/fail JSON the
  cascade posts to the GitHub issue. So EVERY step has machine-verified
  proof, not claims.
- **H27** BDI_FSM_AGENT bridge: mindpalace polls BDI's webui (localhost
  HTTP, per BACKLOG research) — renders its agent state as an NPC whose
  BDI deliberation events surface in chat as `[BDI] <event>`. Zero-LLM
  intelligence from the 405-test deterministic agent.
- **H28** BDI→GitHub chat loop: BDI events + scout reports + SLM replies
  share ONE chat log format; quorum topics drawn from the merged stream
  so all bots (SLM scouts, BDI, firefly) discuss the SAME real repo items.
- **H29** Testing bot vs game: `scripts/stress_bot.py` — synthetic input
  injection (autodrive path) doing 100 rapid teleports/interactions while
  watching for CME/freeze (the agent-thread CME class of bug) — logs to
  metrics. Finds concurrency regressions BEFORE they hit the live game.
- **H30** Bot Colony economy: scouts earn DePIN credits for unique visits;
  duplicate visits (never-twice) earn zero — ties world-recon to the
  existing economy, makes exploration non-free (entropy tax).
- **H31** Bot lifecycle: ModelLifespan-style aging for scout bots — each
  bot has a TTL, "dies" gracefully (fades, logs [Scout] RETIRE), new bot
  spawns with a fresh seed. Nothing lives forever — enforced in code.
- **H32** Bot metrics board: HUD page listing live scouts (name, age,
  visits, credits, last report) — visible in-game proof the swarm works.

## PHASE D — Quorum ↔ GitHub (H33–H38) — "make sure the quorum is hooked up to my github"
- **H33** Step-log issue system: one long-lived GitHub issue per phase in
  chrisalunlloyd2-sudo/mindpalace — every finished H# appends a comment
  (action, files, evidence link). The autonomous "updating GitHub every
  step" pipeline. Uses existing GitHubIssueStream (add-only) + a new
  comment method (GitHubClient gets addIssueComment).
- **H33a** Quorum-GitHub audit task: verify GitHubIssueStream
  (GameEngine.java:345 wiring) actually fires — check real issues raised
  on the repo, fix pacing/rate limits if throttled silently.
- **H34** Open-issue ingestion: GitHubClient.listIssues(repo) — open
  issues become TODO crystals (entity layer) so agents see GitHub work
  INSIDE the world; quorum topics may be drawn from issue titles.
- **H35** Issue-status votes: quorum vote on "close issue #N" is ADD-ONLY
  (post a comment with the verdict — never close) per never-delete rule.
- **H36** Repo health rollup: per-repo score (open issues, TODO density,
  last-push age) → KG node attribute + room glow intensity in-game. Rooms
  literally glow with repo health.
- **H36a** 141-repo sweep job: bots run the rollup across all 143 mapped
  repos nightly (quota-free), updating room glow + KG.
- **H37** Commit-stream surface: last 10 commits per repo render as
  "engraving" objects near the room's door (title + sha + age) — GitHub
  activity made physical. Test: E2E shot of a door with engravings.
- **H38** Autonomy gate: before any autonomous GitHub push (edit_file via
  github.upsertFile), require quorum APPROVED on the exact diff summary +
  critic non-rejection; else the write stays local-only. Safety net now
  that agents have real write power.

## PHASE E — Entropy Economy (H39–H42) — "nothing runs for free"
- **H39** Compute tax: every Ollama call costs the agent DePIN credits
  proportional to tokens (estimate by chars/4). Empty outputs refund zero.
  Prevents infinite chatter loops — chat literally costs money now.
- **H40** Idle decay: agents with no completed job in N cycles lose tier /
  skill decay (ModelLifespan already tracks spans; wire to DePIN skill).
- **H41** Energy budget for world: bloom intensity, tree count, scout
  count are capped by a daily "energy" ledger; over-budget days auto-dim
  effects (graceful degradation, never crash).
- **H42** Kill-switch telemetry: every autonomous write path (solveOne,
  executeTool, issueStream.raise) logs cost + outcome to telemetry.db so
  the Sunday review can price each subsystem. You get a bill, not vibes.

## PHASE F — GLM Flash Tier (H43–H46) — quota-saving model routing
- **H43** GLM-flash profile in ModelConfig: a "flash" role for cheap fast
  calls (chat echoes, quorum topic titles, scout report formatting) —
  local smallest model (qwen2.5:0.5b) with low max_tokens, NOT ollama-cloud
  quota. All flash-tier calls are quota-free by construction.
- **H44** Ollama-cloud quota firewall: ModelScheduler routes ONLY the
  tool/critic deep calls through a configurable endpooint; flash calls
  never touch cloud. Config flag `mindpalace.flash.local=true` default.
-- **H45** Cloud burst-mode (optional, off by default): if a task exceeds
  N local attempts, escalate ONE call to ollama-cloud glm flash (small
  max_tokens, single round) — the only sanctioned quota spend, and only
  after H01–H05 land so the prompt it sends is worth the tokens.
- **H46** Quota ledger: every cloud call (if burst-mode on) logs tokens to
  telemetry.db `cloud` category; morning digest reports spend. Zero
  surprise quota walls.

## PHASE G — Module Programs (H47–H50) — new programs & subprograms
- **H47** Sub-program: "Library Extractor 2" — bots scan repos/ for
  duplicated utility functions and open dedupe issues (feeds AIGEN_SYS
  duplicate-finder program; quota-free static analysis).
- **H48** Sub-program: "Doc Drift Sentinel" — compares README claims vs
  actual file inventory per repo; drift > threshold opens an issue. Stops
  the planning-doc drift problem (BACKLOG noted it twice).
- **H49** Module: multiplayer contract (from BACKLOG next-week): presence
  heartbeat + chat stream over a single WebSocket — client/server contract
  document first (issue), then a minimal local loopback server so two
  game instances can see each other's scouts. One game instance rule
  still enforced by bot_swarm (guard: single javaw).
- **H50** Program completion: "MindPalace Self-Report" — weekly
  auto-generated state-of-the-game report (metrics, phases done, next
  steps) posted as a GitHub issue comment + saved to reports/. The system
  reports its own progress autonomously. Loop closes.

## Order of execution (quota-savvy cascade)
1. Bots first (H23, H26, H265): they make every later step cheaper.
2. Phase A (H01→H12) — cognition: agents become worth feeding.
3. Phase D (H33→H38) — GitHub hookup: every step ships publicly.
4. Phase B (H13→H22) — world finishing: visible progress, user-visible.
5. Phase C (H24→H32) — scouts in-game: recon swarm goes live.
6. Phase E (H39→H42) — entropy: make it all cost-aware.
7. Phase F (H43→H46) — flash tier: route cheap calls locally.
8. Phase G (H47→H50) — modules and self-reporting.

## Task-file format (each H# → one TASK file)
```
TASK_H01_CRITIC_CONTEXT.md
Goal: <one line>
Files: <exact paths:line regions>
Steps: numbered, copy/paste commands
Verify: selftest + E2E + metric assertion
Ship: commit + push + issue comment (cascade_dev.sh H01)
```