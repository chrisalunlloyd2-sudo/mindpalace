# MindPalace — Master Development Plan

> The full vision, phased. Current state is Phase 1.x (complete). Everything
> below is the plotted path to the self-maintaining code universe.

---

## Current State (Phase 1.x — COMPLETE)

- 3D first-person engine (LWJGL 3 + OpenGL 3.3, Phong lighting + distance fog)
- 136 rooms / 8 hallways, mapped to real GitHub repos (sorted by size)
- Wooden bookcases (3 walls), books grouped by language, color-coded spines
- Readable neon signs (cyan=public, pink=private), glowing neon door frames
- Teleporter pads (pulsing cyan portals) between floors
- Hardwood floors, wallpaper, exit signs, crown molding, baseboard, ornaments
- Retro terminal book editor (view/edit/create/delete/suggest), full GitHub CRUD
- GitHub PAT from Windows Credential Manager
- Frustum + distance culling, acceleration/friction, wall collision, noclip (F3)
- Font renderer (bitmap atlas, wall/floor/billboard), HUD, minimap, F1 help
- Synthesized audio, search/filter (`/`)
- Fog of War (hex reveal), live updates (construction animation)
- Agent NPCs (Explorer + Critic) with SLM brains, state machines, KV personalities
- Knowledge Graph, TODO crystals, lab devices
- Always-on chat HUD, backup (C:→D:), memory DBs (never-make-code/mistakes-twice)
- Model Lifespan (bounded context, drift correction, rolling summary, RAG)
- Live patching (rooms/books/texts/graphics hot-applied, no restart)
- Autodrive tour + screenshot capture (agent "sees" the world)

---

## Phase E — Procedural Music & Beats StudioLab (DONE)

- [x] Procedural music engine (`MusicEngine`) — endless ambient soundtrack, no audio files
- [x] Step-sequencer: pad (chord tones + LFO), arpeggio, bass line, optional beat (kick + hat)
- [x] Live-tunable: key, tempo (BPM), scale (minor/major/dorian/lydian/mixolydian), beat on/off, volume
- [x] Mood presets (calm / mysterious / energetic / dreamy) — one call sets tempo+scale+beat
- [x] Beats StudioLab menu page (ESC → Music) — tune everything live in-game
- [x] Self-test check 17 (procedural engine + live tuning + moods)

---

## Phase F — Outside World Streaming (PARTIAL)

- [x] Day/night cycle — sun (day), sunset (dusk), moon + stars (night), driven by the real clock hour
- [ ] Render the outside world in small chunks at once (streaming, not all-at-once)
- [ ] Diablo/Zelda-style top-down view (low overhead)
- [ ] Trees (Fibonacci), water, lake, sun, sunset, moon, day/night cycle
- [ ] Local weather sync (real weather → in-game weather)

---

## Phase G — Teleporter Graphics Upgrade (DONE)

- [x] Particle swirl — 10-orb spiral column rising and tapering
- [x] Rotating glow ring — two sweeping bars
- [x] Animated pad — concentric rings + white-hot core
- [x] Shimmer — alternating cyan/white/green orbs

---

## Phase H — Exe Installer

- [ ] jpackage native .exe installer
- [ ] Auto-updater

---

## Phase 2 — Options Panel & Interactivity (partial — see below)

### 2.1 Exhaustive Options Panel (ESC)
- [ ] Look sensitivity, invert Y, rebindable keys
- [ ] Assign hotkeys to automations
- [ ] Video: resolution (super-low → super-high), fullscreen, VSync, MSAA, quality presets, render scale
- [ ] Audio: master/music/SFX volume, per-sound toggles
- [ ] Agents: create/delete, model select, model downloader, LoRA settings, KG node list, cellular automata methods, email triggers, Gmail app-password entry
- [ ] Multiplayer: UDP setup, SSH key display + entry (named), API link (OpenAI/Gemini/DeepSeek-compatible) for cloud-model players (optional, nothing entered by default), private-repo sharing
- [ ] Email: in-game client, me-to-me sync, emails → books in a notes room (cabinets + folders)

### 2.2 Book Interaction
- [ ] Hover → neon glow highlight + tooltip (filename | language | size)
- [ ] Hover → content preview (first lines, "a page of programming")
- [ ] Click → sound + open editor (with the new live-add design)
- [ ] Editor redesigned via live-add protocol, tested in a separate headless unit

