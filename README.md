# MindPalace — 3D GitHub Repository Explorer

> A Doom-style first-person walkthrough of your code universe, where every door
> is a GitHub repo, every book is a file, and two small language models live
> inside the walls — reading, gossiping, and mining your code while you walk.

MindPalace turns your GitHub account into a grand, explorable palace. Walk a
warm, fog-lit hallway where each door opens into a room built from a real
repository. Wooden bookcases line three walls; every book is a file you can
read, edit, create, or delete — full GitHub CRUD from inside a 3D game. Two
local SLM agents (an Explorer and a Critic) roam the halls, pick up TODO
crystals, read books, and hold endless conversations about your code in
programming languages — mining working, tested code candidates you can later
pull into the editor.

---

## Table of Contents

1. [The Vision](#the-vision)
2. [Current State](#current-state)
3. [Key Parts of the Game](#key-parts-of-the-game)
4. [Quick Start](#quick-start)
5. [Controls](#controls)
6. [The Options Panel](#the-options-panel)
7. [Gameplay Guide](#gameplay-guide)
8. [The Agents & Code Mining](#the-agents--code-mining)
9. [Live Patching](#live-patching)
10. [Project Structure](#project-structure)
11. [Tech Stack](#tech-stack)
12. [FAQ](#faq)
13. [Roadmap](#roadmap)

---

## The Vision

MindPalace is an **externalized vision** — a living, breathing map of a
developer's entire code universe. It is not a file browser with a 3D skin; it
is a world where code has *place*, *light*, and *inhabitants*.

- **Every repo is a room.** Public repos glow cyan; private repos glow pink.
  Fog of war hides what you haven't explored yet.
- **Every file is a book.** Color-coded by language, clickable, editable.
- **Agents live here.** Small language models walk the halls, read your code,
  and talk about it — endlessly, in programming languages, mining working code.
- **The world grows.** New repos appear live. The outside is a roleplay world
  (Diablo/Zelda-style top-down) where every node, agent, and service is a
  place you can visit and build.

The end state is a **self-maintaining code universe**: agents mine code,
candidates are tested and fitness-ranked, and you pull the best into the
editor with a keystroke. The databases grow massive — that's the point.

---

## Current State

**Phase 1.x — COMPLETE** (the palace interior):

- 3D first-person engine (LWJGL 3 + OpenGL 3.3, Phong lighting + distance fog)
- 136 rooms across 8 hallways, mapped to real GitHub repos (sorted by size)
- Wooden bookcases on 3 walls per room, books grouped by language
- Color-coded book spines (blue=Python, yellow=JS, green=Java, red=C++,
  orange=HTML, grey=Shell, cream=Markdown)
- Readable neon signs above every door (cyan=public, pink=private)
- **Glowing neon door frames** — pulsing trim + halo around each doorway
- **Teleporter pads** (pulsing cyan portals) between floors — replaced stairs
- Hardwood floors, wallpaper, exit signs, crown molding, baseboard trim
- Table + chairs, potted plant + floor lamp in every room
- Retro terminal book editor (view/edit/create/delete/suggest)
- GitHub PAT auto-loaded from Windows Credential Manager; full CRUD synced
- Frustum + distance culling, acceleration/friction movement, wall collision
- Font renderer (bitmap atlas, wall/floor/billboard modes)
- On-screen HUD (hotkey bar + room info), minimap, F1 help overlay
- Synthesized audio (footsteps, door creak, ambient hum)
- Search/filter (`/` key to jump to any repo)
- **Fog of War** — hex-grid reveal; private/remote repos hidden until explored
- **Live updates** — new repos appear with a construction animation
- **Agent NPCs** — Explorer + Critic with bodies, state machines, SLM brains
- **Knowledge Graph** — rooms as nodes, adjacency edges, district clustering
- **TODO crystals** — TODO/FIXME/HACK comments → hex crystals (height = complexity)
- **Lab devices** — test files → glowing devices (green=pass, red=fail)
- **Always-on chat HUD** — agents' reasoning surfaced as they work
- **Backup + Memory** — cold backup to D:, never-make-code-twice / never-make-mistakes-twice SQLite DBs
- **Model Lifespan** — token-budgeted context, drift detection, rolling summary, RAG memory
- **Live patching** — drop a `patch.json` and the game ships rooms/books/texts/graphics without restarting

---

## Key Parts of the Game

### The Hallway
A grand corridor with hardwood floors, wallpaper, and crown molding. Doors line
both walls. Each door has a neon sign (repo name), an exit sign, and a glowing
frame. Teleporter pads at the end of each floor carry you to the next.

### The Rooms
Each room is a repository. Three walls of bookcases hold the files as books,
grouped by language. A table, chairs, a plant, and a lamp make it feel lived-in.
The room's last commit and language are shown in the HUD.

### The Books
Every book is a file. Hover to see its name, language, and size. Click to open
the editor. Books are color-coded by language so you can scan a shelf at a
glance.

### The Agents
Two SLM agents live in the palace:
- **Explorer** (tool agent) — reads books, picks up TODO crystals, proposes code changes
- **Critic** (actor-critic) — reviews the Explorer's proposals, flags bugs and risks

They hold coherent, continuous conversations about your code, driven by a
stateful model lifespan (history + drift correction + RAG memory). Their
reasoning surfaces in the chat HUD.

### The Crystals & Devices
- **TODO crystals** — hex crystals that grow with the complexity of the TODO/FIXME/HACK comment they represent
- **Lab devices** — glowing terminals that turn green (passing tests) or red (failing tests)

### The Knowledge Graph
Rooms are nodes; hallways are edges. The graph clusters repos into districts
and powers the agents' spatial awareness.

---

## Quick Start

### Prerequisites

- Java 17+
- Maven 3.9+
- Windows 10/11 (uses Windows Credential Manager for GitHub auth)
- GPU with OpenGL 3.3 support
- (Optional) Ollama running locally for the SLM agents

### Build & Run

```bash
git clone https://github.com/chrisalunlloyd2-sudo/mindpalace.git
cd mindpalace

export JAVA_HOME="C:/Program Files/Java/jdk-17"
export M2_HOME="C:/ProgramData/chocolatey/lib/maven/apache-maven-3.9.16"

# Compile + package (fat jar)
"$JAVA_HOME/bin/java" -cp "$M2_HOME/boot/plexus-classworlds-2.11.0.jar" \
  -Dclassworlds.conf="$M2_HOME/bin/m2.conf" -Dmaven.home="$M2_HOME" \
  -Dmaven.multiModuleProjectDirectory="$PWD" \
  org.codehaus.plexus.classworlds.launcher.Launcher clean package

# Run
"$JAVA_HOME/bin/java" -jar target/mindpalace-1.0.0.jar
```

### CLI Flags

| Flag | Effect |
|------|--------|
| `--autodrive <dir>` | Scripted walkthrough that captures PNG frames to `<dir>` (lets an agent SEE the world) |

---

## Controls

| Key | Action |
|-----|--------|
| WASD | Move |
| Mouse | Look around |
| Space | Jump |
| Enter | Open door / exit room |
| Left Click | Click book to open editor |
| ESC | Toggle menu / close editor |
| F1 | Help overlay |
| F3 | Toggle noclip (free-fly: Space/Shift up/down, no collision) |
| F11 | Fullscreen toggle |
| F12 | Screenshot (saves to `screenshots/`) |
| `/` | Search / jump to any repo |

### Book Editor Commands

| Command | Action |
|---------|--------|
| `:e` | Edit mode |
| `:n` | Create new file |
| `:d` | Delete file |
| `:s` | AI suggestions |
| `:w` | Save (in edit/create mode) |
| `:q` | Close / go back |
| `:y` | Confirm delete |

---

## The Options Panel

The options panel (ESC) is the control center. It is being expanded to cover
every subsystem. The full target surface:

### Controls
- Look sensitivity (mouse)
- Invert Y axis
- Rebindable keys (move, jump, interact, screenshot, noclip, search)
- Assign hotkeys to automations

### Video
- Resolution (from super-low to super-high)
- Fullscreen / windowed
- VSync
- Anti-aliasing (MSAA) options
- Quality presets (low → ultra)
- Render scale

### Audio
- Master / music / SFX volume
- Footstep, door, ambient hum toggles

### Agents
- Create / delete agents
- Select model per agent
- Model downloader (pull SLMs from Ollama)
- LoRA settings (extensive list)
- Knowledge-graph node list
- Cellular automata methods
- Email triggers for agent flow
- Gmail app-password entry point

### Multiplayer
- UDP connection setup (connect to another PC)
- SSH key display + entry (name your keys)
- API link (OpenAI-compatible, Gemini-compatible, DeepSeek-compatible) to
  enter a cloud model as a player — **optional, nothing entered by default**
- Private-repo sharing (grant view-only access to specific repos)

### Email
- In-game email client
- Sync with self-sent "me-to-me" emails
- Emails become books in a dedicated notes room (cabinets + folders for organization)

### Economy (planned)
- Real DePIN wallets, blackboard TODOs, a massive TOC tree
- Models earn money for work, spend it to earn more
- Player inventory, skills, programming languages, deployment types

---

## Gameplay Guide

1. **Spawn** in the grand hallway on floor 0. Look around — doors line both walls.
2. **Read the signs** — each door's neon sign shows the repo name (cyan=public, pink=private).
3. **Walk to a door** and press Enter to open it. Step inside the room.
4. **Scan the bookcases** — books are color-coded by language. Hover to see name/size.
5. **Click a book** to open the editor. Read, edit, create, or delete files.
6. **Explore** — fog of war hides unexplored repos. Walk to reveal them.
7. **Find the teleporter** at the end of each floor to reach the next.
8. **Watch the agents** — the Explorer and Critic roam, read, and discuss your code in the chat HUD.
9. **Search** — press `/` to jump to any repo by name.

---

## The Agents & Code Mining

The long-term goal is **endless code mining**:

1. Agents hold endless conversations about a repo's files — in programming languages.
2. The conversation (code + discussion) is captured and shipped to the dev editor.
3. Up/Down keys cycle through **working, tested code candidates** ranked by genetic fitness.
4. 2–3 SLMs + your `mdi-fsm` clone + `sophia` symbolic AI all play together.
5. Telemetry samples every few minutes (and stays quiet when things are good).
6. Performance: fastmem loading for single-shot calls, one inference at a time,
   chat saved and passed to the next model as the previous model's lifecycle ends.
7. Each turn updates the LoRA, KG, and KV affine to improve the models.
8. The databases grow massive — that's the point. Functional code accumulates.

The **never-make-code-twice** and **never-make-mistakes-twice** databases are
tree structures that prevent redundant work. The **Tree of Knowledge** is a
Google webcrawl (research papers via a Qwen 3B with cross-correlation RNNs),
catalogued by **Squigly** — a worm agent that lives in the wild (see its repo).

---

## Live Patching

The game watches `patches/patch.json`. Drop a new patch (new `id`) and within
8 seconds the game plays a "GAME PATCH LOADING" cinematic and ships it live —
new rooms, books, texts, and graphics tuning — **no restart, no rebuild**.

```json
{
  "id": "2026-08-17-001",
  "title": "Firefly Night",
  "message": "The garden blooms.",
  "texts": ["Fireflies drift over the garden."],
  "rooms": [{"name": "Gardens", "desc": "A night garden.", "lang": "python"}],
  "books": [{"title": "Patch Notes", "content": "Line one.\nLine two."}],
  "graphics": {"ambient": 0.85, "lightR": 1.0, "lightG": 0.9, "lightB": 0.7, "lightY": 4.0}
}
```

Rules: **ADD-only** (never delete), **never re-use an id**. See `PATCHING.md`
for the full schema and the `graphics` block (hot-tune lighting live).

---

## Project Structure

```
mindpalace/
├── pom.xml
├── README.md
├── ROADMAP.md
├── PATCHING.md
├── patches/
│   └── patch.json          # live patch manifest
├── src/main/java/com/mindpalace/
│   ├── Main.java
│   ├── engine/
│   │   ├── GameEngine.java    # game loop, window, state, neon text, autodrive
│   │   ├── Input.java         # keyboard + mouse
│   │   └── GameState.java
│   ├── render/
│   │   ├── Renderer.java      # OpenGL renderer, lighting, live-tunable
│   │   ├── Shader.java        # GLSL wrapper
│   │   ├── Mesh.java          # VAO/VBO/EBO
│   │   ├── Texture.java       # solid-color + image textures
│   │   ├── Camera.java        # FPS camera
│   │   ├── FontRenderer.java  # bitmap font atlas + text
│   │   └── Screenshot.java    # glReadPixels → PNG
│   ├── world/
│   │   ├── WorldBuilder.java  # procedural world, teleporters, door frames
│   │   ├── Room.java          # repo room entity
│   │   ├── Book.java          # file book entity
│   │   ├── Hallway.java       # hallway segment
│   │   ├── RepoMapper.java    # local repo scanner + git log
│   │   ├── RoomPopulator.java # file→book mapper
│   │   ├── FogOfWar.java      # hex-grid reveal
│   │   ├── KnowledgeGraph.java
│   │   ├── TodoCrystal.java
│   │   └── LabDevice.java
│   ├── entity/
│   │   ├── Player.java        # FPS controller, collision, noclip
│   │   └── AgentNPC.java      # agent body + state machine
│   ├── agent/
│   │   ├── AgentManager.java  # tool + critic orchestration
│   │   ├── BehaviorTree.java  # SLM decision brain
│   │   ├── ModelLifespan.java # bounded context + drift + RAG
│   │   ├── ModelScheduler.java
│   │   ├── ModelConfig.java
│   │   ├── KVTree.java
│   │   └── OllamaClient.java
│   ├── github/
│   │   ├── GitHubClient.java  # REST CRUD
│   │   └── RepoScanner.java
│   ├── deploy/
│   │   └── PatchManager.java  # live patch poll + apply
│   ├── ui/
│   │   ├── HUD.java
│   │   ├── BookEditor.java
│   │   └── SettingsMenu.java
│   └── audio/
│       └── AudioEngine.java
├── src/main/resources/shaders/
│   ├── basic.vert
│   └── basic.frag
└── src/installer/
    └── mindpalace.iss
```

---

## Tech Stack

- **Engine:** LWJGL 3.3.3 + OpenGL 3.3 Core
- **Language:** Java 17
- **Build:** Maven 3.9.16
- **Math:** JOML 1.10.5
- **HTTP:** OkHttp 4.12.0
- **JSON:** Gson 2.10.1
- **Font:** Java 2D → OpenGL texture atlas
- **Agents:** Ollama (local SLMs)

---

## FAQ

**Q: Why is the game dark?**
A: The palace is intentionally moody — warm, fog-lit, atmospheric. If it's too
dark, drop a live patch with a higher `ambient` value (see Live Patching), or
raise the base ambient in `Renderer.java`.

**Q: Why do some doors have no sign?**
A: Fog of war. Unexplored (private/remote) repos are hidden until you walk to
them and reveal them.

**Q: How do I add a new repo?**
A: Push a repo to GitHub (or create one locally). The live-update manager polls
every 15s and builds a new room with a construction animation.

**Q: Do the agents need Ollama?**
A: Yes — the SLM brains call Ollama. Without it, agents fall back to
deterministic behavior (they still roam and read, just without LLM reasoning).

**Q: Is my GitHub token safe?**
A: The token is loaded from Windows Credential Manager and only ever sent to
api.github.com. It never leaves your machine otherwise.

**Q: Can I play with friends?**
A: Multiplayer (UDP) is on the roadmap. The options panel will include
connection setup, SSH key display, and private-repo sharing.

**Q: What's the "outside"?**
A: A planned top-down roleplay world (Diablo/Zelda-style, low overhead) where
all your other nodes, agents, and services live — with block-based building,
a real economy, and a day/night cycle synced to your clock and local weather.

---

## Roadmap

See `ROADMAP.md` for the full phased plan. Highlights:

- **Phase 2** — Book hover preview, more decor, player stats, elevator
- **Phase 3** — Laboratory: create/fork repos, code microscope, CI/CD viz
- **Phase 4** — Courtyard: fountain, bar, safe (AES-256)
- **Phase 5** — The Outside: grass, sun, trees, lake, lakehouse, weather sync
- **Phase 6** — Curie the Cat (separate repo)
- **Phase 7** — Forge Room & 3D Creator (block building, texture import)
- **Phase 8** — Multiplayer (UDP, position sync, collaborative editing)
- **Phase 9** — Polish & Release (installer, auto-updater, real textures)

---

Built with LWJGL 3, OpenGL 3.3, and love for code.
