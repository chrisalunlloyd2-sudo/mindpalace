# MindPalace — Master Development Plan

## Current State (Phase 1 — COMPLETE)

- 3D first-person engine (LWJGL 3 + OpenGL 3.3, Phong lighting)
- 68 rooms across 4 floors, sorted by repo size
- Wooden bookcases on 3 walls per room, books grouped by language
- Color-coded book spines (blue=Python, yellow=JS, green=Java, red=C++, orange=HTML, grey=Shell, cream=Markdown)
- Book spine text (filenames rendered on spines, 8m cull)
- Book hover tooltip (filename | language | size)
- Billboard neon signs above every door (cyan=public, pink=private) — always face camera
- Floor labels in front of each door
- Hardwood floors + wallpaper textures
- Exit signs above doorways
- Stairwell with railings between floors
- Table + chairs in each room
- Retro terminal book editor (view/edit/create/delete/suggest)
- GitHub PAT auto-loaded from Windows Credential Manager
- Full CRUD synced to GitHub
- Last commit info in HUD
- Frustum + distance culling
- Acceleration/friction movement, wall collision
- Font renderer (bitmap atlas, wall/floor/billboard modes)
- Door open/close animation (slide up on enter, slide down on exit)
- Doorknobs, poster frames, crown molding, baseboard trim
- On-screen HUD (hotkey bar + room info)
- Minimap (top-right, floor dots, player @ marker)
- 2 LLM agents (phi3:mini tool-calling + tinyllama:1.1b critic), 5-min auto cycle, Tab to chat
- Live deployments (git add/commit/push/build on save, particle burst animation)
- Synthesized audio (footsteps, door creak, ambient hum)
- Search/filter (/ key to jump to any repo)

## Phase 2 — Visual Polish & Interactivity

### 2.1 Book Interaction
- [x] Verify book click detection works reliably
- [ ] Add book hover highlight (glow when looking at a book)
- [x] Book tooltip showing filename + language on hover
- [x] Book spine text (filenames on spines)

### 2.2 Room Decor
- [x] Posters on walls
- [x] Doorknobs on every door
- [x] Crown molding / baseboard trim
- [ ] Ornaments, plants, lamps

### 2.3 HUD & Map
- [x] On-screen HUD (hotkey bar, room info)
- [x] Minimap in corner showing hallway layout
- [ ] Hotkey reference overlay (F1)
- [ ] Player stats display

### 2.4 More Levels
- [x] Expand to 4 floors for 68 repos
- [ ] Floor indicator signs
- [ ] Elevator / teleport between floors
- [ ] Organize floors by category (Python floor, JS floor, etc.)

## Phase 3 — The Laboratory (Half-Life Style)

### 3.1 Lab Room
- [x] Large room with industrial aesthetic (metal walls, concrete floor)
- [x] Microscope model (block-based, retro)
- [x] Lab tables with equipment
- [x] Computer terminals (block monitors + keyboards)
- [x] Server racks

### 3.2 Lab Functions
- [ ] "Create Repo" terminal — spawns new room
- [ ] "Fork Repo" station
- [ ] Code analysis microscope (zoom into code structure)
- [ ] CI/CD pipeline visualization

## Phase 4 — The Courtyard

### 4.1 Courtyard Features
- [x] Large open room with glass ceiling
- [x] Central fountain
- [x] Couches and seating areas
- [x] TV screens
- [x] Bar/restaurant area
- [x] Hotel safe

### 4.2 Safe Mechanics
- [ ] Password-protected safe
- [ ] Encrypted file storage
- [ ] AES-256 encryption for secrets
- [ ] Visual combination lock

## Phase 5 — The Outside

### 5.1 Outdoor Environment
- [x] Grass terrain
- [x] Sun
- [x] Trees (simple block-based)
- [x] Lake
- [x] Lakehouse

### 5.2 Multiplayer Gateway
- [ ] QR code portal for game linking
- [ ] Cross-repo visitation (visit other people's MindPalaces)
- [ ] Multiplayer chat system
- [ ] Join codes / passwords
- [ ] Encryption for multiplayer sessions

## Phase 6 — Curie the Cat

### 6.1 Curie Repo
- [ ] Separate repo: `chrisalunlloyd2-sudo/Curie`
- [ ] Cat model (calico colors: orange, black, white)
- [ ] Walking animation
- [ ] Cat doors in every room door

### 6.2 Curie Behavior
- [ ] Roaming AI (random pathfinding)
- [ ] Follows player sometimes
- [ ] Sits on tables / couches
- [ ] Reacts to player (meow, purr)
- [ ] Physics (jumping, collision)

## Phase 7 — Forge Room & 3D Creator

### 7.1 Forge Room
- [ ] Object creation station
- [ ] Import Unreal/Godot assets as objects
- [ ] 3D model viewer
- [ ] Texture editor

### 7.2 Visual Customization
- [ ] Edit room visuals in-game
- [ ] Change wall textures, floor types
- [ ] Place custom objects
- [ ] Save/load room configurations

## Phase 8 — Multiplayer

### 8.1 Networking
- [ ] WebSocket server for chat
- [ ] Player position sync
- [ ] Room state sync
- [ ] Join/leave notifications

### 8.2 Social Features
- [ ] Visit other players' MindPalaces
- [ ] Collaborative editing
- [ ] Voice chat hooks
- [ ] Friend system

## Phase 9 — Polish & Release

- [ ] Windows installer (Inno Setup)
- [ ] Auto-updater
- [ ] Settings persistence
- [ ] Performance optimization pass
- [ ] Real textures (image loading)
- [x] Audio (footsteps, doors, ambient)
- [ ] VR mode (OpenXR)
- [ ] Gamepad support

## Tech Debt & Infrastructure

- [ ] Automated checkpoint releases
- [ ] Version control documentation
- [ ] Asset pipeline documentation
- [ ] CI/CD for builds
- [ ] Test suite
- [ ] Performance benchmarks

---

*Last updated: August 2026*
*Repo: https://github.com/chrisalunlloyd2-sudo/mindpalace*
