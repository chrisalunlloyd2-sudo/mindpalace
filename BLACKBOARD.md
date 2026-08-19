# MindPalace — Blackboard (living plan)

## FIXED THIS SESSION (bf0c1a0)
- Teleporter: end-wall + per-floor clamp + freeze while picking + enter/click guards
- End-of-hall void glitch: end wall added, per-floor forward clamp
- Book hover: highlight wraps book (was 0.12 cube hidden inside), tooltip enlarged

## CONFIRMED BUGS (root causes found)
1. **Tab map overlay missing** — renderMinimap() exists (top-right, always-on) but NO GLFW_KEY_TAB handler. Need full-screen map toggle.
2. **Chats "not working"** — ModelScheduler enforces 5-min spacing between EVERY Ollama call. User chat queued up to 5 min → looks dead. Also routed to tool+critic, not direct reply. Ollama IS running, all models installed.

## PHASES (proposed order — awaiting Architect OK)

### Phase A — Fix the two broken things (small, high-value)
- A1. Tab = full-screen map overlay (rooms/hallways/player/agents/crystals, fog-aware)
- A2. Chat: direct conversational reply path, bypass 5-min gate for user messages (keep gate for autonomous cycle)

### Phase B — Avatars (90s polygon aesthetic)
- B1. Replace basic player/agent boxes with geometric polygon bodies (low-poly limbs, head, arms)
- B2. Walk/arm-swing animation, idle bob, per-agent color/role silhouette
- B3. Player model visible in 3rd person? (or keep 1st person + show agents only)

### Phase C — Room personality (per-repo vibe)
- C1. Each room themed by repo language/topic (color palette, wall accent, floor tint)
- C2. Book labels (spine text = filename, readable)
- C3. GitHub repo poster boards on walls (JPG texture mapping — Phase 2.3)

### Phase D — Finesse / mini-apps (low perf cost)
- D1. Clickable bushes/objects → random fact or web search (side mini-app)
- D2. Side mini-apps panel (small widgets: clock, KG stats, model telemetry)

### Phase E — Music + Beats Studio
- E1. Procedural MIDI music (genetic/experimental beats, per-room mood)
- E2. Beats StudioLab — FL-like synthesizer UI
- E3. Train small LM to operate the synth (long-term)

### Phase F — Outside world streaming
- F1. Render only small portions of outside at once (chunked/streamed)

### Phase G — Teleporter graphics upgrade
- G1. Better teleporter visuals (particle swirl, glow, animated pad)

### Phase H — Exe installer
- H1. jpackage native installer (Windows .exe)

## CONSTRAINTS
- All adds LOW performance cost (Intel HD 510, OpenGL 3.3)
- No VR. Non-VR mode only.
- "Never delete, only merge"
- Live edits preferred; don't close game unless rebuild needed
- Commit + timestamped log BEFORE changes when code works
