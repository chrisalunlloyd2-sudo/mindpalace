# NEXT_STEPS_151_250 — Steps 151–250 (drafted 2026-11-07)

> Continues `NEXT_100_STEPS.md`. Same contract: one step = build → selftest →
> E2E → commit → push → evidence. Small-box laws in `docs/KNOWLEDGE_BASE.md`
> apply to every step. Markers: [x] shipped · [~] in flight · [ ] queued.
> **Owner** tags: (C) Claude/Sonnet, (H) Hermes, (A) Architect decision.

## Where we are (2026-11-07)
- Small-box stability: [x] #342 capacity profile, [x] #344 voter roster flag, [x] #347 automatic roster
- Chat voice: [x] #346 plain-words kit (qwen 0.5b breaks 15→0 of 20; 3b no refusals either way)
- Watchdog: heartbeats restored 16:42 after a 6 h gap (outside the repo, `~/.aria/engine`)
- Docs: [x] #345 (closes #343), this file + `docs/KNOWLEDGE_BASE.md` + `docs/HANDOFF_SONNET.md`

## PHASE H — Small-box hardening (151–165)
- [x] **151** (A) Merge #347 (automatic small roster under 16 GB)
- [ ] **152** (C) Pass `-Dmindpalace.voters` through `mindpalace-sync.sh`, `release_phase.sh` and Hermes' copy, from one shared `GAME_JAVA_FLAGS` (belt and braces with #347)
- [~] **153** (C) Tasks re-registered at Priority 4 (done 2026-11-07); still to do: `install()` default and remove the in-code raise
- [ ] **154** (C) Replace the Aria-Ollama-Serve PowerShell launcher with `watchdog.py start-ollama` (that launcher hung 20+ min)
- [ ] **155** (C) Port the loopback `urlopen` → `http.client` fix to `scripts/monitor_mindpalace.py` and `scripts/system_maintenance.py` (same SSL-context stall)
- [ ] **156** (C) Selftest: a ballot on the small roster reaches quorum with one voter cold-loaded (MP-014 guarantee re-proved for 3 voters)
- [ ] **157** (C) Telemetry: model-load count per hour in the weekly report; alert above 30/h (disk thrash signature)
- [ ] **158** (C) `check_ollama_capacity.py` also reads `/api/ps` and flags distinct-models-per-hour above the cap
- [ ] **159** (C) Disk-queue probe in `ops_health.py` (PerfDisk queue > 8 for 2 cycles = WARN card)
- [ ] **160** (C) Boot recorder pattern (faulthandler on line 1) in every scheduled Python job
- [ ] **161** (C) `budget.py` refuses heavy jobs while the disk queue is high (new gate 6)
- [ ] **162** (H) Hermes card template "slow box" with law lines 1–5 pre-filled
- [ ] **163** (C) Startup log line printing the active Ollama profile (from `/api/ps` context lengths)
- [ ] **164** (C) Doc-drift sentinel learns the small-box section (no false DRIFT on 12 GB)
- [ ] **165** (A) Decide: keep gemma2/phi3 for big boxes only, or retire them

## PHASE I — Character & voice (166–185)
- [ ] **166** (C) Confirmation A/B on llama3.2:3b with 3 seeds × 10 prompts (today: 1 seed)
- [ ] **167** (C) Echo filter: strip kit lines the model parrots back ("Rooms near you:" verbatim)
- [ ] **168** (C) Plain-words kit for the critic lane, behind a flag, A/B before default
- [ ] **169** (C) Personality drift metric: cosine of reply to trait sentence over 50 turns
- [ ] **170** (C) KV traits evolve from logged behavior (curiosity up when the agent explores new rooms)
- [ ] **171** (C) Mood line from the world (weather, time of day) in the voice kit
- [ ] **172** (C) Per-district dialect words (NEXT_100 step 111 seed)
- [ ] **173** (C) Memory line: last 3 facts the agent learned about this room, from the KG
- [ ] **174** (C) Refusal detector in telemetry (the box_voice regex) with a weekly rate
- [ ] **175** (C) Selftest: voice kit length budget ≤ 400 chars
- [ ] **176** (C) Voice kit golden file: changes need a reviewed diff
- [ ] **177** (H) Hermes posts the weekly refusal/echo rates on the step-log issue
- [ ] **178** (C) Second-person rooms ("You are in palace-notes") vs list A/B
- [ ] **179** (C) Name each agent; the kit says "Your name is …"
- [ ] **180** (C) Agent remembers the player's name across sessions (KG, local only)
- [ ] **181** (C) Conversation summary every 20 turns replaces history (bounded context)
- [ ] **182** (C) Emote tokens → billboard icons (no extra model)
- [ ] **183** (C) Speech-bubble timing by reply length
- [ ] **184** (C) Voice regression gate in CI using recorded replies (no model in CI)
- [ ] **185** (A) Approve personality-over-performance defaults per role

