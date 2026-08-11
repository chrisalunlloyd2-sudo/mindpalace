# MindPalace — Master Development Plan

## Current State (Phase 1 — COMPLETE)

- 3D first-person engine (LWJGL 3 + OpenGL 3.3, Phong lighting)
- 51 rooms across 2 floors, sorted by repo size
- Wooden bookcases on 3 walls per room, books grouped by language
- Color-coded book spines (blue=Python, yellow=JS, green=Java, red=C++, orange=HTML, grey=Shell, cream=Markdown)
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

## Phase 2 — Visual Polish & Interactivity

### 2.1 Book Interaction
- [ ] Verify book click detection works reliably
- [ ] Add book hover highlight (glow when looking at a book)
- [ ] Book tooltip showing filename + language on hover
- [ ] Higher-resolution book models (beveled edges, spine text)

### 2.2 Room Decor
- [ ] Posters on walls (README.md screenshots as textures)
- [ ] Doorknobs on every door
- [ ] Crown molding / baseboard trim
- [ ] Ornaments, plants, lamps

### 2.3 HUD & Map
- [ ] On-screen HUD (health/status bar, minimap, hotkeys)
- [ ] Minimap in corner showing hallway layout
- [ ] Hotkey reference overlay (F1)
- [ ] Player stats display

### 2.4 More Levels
- [ ] Expand to 4+ floors for 122+ repos
- [ ] Floor indicator signs
- [ ] Elevator / teleport between floors
- [ ] Organize floors by category (Python floor, JS floor, etc.)

## Phase 3 — The Laboratory (Half-Life Style)

A dedicated lab area for creating repos and software.

### 3.1 Lab Room
- [ ] Large room with industrial aesthetic (metal walls, concrete floor)
- [ ] Microscope model (block-based, retro)
- [ ] Lab tables with equipment
- [ ] Computer terminals (block monitors + keyboards)
- [ ] Server racks
- [ ] Chemical vials / beakers

### 3.2 Lab Functions
- [ ] "Create Repo" terminal — spawns new room
- [ ] "Fork Repo" station
- [ ] Code analysis microscope (zoom into code structure)
- [ ] CI/CD pipeline visualization

## Phase 4 — The Courtyard

A beautiful indoor courtyard at the end of the hallway.

### 4.1 Courtyard Features
- [ ] Large open room with glass ceiling
- [ ] Central fountain (animated water particles)
- [ ] Couches and seating areas
- [ ] TV screens (showing repo stats)
- [ ] Bar/restaurant area
- [ ] Hotel safe (encrypted secrets storage)

### 4.2 Safe Mechanics
- [ ] Password-protected safe
- [ ] Encrypted file storage
- [ ] AES-256 encryption for secrets
- [ ] Visual combination lock

## Phase 5 — The Outside

A door leading to the outside world.

### 5.1 Outdoor Environment
- [ ] Grass terrain (heightmap or flat with texture)
- [ ] Sun (skybox or directional light)
- [ ] Trees (simple block-based)
- [ ] Lake with reflective water
- [ ] Lakehouse

### 5.2 Multiplayer Gateway
- [ ] QR code portal for game linking
- [ ] Cross-repo visitation (visit other people's MindPalaces)
- [ ] Multiplayer chat system
- [ ] Join codes / passwords
- [ ] Encryption for multiplayer sessions

## Phase 6 — Curie the Cat

An AI calico cat that roams the palace.

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
- [ ] Audio (footsteps, doors, ambient, music)
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
