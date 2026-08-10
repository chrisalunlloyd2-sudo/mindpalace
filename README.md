# 🏰 MindPalace — 3D GitHub Repository Explorer

**A Doom-style first-person walkthrough of your code universe.**

Walk through a grand hallway where every door is a GitHub repo. Each room has bookshelves — every book is a file you can read, edit, create, or delete. Full GitHub CRUD from inside a 3D game.

## 🎮 The Experience

```
     ┌──────────────────────────────────────────────────┐
     │  ██ LONG HALLWAY (Ground Floor) ██               │
     │  ┌──┐ ┌──┐ ┌──┐ ┌──┐    ┌──┐ ┌──┐ ┌──┐ ┌──┐    │
     │  │R1│ │R2│ │R3│ │..│    │..│ │..│ │..│ │R25│    │
     │  └──┘ └──┘ └──┘ └──┘    └──┘ └──┘ └──┘ └──┘    │
     │  ← 25 rooms each side →                           │
     │                         STAIRS →                  │
     │  ██ UPPER HALLWAY ██                              │
     │  ┌──┐ ┌──┐ ┌──┐ ┌──┐    ┌──┐ ┌──┐ ┌──┐ ┌──┐    │
     │  │R26││R27││R28││..│    │..│ │..│ │..│ │R50│    │
     │  └──┘ └──┘ └──┘ └──┘    └──┘ └──┘ └──┘ └──┘    │
     │  + ViperAI_Notes special room                     │
     └──────────────────────────────────────────────────┘
```

## 🏗️ Architecture

- **Engine:** LWJGL 3 + OpenGL 3.3+ (GPU-accelerated)
- **Language:** Java 17
- **Build:** Maven
- **GitHub:** OkHttp REST API
- **Math:** JOML
- **Installer:** Inno Setup (Windows .exe)

## 🎯 Features

### Phase 1 (CURRENT)
- ✅ 3D first-person engine with Phong lighting
- ✅ Procedural hallway + room generation
- ✅ 123 GitHub repos mapped to rooms
- ✅ Books = files on shelves
- ✅ WASD movement + mouse look
- ✅ GitHub PAT auth (Windows Credential Manager)
- ✅ Book viewer (read file contents)
- ✅ Edit, create, delete books (files)
- ✅ Settings menu (graphics, audio, controls, theme)
- ✅ Windows installer with PAT input

### Phase 2 (NEXT)
- 🔲 ImGui-style overlay for book viewer
- 🔲 Second room per repo (comments, gists, archive)
- 🔲 Audio (footsteps, doors, ambient)
- 🔲 Minimap
- 🔲 Texture loading from files
- 🔲 Gamepad support
- 🔲 VR mode (OpenXR)

## 🚀 Quick Start

### Build
```bash
# Set up Java and Maven
export JAVA_HOME="C:/Program Files/Java/jdk-17"
export M2_HOME="C:/ProgramData/chocolatey/lib/maven/apache-maven-3.9.16"

# Compile
mvn clean compile

# Package
mvn package -DskipTests

# Run
java -jar target/mindpalace-1.0.0.jar
```

### Controls
| Key | Action |
|-----|--------|
| WASD | Move |
| Mouse | Look around |
| Shift | Sprint |
| Space | Jump |
| E | Interact (open door, read book) |
| F | Toggle flashlight |
| ESC | Settings menu |
| F11 | Fullscreen toggle |

## 📦 Windows Installer

The installer (`mindpalace-setup-1.0.0.exe`) will:
1. Install MindPalace to `Program Files\MindPalace`
2. Ask for your GitHub PAT during install
3. Auto-populate all 123 repos on first run
4. Create desktop shortcut

## 🔐 GitHub Integration

MindPalace uses your GitHub Personal Access Token to:
- List all your repos
- Read file contents
- Create, update, and delete files
- Sync metadata

Token is stored locally in Windows Credential Manager. Never sent anywhere except api.github.com.

## 📊 Scale

- **123 GitHub repos** (chrisalunlloyd2-sudo)
- **49 local repos** in AIGEN_SYS
- **ViperAI_Notes** special room
- **~100 rooms** across 2 floors
- **Thousands of books** (files)

## 🎨 Themes

- **Stone** — Classic dungeon hallway
- **Wood** — Warm library feel
- **Marble** — Grand archive
- **SciFi** — Neon-lit corridor

## 📁 Project Structure

```
mindpalace/
├── pom.xml
├── src/main/java/com/mindpalace/
│   ├── Main.java              # Entry point
│   ├── engine/
│   │   ├── GameEngine.java    # Game loop, window, state
│   │   ├── Input.java         # Keyboard + mouse
│   │   └── GameState.java     # State enum
│   ├── render/
│   │   ├── Renderer.java      # OpenGL renderer
│   │   ├── Shader.java        # GLSL shader wrapper
│   │   ├── Mesh.java          # VAO/VBO/EBO
│   │   ├── Texture.java       # STB texture loader
│   │   └── Camera.java        # FPS camera
│   ├── world/
│   │   ├── WorldBuilder.java  # Procedural world gen
│   │   ├── Room.java          # Repo room entity
│   │   ├── Book.java          # File book entity
│   │   ├── Hallway.java       # Hallway segment
│   │   ├── RepoMapper.java    # Local repo scanner
│   │   └── RoomPopulator.java # File→book mapper
│   ├── entity/
│   │   └── Player.java        # FPS controller
│   ├── github/
│   │   ├── GitHubClient.java  # REST API client
│   │   └── RepoScanner.java   # Remote repo merger
│   ├── ui/
│   │   ├── HUD.java           # Crosshair, prompts
│   │   ├── BookViewer.java    # File read/edit/delete
│   │   └── SettingsMenu.java  # Settings panel
│   └── audio/
│       └── AudioEngine.java   # OpenAL stub
├── src/main/resources/
│   └── shaders/
│       ├── basic.vert         # Vertex shader
│       └── basic.frag         # Fragment shader
└── src/installer/
    └── mindpalace.iss         # Inno Setup script
```

## 🔧 Development

```bash
# Compile
mvn clean compile

# Run
mvn exec:java -Dexec.mainClass="com.mindpalace.Main"

# Package fat jar
mvn package -DskipTests

# Build installer (requires Inno Setup)
iscc src/installer/mindpalace.iss
```

---

**Built with LWJGL 3, OpenGL 3.3, and love for code.**
