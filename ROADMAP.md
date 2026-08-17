# MindPalace — Master Development Plan

## Current State (Phase 1 — COMPLETE)

- 3D first-person engine (LWJGL 3 + OpenGL 3.3, Phong lighting)
- 145 rooms across 9 floors (68 local + 77 remote/private via GitHub fog of war)
- Wooden bookcases on 3 walls per room, books grouped by language
- Color-coded book spines (blue=Python, yellow=JS, green=Java, red=C++, orange=HTML, grey=Shell, cream=Markdown)
- Book spine text, hover tooltip (filename | language | size), hover highlight (amber glow)
- Billboard neon signs above every door (cyan=public, pink=private)
- Floor labels, floor indicator signs, teleport pads between floors
- Hardwood floors + wallpaper textures, exit signs, stairwell + railings
- Table + chairs, potted plant + floor lamp ornaments in every room
- Retro terminal book editor (view/edit/create/delete/suggest)
- GitHub PAT auto-loaded from Windows Credential Manager (direct git credential-manager get)
- Full CRUD synced to GitHub
- Frustum + distance culling, acceleration/friction movement, wall collision
- Font renderer (bitmap atlas, wall/floor/billboard modes)
- Door open/close animation, doorknobs, poster frames, crown molding, baseboard trim
- On-screen HUD (hotkey bar + room info), minimap, F1 help overlay
- Synthesized audio (footsteps, door creak, ambient hum)
- Search/filter (/ key to jump to any repo)

## Phase 1.5 — Fog of War & Live Updates (COMPLETE)

- [x] FogOfWar: hex-grid reveal; private/remote repos hidden until explored
- [x] GitHubClient + RepoScanner wired into WorldBuilder (145 repos / 9 floors)
- [x] LiveUpdateManager: 15s poll, validate before add, construction animation (GTA Vice City style)
- [x] WorldBuilder.addRoom(): live room insertion without rebuild
- [x] Token load fix: direct git credential-manager get (WSL bash was culprit)

## Phase 1.6 — Agent NPCs & SLM Brains (COMPLETE)

- [x] AgentNPC: Explorer (tool) + Critic (critic) as NPCs with bodies + state machines
- [x] KVTree: per-role personality (curiosity, riskTolerance, verbosity, wanderlust, gossip)
- [x] KnowledgeGraph: rooms as nodes, adjacency edges, district clustering
- [x] TodoCrystal: TODO/FIXME/HACK comments → hex crystals (height = complexity)
- [x] LabDevice: test files → glowing lab devices (green=pass, red=fail)
- [x] BehaviorTree: real SLM brains (phi3:mini + tinyllama:1.1b) drive NPC decisions
- [x] Objects spawn on live room add (books + lab devices)

## Phase 1.7 — Chat, Backup, Memory (COMPLETE)

- [x] Always-on chat HUD (top of screen); Enter pops typing cursor, type + Enter to chat
- [x] Input char callback for in-game typing; Player suppresses door while typing
- [x] BackupManager: auto-crawls C: → D: cold backup (content-hash dedupe)
- [x] MemoryManager: SQLite never-make-code-twice + never-make-mistakes-twice DB
- [x] Theta-curve log pruning (50MB soft cap)
- [x] IdleDetector: agents work harder when idle, quiet when playing

## Phase 1.8 — Model Lifespan (COMPLETE)

- [x] ModelLifespan: token-budgeted context window (phi3:mini 3k, tinyllama 1.5k)
- [x] Drift detection via nomic-embed-text embeddings (cosine centroid)
- [x] Correctors: drop poisoned turns + re-anchor system prompt on drift
- [x] Rolling summary compaction (fold old turns into summary)
- [x] Lightweight RAG memory retrieval (embedding → top-k recall)

## Phase 1.9 — Model Coherence & Performance (COMPLETE)

- [x] ModelConfig: single source of truth for model selection (llama3.2:3b tool + gemma2:2b critic)
- [x] BehaviorTree rewired to stateful ModelLifespan (history + drift + RAG) — no more stateless per-tick replies
- [x] NPC SLM reasoning surfaced into chat HUD (coherent thread visible)
- [x] ModelScheduler: 15s spacing (was 5min — too slow for conversation), dynamic spacing for idle detection
- [x] Mouse fix: idle detection no longer drains mouse deltas (look left/right restored)

## Phase 1.10 — Model Lifespan for Code (NEXT)

- [ ] Feed real code into ModelLifespan (book content → model context, not just filenames)
- [ ] Drift correctors tuned for code (detect when model hallucinates APIs, re-anchor on actual file)
- [ ] RAG memory over code snippets (retrieve similar code before generating)
- [ ] Never-make-code-twice DB wired into generation (check code_seen before proposing)
- [ ] Research: advanced drift correction (KL-divergence, perplexity gates, self-consistency voting)

## Phase 2 — Visual Polish & Interactivity

### 2.1 Book Interaction
- [x] Book click detection, hover highlight, tooltip, spine text
- [ ] Book content preview on hover (first lines)

### 2.2 Room Decor
- [x] Posters, doorknobs, crown molding, baseboard, ornaments (plant + lamp)
- [ ] More ornament variety (rugs, paintings, bookshelves with props)

### 2.3 HUD & Map
- [x] HUD, minimap, F1 help overlay
- [ ] Player stats display (repos visited, crystals collected)

### 2.4 More Levels
- [x] 9 floors, floor signs, teleport pads
- [ ] Elevator animation between floors
- [ ] Organize floors by category (Python floor, JS floor, etc.)

## Phase 3 — The Laboratory (Half-Life Style)

### 3.1 Lab Room
- [x] Industrial aesthetic, microscope, lab tables, terminals, server racks

### 3.2 Lab Functions
- [ ] "Create Repo" terminal — spawns new GitHub repo + room
- [ ] "Fork Repo" station
- [ ] Code analysis microscope (zoom into code structure)
- [ ] CI/CD pipeline visualization

## Phase 4 — The Courtyard

### 4.1 Courtyard Features
- [x] Glass ceiling, fountain, couches, TV screens, bar, hotel safe

### 4.2 Safe Mechanics
- [ ] Password-protected safe, AES-256 encryption, visual combination lock

## Phase 5 — The Outside

### 5.1 Outdoor Environment
- [x] Grass, sun, trees, lake, lakehouse

### 5.2 Multiplayer Gateway
- [ ] QR code portal, cross-repo visitation, multiplayer chat, join codes, encryption

## Phase 6 — Curie the Cat

- [ ] Separate repo `chrisalunlloyd2-sudo/Curie`
- [ ] Calico cat model, walking animation, cat doors
- [ ] Roaming AI, follows player, sits on furniture, meows/purrs, physics

## Phase 7 — Forge Room & 3D Creator

- [ ] Object creation station, import Unreal/Godot assets, 3D viewer, texture editor
- [ ] Edit room visuals in-game, change textures, place objects, save/load configs

## Phase 8 — Multiplayer

- [ ] WebSocket server, position sync, room state sync, join/leave
- [ ] Visit other MindPalaces, collaborative editing, voice chat, friends

## Phase 9 — Polish & Release

- [ ] Windows installer (Inno Setup / jpackage)
- [ ] Auto-updater, settings persistence, performance pass
- [ ] Real textures (image loading), VR mode (OpenXR), gamepad support

## Tech Debt & Infrastructure

- [ ] Automated checkpoint releases
- [ ] CI/CD for builds, test suite, performance benchmarks
- [ ] Asset pipeline documentation

---

*Last updated: August 2026*
*Repo: https://github.com/chrisalunlloyd2-sudo/mindpalace*
