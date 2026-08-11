# MindPalace — 3D GitHub Repository Explorer

A Doom-style first-person walkthrough of your code universe. Walk a grand hallway where every door is a GitHub repo. Each room has wooden bookcases — every book is a file you can read, edit, create, or delete. Full GitHub CRUD from inside a 3D game.

## Features

- 3D first-person engine with Phong lighting (LWJGL 3 + OpenGL 3.3)
- 51 rooms across 2 floors, each mapped to a real GitHub repo
- Wooden bookcases on 3 walls per room, books grouped by language
- Color-coded book spines: blue=Python, yellow=JS, green=Java, red=C++, orange=HTML, grey=Shell, cream=Markdown
- Neon signs above every door with repo name (cyan=public, pink=private)
- Floor labels in front of each door
- Retro terminal book editor — click any book to view/edit/create/delete/suggest
- GitHub PAT auto-loaded from Windows Credential Manager
- Full CRUD: read, edit, create, delete files — synced to GitHub
- Last commit info shown in HUD when looking at a door
- Frustum + distance culling for performance
- Acceleration/friction movement (Doom-style), wall collision

## Quick Start

### Prerequisites

- Java 17+
- Maven 3.9+
- Windows 10/11 (uses Windows Credential Manager for GitHub auth)
- GPU with OpenGL 3.3 support

### Build & Run

```bash
# Clone
git clone https://github.com/chrisalunlloyd2-sudo/mindpalace.git
cd mindpalace

# Set Java/Maven paths (Windows)
export JAVA_HOME="C:/Program Files/Java/jdk-17"
export M2_HOME="C:/ProgramData/chocolatey/lib/maven/apache-maven-3.9.16"

# Compile
"$JAVA_HOME/bin/java" -cp "$M2_HOME/boot/plexus-classworlds-2.11.0.jar" \
  -Dclassworlds.conf="$M2_HOME/bin/m2.conf" -Dmaven.home="$M2_HOME" \
  -Dmaven.multiModuleProjectDirectory="$PWD" \
  org.codehaus.plexus.classworlds.launcher.Launcher clean compile

# Package (fat jar)
"$JAVA_HOME/bin/java" -cp "$M2_HOME/boot/plexus-classworlds-2.11.0.jar" \
  -Dclassworlds.conf="$M2_HOME/bin/m2.conf" -Dmaven.home="$M2_HOME" \
  -Dmaven.multiModuleProjectDirectory="$PWD" \
  org.codehaus.plexus.classworlds.launcher.Launcher package -DskipTests

# Run
"$JAVA_HOME/bin/java" -jar target/mindpalace-1.0.0.jar
```

### Controls

| Key | Action |
|-----|--------|
| WASD | Move |
| Mouse | Look around |
| Enter | Open door / exit room |
| Left Click | Click book to open editor |
| ESC | Toggle menu / close editor |
| F11 | Fullscreen toggle |

### Book Editor Commands

When a book is open, type these in the terminal:

| Command | Action |
|---------|--------|
| `:e` | Edit mode |
| `:n` | Create new file |
| `:d` | Delete file |
| `:s` | AI suggestions |
| `:w` | Save (in edit/create mode) |
| `:q` | Close / go back |
| `:y` | Confirm delete |

## Project Structure

```
mindpalace/
├── pom.xml
├── README.md
├── src/main/java/com/mindpalace/
│   ├── Main.java
│   ├── engine/
│   │   ├── GameEngine.java    # Game loop, window, state, neon text, floor map
│   │   ├── Input.java         # Keyboard + mouse (GLFW_CURSOR_DISABLED)
│   │   └── GameState.java     # State enum
│   ├── render/
│   │   ├── Renderer.java      # OpenGL renderer, 19 texture types
│   │   ├── Shader.java        # GLSL shader wrapper (file + inline)
│   │   ├── Mesh.java          # VAO/VBO/EBO
│   │   ├── Texture.java       # Solid-color texture
│   │   ├── Camera.java        # FPS camera
│   │   └── FontRenderer.java  # Bitmap font atlas + text rendering
│   ├── world/
│   │   ├── WorldBuilder.java  # Procedural world, bookcases, neon signs
│   │   ├── Room.java          # Repo room entity
│   │   ├── Book.java          # File book entity
│   │   ├── Hallway.java       # Hallway segment
│   │   ├── RepoMapper.java    # Local repo scanner + git log
│   │   └── RoomPopulator.java # File→book mapper
│   ├── entity/
│   │   └── Player.java        # FPS controller, collision, door interaction
│   ├── github/
│   │   ├── GitHubClient.java  # REST API client (CRUD)
│   │   └── RepoScanner.java   # Remote repo merger
│   ├── ui/
│   │   ├── HUD.java           # Door prompts + last commit
│   │   ├── BookEditor.java    # Retro terminal file editor
│   │   └── SettingsMenu.java  # Settings panel
│   └── audio/
│       └── AudioEngine.java   # OpenAL stub
├── src/main/resources/shaders/
│   ├── basic.vert
│   └── basic.frag
└── src/installer/
    └── mindpalace.iss
```

## Tech Stack

- **Engine:** LWJGL 3.3.3 + OpenGL 3.3 Core
- **Language:** Java 17
- **Build:** Maven 3.9.16
- **Math:** JOML 1.10.5
- **HTTP:** OkHttp 4.12.0
- **JSON:** Gson 2.10.1
- **Font:** Java 2D → OpenGL texture atlas

## GitHub Integration

Token is loaded from Windows Credential Manager automatically. Used for:
- Listing repos
- Reading file contents
- Creating, updating, deleting files
- Commit messages prefixed with "MindPalace:"

Token never leaves your machine except to api.github.com.

## Performance

- Frustum culling (only render rooms in camera view)
- Distance culling (skip rooms >30m away)
- Max 40 books per wall, 3 walls per room
- Intel HD Graphics 510 tested and working

---

Built with LWJGL 3, OpenGL 3.3, and love for code.
