# MindPalace — Blackboard (living plan)

## FIXED THIS SESSION (bf0c1a0)
- Teleporter: end-wall + per-floor clamp + freeze while picking + enter/click guards
- End-of-hall void glitch: end wall added, per-floor forward clamp
- Book hover: highlight wraps book (was 0.12 cube hidden inside), tooltip enlarged

## CONFIRMED BUGS (root causes found)
1. ~**Tab map overlay missing**~ FIXED — GLFW_KEY_TAB toggle shipped (GameEngine.java:1024, showMap + 1-9 teleport; also F8 DEBUG overlay toggle), BLACKBOARD refresh for issue #84.
2. ~**Chats "not working"**~ FIXED — user chat has a dedicated immediate lane: `submitImmediate()` (ModelScheduler.java:124) bypasses the 5-min autonomous spacing; autonomous cycle still gated. (BLACKBOARD refresh for issue #84.)

## PHASES (proposed order — RECONCILED 2026-11-05, #319: B/C/D/E/F/G/H all have shipped
code; only the genuinely-open residues below remain. Style matches Phase A's strike-through
+ DONE + evidence; per AGENTS.md hard rule #1 each claim was grepped against source.)

### Phase A — Fix the two broken things (small, high-value) — BOTH DONE
- ~A1. Tab = full-screen map overlay~ ✅ DONE — GameEngine.java:1024 showMap + 1-9 teleport (step 76, 02df8e0)
- ~A2. Chat: direct conversational reply path, bypass 5-min gate for user messages~ ✅ DONE — ModelScheduler.submitImmediate (ModelScheduler.java:124)

### Phase B — Avatars (90s polygon aesthetic) — PARTIAL
- ~B1. Replace basic player/agent boxes with geometric polygon bodies (low-poly limbs, head, arms)~ ✅ SHIPPED AS BOX-RENDERER ANALOG — GameEngine NPC draw: anthropometric proportions (shoulders/hips/arms/legs per sex), head/shoulder/hip/leg geometry, AvatarDescriptor overrides (skin/clothing/proportions); avatar/ package (AvatarLibrary presets incl. Cortana, AvatarDescriptor, CharacterDNA, AvatarImporter). NOT true polygon meshes — bodies are cubes + hex prism; a real low-poly mesh swap stays open.
- ~B2. Walk/arm-swing animation, idle bob, per-agent color/role silhouette~ ✅ SHIPPED — GameEngine "Gait: walk-cycle swing + idle bob (B2)": bob phase sine, walking swing 0.5, legSwing; holo tint per sex/role.
- B3. Player model visible in 3rd person? (or keep 1st person + show agents only) — STILL OPEN (no 3rd-person camera path found in source)

### Phase C — Room personality (per-repo vibe) — SHIPPED
- ~C1. Each room themed by repo language/topic (color palette, wall accent, floor tint)~ ✅ DONE — WorldBuilder.districtDoorColor + PortalTheme (#152, 4830150); selftest "room personality (N/M rooms tinted)" green
- ~C2. Book labels (spine text = filename, readable)~ ✅ DONE — FontRenderer book-spine labels; selftest "book spine" green
- ~C3. GitHub repo poster boards on walls (JPG texture mapping — Phase 2.3)~ ✅ DONE — poster boards + off-thread decode/downscale; selftest "poster boards" + "poster decode off-thread" green

### Phase D — Finesse / mini-apps (low perf cost) — SHIPPED
- ~D1. Clickable bushes/objects → random fact or web search (side mini-app)~ ✅ DONE — selftest "finesse (N facts, KG ...)" green
- ~D2. Side mini-apps panel (small widgets: clock, KG stats, model telemetry)~ ✅ DONE — clock (TimeClock), KG stats + model telemetry in HUD/selftest-covered widgets

### Phase E — Music + Beats Studio — E1/E2 SHIPPED, E3 OPEN
- ~E1. Procedural MIDI music (genetic/experimental beats, per-room mood)~ ✅ DONE — audio/ (AudioEngine, MusicEngine, StepSequencer, AudioSettings) + genetics/ GA pipeline (AudioEvolver, AudioGenome, PatchSynth, SonicFitness, FFT, GenomeArchive, GeneticTimeline); selftest "music (procedural engine + live tuning + moods)" + "step sequencer" green
- ~E2. Beats StudioLab — FL-like synthesizer UI~ ✅ DONE — StepSequencer UI (grid + toggle + tempo + clear; selftest green)
- E3. Train small LM to operate the synth (long-term) — STILL OPEN

### Phase F — Outside world streaming — SHIPPED
- ~F1. Render only small portions of outside at once (chunked/streamed)~ ✅ DONE — WorldBuilder.chunkVisibleAt + OutsideWorld.chunkVisible (chunked draw; ROADMAP.md Phase F [x]; CCR §5 reconciled same day, #322)

### Phase G — Teleporter graphics upgrade — SHIPPED
- ~G1. Better teleporter visuals (particle swirl, glow, animated pad)~ ✅ DONE — 120-particle one-shot swirl, AnimationSystem.swirlMode, wired into Player/GameEngine (MP-009, e4a1647, #155; e2e waypoint case 33)

### Phase H — Exe installer — SHIPPED
- ~H1. jpackage native installer (Windows .exe)~ ✅ DONE — build-installer.sh (jpackage .exe/.msi) + build-warm-installer.sh (Inno Setup); INSTALLER.md "Two installers, two tiers" (#139/#140, a8e11ce)

## CONSTRAINTS
- All adds LOW performance cost (Intel HD 510, OpenGL 3.3)
- ~~No VR. Non-VR mode only.~~ Superseded: the VR path is kept and a VR/2D toggle is shipped (see CROSS_CORRELATED_ROADMAP.md).
- "Never delete, only merge"
- Live edits preferred; don't close game unless rebuild needed
- Commit + timestamped log BEFORE changes when code works
