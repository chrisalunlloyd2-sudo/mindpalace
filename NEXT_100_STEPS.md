# NEXT_100_STEPS — Steps 51–150 (Architect review draft)

> Continuation of HYPOTHESES_50.md (H01–H50). Same contract: one step = one
> TASK file = build → selftest → E2E → commit → push → step-log evidence.
> Nothing lives forever, nothing runs for free, always advancing.
> Status markers: [x] shipped · [~] in flight · [ ] queued.

## Where we are (2026-09-06)
- HYPOTHESES_50 infrastructure: DONE (bots, cascade, step-log issue #9)
- H01 critic-context: SHIPPED (commit 108d06b) — critic sees real tool actions
- Rotor/audio advancement: SHIPPED (commit 1eee1e7) — carry events, wind, rotor-clock music
- CSS personality + README program section: SHIPPED (77a47cd, b21b447)
- DAGs: docs/dag_{dataflow,architecture,deployer}.png (this drop)
- Phase B world finishing: NOT STARTED (walls/roofs — the "unfinished houses")

## PHASE A CONT. — SLM Cognition (51–58)
- [x] **51** H01 critic-context — critic reviews concrete tool actions
- [ ] **52** H02 repetition breaker — cosine/SequenceMatcher gate on last-N agent outputs; ≥0.85 match → perturb (temperature bump + "vary approach" system note). Files: AgentManager, ModelLifespan
- [ ] **53** H08 meta-chatter gate — keyword filter on emit; reject "Would you like…"/"Please provide…"; one forced rephrase, else silent
- [ ] **54** H03 tool-loop round-2 — tool results fed back as proper assistant/tool message pairs (Ollama format), SLM sees its own read_file output
- [ ] **55** H04 budgets — TOOL_BUDGET 2000→4000; ToolExecutor truncates files head/tail 150 lines
- [ ] **56** H05 patch semantics — edit_file gets old_string/new_string; solveOne stops rewriting whole files
- [ ] **57** H06 few-shot anchor — one worked TODO→read→patch→apply example in the tool prompt
- [ ] **58** H12 daily metrics task — scout_bot writes slm_quality JSON to the step-log weekly (targets: meta <15%, code >25%)

## PHASE B — World Finishing (59–78)
- [ ] **59** H13 walls — procedural perimeter walls per room, door gaps aligned to hallway openings (WorldBuilder)
- [ ] **60** H13b wall collision — player + NPC collision vs walls (engine/GameState move validation)
- [ ] **61** H14 roofs — flat slab + parapet; courtyard open-sky
- [ ] **62** H15 heights — interior ceiling 4.5m, hall 6m
- [ ] **63** H16 forest fill ×3 — pine/oak/deadwood species, radial density falloff, DeterministicSeed (OutsideWorld: 220 → 600+ trees)
- [ ] **64** H17 forest floor — grass tufts, fallen logs, rocks (instanced, ≤40 draw calls total)
- [ ] **65** H18 paths — cobblestone strips from hallway exits to the forest ring
- [ ] **66** H20 bloom presets — courtyard warm / forest cool / hall neutral, 1.5s lerp on region change
- [ ] **67** H21 quorum flares — APPROVED → gold bloom flare, REJECTED → ice dip (rides the rotor carry-event pattern; CSS already has the web-side twins)
- [ ] **68** H22 palette sweep — all new meshes registered in Renderer palette; region-name HUD fade
- [ ] **69** Window glass — emissive pane quads on walls, lit from inside at "night"
- [ ] **70** Door meshes — actual hinged doors in wall gaps, creak sound already exists
- [ ] **71** Exterior lanterns — point-light posts along the paths (bloom feeds)
- [ ] **72** Weather-forest coupling — rain → darker fog + slower music; snow → white floor tint
- [ ] **73** Forest wildlife — 5 deterministic fireflies (H24 scouts' bodies) looping the tree ring
- [ ] **74** Bench + signposts — rest points with repo-name signposts at path forks
- [ ] **75** Room interiors II — shelves on the 4th wall (window wall), wall art = repo stars
- [ ] **76** Minimap walls — wall segments render on the Tab map
- [ ] **77** E2E waypoints 14–16 — forest path, room interior w/ walls, night window view
- [ ] **78** Perf pass — HD 510 ≥30fps at all 16 waypoints, profile + tune LOD/fog

## PHASE C — Bots & Scouts (79–88)
- [ ] **79** H24 Scout NPC — firefly bot visiting rooms, `[Scout] VISIT <room> <book>` chat lines
- [ ] **80** H25 scout→quorum — scout reports become quorum proposals → TODO crystals
- [ ] **81** H27 BDI bridge v0 — poll BDI_FSM_AGENT webui HTTP; render state as NPC + `[BDI]` chat events
- [ ] **82** H28 unified bot chat format — scout/BDI/SLM share one log schema; quorum reads all
- [ ] **83** H29 stress bot — rapid teleport/interaction bursts, CME hunt on live console
- [ ] **84** H30 scout economy — DePIN credits for unique visits; never-twice dedupe
- [ ] **85** H31 bot lifecycle — TTL, graceful retire, fresh-seed respawn; HUD bot board (H32)
- [ ] **86** H265 self-test harness in cascade — test_bot --run-selftest JSON posted to step-log
- [ ] **87** BDI hardening — sync bdi_fsm tests green in cascade; ASTInspector gate on agent-written Python
- [ ] **88** Bot metrics → step-log — weekly bot activity report comment (visits, credits, retirements)

## PHASE D — Quorum ↔ GitHub Deep (89–98)
- [ ] **89** H33 GitHub releases — cascade tags v0.x.y per phase, builds installer exe, uploads to Release (gh release create; token from Credential Manager)
- [ ] **90** H33a version bump automation — pom.xml version + INSTALLER.md stamp on every phase completion
- [ ] **91** H34 open-issue crystals — GitHubClient.listIssues → TODO crystals; agents see repo issues in-world
- [ ] **92** H35 issue verdicts — quorum votes on issues; verdict posted as comment (add-only, never close)
- [ ] **93** H36 repo health rollup — issues/TODO-density/last-push age → KG attribute + room glow intensity
- [ ] **94** H36a nightly 143-repo sweep — quota-free bot job
- [ ] **95** H37 commit engravings — last 10 commits per repo rendered at the room door
- [ ] **96** H38 push gate — autonomous upsertFile requires quorum APPROVED on the exact diff + critic pass
- [ ] **97** PR preview branch — agent edits land on `agent/<step>` branches; human merges (safety before trust)
- [ ] **98** Weekly self-report (H50) — metrics + phases + next steps as issue comment + reports/

## PHASE E/F/G — Economy, Flash, Modules (99–150)
- [ ] **99** H39 compute tax — Ollama calls cost DePIN credits by chars/4; empty = no refund
- [ ] **100** H40 idle decay — skill/tier decay without completed jobs
- [ ] **101** H41 energy budget — daily ledger caps bloom/trees/scouts; graceful degradation
- [ ] **102** H42 cost telemetry — every autonomous write logs cost+outcome; Sunday review gets a bill
- [ ] **103** H43 flash tier — ModelConfig FLASH lane; trivial tasks never route above flash
- [ ] **104** H44 quota firewall — flash calls local-only by construction; burst client OFF by default
- [ ] **105** H46 quota ledger — cloud-call telemetry; morning digest reports spend
- [ ] **106** H47 dedupe sentinel — static scan for duplicated utilities across repos → issues
- [ ] **107** H48 doc-drift sentinel — README claims vs file inventory; drift → issue
- [ ] **108** H49 multiplayer contract — presence + chat over one WebSocket; loopback two-instance demo
- [ ] **109** Installer v2 — jpackage --type msi, per-user, auto-update check against Releases API, better first-run wizard, theme picker
- [ ] **110** Installer QA — clean-path install test documented in INSTALLER.md
- [ ] **111–150** Content & depth: NPC dialects per district (30), room acoustics (reverb by size), puzzle rooms (Enigma plugboard chains), agent apprenticeship (senior agents train newborn bots on logged successful cycles), KG-backed memory palace search, autosave/replay viewer, achievements tied to real repo milestones, mod hooks (JSON room decorations), localization scaffold, and 30 further polish steps drafted from weekly self-report data.

## Release/versioning policy (per Architect)
- Every phase completion → version bump (0.x.0 per phase: A=0.2, B=0.3, …), tag + installer exe on GitHub Releases
- Binaries updated along the way, not just at milestones — the cascade uploads on green
- Heartbeat crons read the step-log issue first (single source of truth), then act
- BDI_FSM_AGENT: keep 405-test suite green; any mindpalace↔BDI contract change ships with tests on both sides

## For Architect review
1. Approve step order (A-cont → B walls/forest → C bots → D releases → E/F/G)?
2. Version scheme ok (0.x.0 per phase, exe on every green phase)?
3. PR-preview-branch safety (97) before autonomous pushes — yes/no?