## PHASE J — Self-generated data & LoRA (186–205)
- [ ] **186** (C) mpbox generates KV-conditioned dialogue (plain-words kit, 0.5b), 500 rows, via budget.py
- [ ] **187** (C) Filter: refusal + echo regexes, dedupe by bigram similarity ≥ 0.85
- [ ] **188** (C) Human-readable sample (50 rows) for Architect spot check
- [ ] **189** (A) Approve the dataset
- [ ] **190** (C) First real LoRA on qwen2.5:0.5b in the box (CPU, rank 8, 1 epoch), ttl'd heavy job
- [ ] **191** (C) Import the adapter into Ollama as a local model `mp-voice-0.5b`
- [ ] **192** (C) A/B: base vs LoRA, same harness as box_voice.py
- [ ] **193** (C) LoRASwitcher swaps the real adapter on room change (cellular mic: one model at a time)
- [ ] **194** (C) Adapter registry stores dataset hash + eval scores
- [ ] **195** (C) Rollback: adapter that loses the A/B is never loaded
- [ ] **196** (C) Game selftest running as mpbox (box user, capped Ollama :11436)
- [ ] **197** (C) Move the game runtime into the box (Architect-gated)
- [ ] **198** (C) Nightly data job: 50 new rows, filtered, appended (budget-governed)
- [ ] **199** (C) Weekly LoRA retrain only when +100 rows and the eval improves
- [ ] **200** (C) Training ledger in the knowledge base (what, when, score)
- [ ] **201** (C) KV cache prefix reuse for the static kit (fewer prompt tokens per call)
- [ ] **202** (C) Measure prompt-eval time before/after 201
- [ ] **203** (C) LoRA for the tool lane (patch-format accuracy) — dataset from logged successful cycles
- [ ] **204** (C) Agent apprenticeship: senior agent's successful cycles feed a newborn bot's adapter
- [ ] **205** (A) Decide the publish policy for adapters (private only?)

## PHASE K — Hermes lane (206–225)
- [ ] **206** (H) Read SOUL.md MACHINE LAW at every session start; quote the law number when refusing
- [ ] **207** (H) Use `budget.py` for every model job; on exit 75 post "waiting" and retry later
- [ ] **208** (H) Never edit OLLAMA_* or start serve; on DRIFT post a card comment
- [ ] **209** (H) Daily: `check_ollama_capacity.py` result to the step-log issue
- [ ] **210** (H) Daily: watchdog heartbeat age + incidents tail to the board
- [ ] **211** (H) Card per open PR older than 24 h with CI state, so the Architect sees the queue
- [ ] **212** (H) Weekly report section "machine health" (RAM free, disk queue, model loads/h)
- [ ] **213** (H) Fill the gaps list from `UNFINISHED_BUSINESS.md` into cards (prep only, no code)
- [ ] **214** (H) Reddit-fed proposals → quorum queue (public feedback gate stays: Architect sign-off)
- [ ] **215** (H) Keep gists current: one status gist per week, linked from the step-log
- [ ] **216** (H) Board hygiene: close cards whose PR merged (verify against git log)
- [ ] **217** (H) Nightly doc-drift sentinel run, issues only, no auto-edit
- [ ] **218** (H) Escalation ladder: 2 failed cycles on one card → block + reason + ping
- [ ] **219** (H) Relaunch recipe uses the game flags from step 152 only
- [ ] **220** (H) Track Ollama cloud billing fix before any dispatch (board dispatch stays off until then)
- [ ] **221** (H) Per-card max runtime 30 m, retries 1 (native kanban caps)
- [ ] **222** (H) Prep "advanced bot" cards with exact file/line targets from the Sonnet hints
- [ ] **223** (H) Monthly: knowledge-base review card (corrections, stale lines)
- [ ] **224** (H) Mirror merged-PR summaries into the knowledge base change log
- [ ] **225** (A) Review Hermes' first week under the law

## PHASE L — World depth & release (226–250)
- [ ] **226** (C) Room acoustics: reverb by room size (NEXT_100 111–150 content seed)
- [ ] **227** (C) Puzzle room: Enigma plugboard chain
- [ ] **228** (C) KG-backed palace search (type a word, doors glow)
- [ ] **229** (C) Autosave + replay viewer
- [ ] **230** (C) Achievements tied to real repo milestones
- [ ] **231** (C) Mod hooks: JSON room decorations
- [ ] **232** (C) Localization scaffold
- [ ] **233** (C) Splash screen shows small-box mode when active
- [ ] **234** (C) FPS budget under ballots on the 12 GB box: ≥ 25 FPS with 3 voters (measure)
- [ ] **235** (C) Frame-hitch sampler run during a ballot, report in baselines/
- [ ] **236** (C) Installer: first-run detects RAM and writes the small-box flags
- [ ] **237** (C) Installer: ships `check_ollama_capacity.py` result in the first-run log
- [ ] **238** (C) Release notes generator from merged PR titles
- [ ] **239** (C) v-bump for phase H+I completion
- [ ] **240** (C) Clean-machine install test on the 12 GB box
- [ ] **241** (C) Phone page: show watchdog heartbeat + game FPS (tailnet only)
- [ ] **242** (C) Sophia bots wired into the game as no-model NPC voices
- [ ] **243** (C) Gist wall shows the weekly status gist
- [ ] **244** (C) Water/weather critic findings → tasks (existing critics)
- [ ] **245** (C) Door dissolve duplicate (#296) resolved
- [ ] **246** (C) Multiplayer presence over tailnet between this box and the hub
- [ ] **247** (C) Swarm node: hub schedules heavy jobs off the 12 GB box
- [ ] **248** (C) Public release checklist run (full checklist, public)
- [ ] **249** (H) Announce the release on the step-log issue + gist
- [ ] **250** (A) Phase review: what to keep, what to cut

## Order
H (stability) → I (voice) → J (data/LoRA) with K (Hermes) running alongside → L.
Never two heavy jobs at once. Every step passes the selftest gate before merge.