### 2.3 GitHub Posters
- [ ] Pull JPGs from GitHub repos → render on wall posters
- [ ] Advanced 3D texture mapping (avoid the usual 3D texture distortion)

---

## Phase 3 — The Laboratory (Half-Life Style)

- [x] Industrial aesthetic, microscope, lab tables, terminals, server racks
- [ ] "Create Repo" terminal, "Fork Repo" station
- [ ] Code analysis microscope (zoom into code structure)
- [ ] CI/CD pipeline visualization

---

## Phase 4 — The Courtyard

- [x] Glass ceiling, fountain, couches, TV screens, bar, hotel safe
- [ ] Password-protected safe, AES-256, visual combination lock

---

## Phase 5 — The Outside (Top-Down Roleplay World)

### 5.1 Environment
- [ ] Diablo/Zelda-style top-down view (low overhead, like Pokémon)
- [ ] Emaculately beautiful: trees (Fibonacci sequence), water, lake, sun, sunset
- [ ] Moon + day/night cycle synced to the real clock
- [ ] Local weather sync (real weather → in-game weather)

### 5.2 Block Building
- [ ] Build with blocks (Lego-like), assign attributes → blocks morph into materials
- [ ] Load textures, BMPs, everything
- [ ] All other nodes/agents/services live here as places

### 5.3 Economy
- [ ] Real DePIN wallets, blackboard TODOs, massive TOC tree (fog-of-war driven)
- [ ] Models get jobs + money, spend to earn more
- [ ] Player inventory, skills, programming languages, deployment types
- [ ] Skill = success (more success → more skill)

---

## Phase 6 — Curie the Cat

- [ ] Separate repo `chrisalunlloyd2-sudo/Curie`
- [ ] Calico cat, walking animation, cat doors, roaming AI, meows/purrs

---

## Phase 7 — Forge Room & 3D Creator

- [ ] Object creation station, import Unreal/Godot assets, 3D viewer, texture editor
- [ ] Edit room visuals in-game, save/load configs

---

## Phase 8 — Multiplayer

- [ ] UDP connection (PC-to-PC), position sync, room state sync
- [ ] View-only access to granted private repos (no edit access either way)
- [ ] Collaborative editing, voice chat, friends

---

## Phase 9 — Code Mining Engine (the core loop)

- [ ] Endless agent conversations about repo files (in programming languages)
- [ ] Capture code + conversation → ship to dev editor
- [ ] Up/Down keys cycle working, tested code candidates (genetic fitness ranking)
- [ ] 2–3 SLMs + mdi-fsm clone + sophia symbolic AI all playing
- [ ] Telemetry: sample every few minutes, silent when good
- [ ] Performance: fastmem single-shot calls, one inference at a time, chat handoff between models
- [ ] Per-turn LoRA/KG/KV-affine updates (pick a strong algorithm — e.g. PPO or GRPO)
- [ ] Never-make-code-twice / never-make-mistakes-twice tree DBs
- [ ] Tree of Knowledge: Google webcrawl (Qwen 3B + cross-correlation RNNs), catalogued by Squigly (worm agent)

---

## Phase 10 — Live Everything (bytecode/scripting)

- [ ] Embed a scripting engine (GraalVM/JS) so game *logic* is hot-reloadable
- [ ] (Java cannot hot-swap new classes at runtime — scripting is the real path)
- [ ] Template system for live-editable game behavior

---

## Phase 11 — Polish, Release & Beautiful GitHub

- [ ] Windows installer (Inno Setup / jpackage), auto-updater
- [ ] Real textures, VR mode (OpenXR), gamepad support
- [ ] GitHub: embedded CSS, visuals, graphs, benchmarks, TODOs, thoughts
- [ ] Binary releases on GitHub
- [ ] CI/CD, test suite, performance benchmarks

---

## Tech Debt & Infrastructure

- [ ] Automated checkpoint releases
- [ ] CI/CD for builds, test suite, performance benchmarks
- [ ] Asset pipeline documentation

---

*Last updated: August 2026*
*Repo: https://github.com/chrisalunlloyd2-sudo/mindpalace*
