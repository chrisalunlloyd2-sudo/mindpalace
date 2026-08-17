package com.mindpalace.engine;

import com.mindpalace.render.Renderer;
import com.mindpalace.render.FontRenderer;
import com.mindpalace.render.Camera;
import com.mindpalace.world.WorldBuilder;
import com.mindpalace.world.Book;
import com.mindpalace.world.Room;
import com.mindpalace.world.Hallway;
import com.mindpalace.entity.Player;
import com.mindpalace.entity.AgentNPC;
import com.mindpalace.agent.KnowledgeGraph;
import com.mindpalace.world.TodoCrystal;
import com.mindpalace.ui.HUD;
import com.mindpalace.ui.BookEditor;
import com.mindpalace.github.GitHubClient;
import com.mindpalace.audio.AudioEngine;
import com.mindpalace.agent.AgentManager;
import com.mindpalace.agent.AgentChat;
import com.mindpalace.deploy.DeployManager;
import com.mindpalace.deploy.AnimationSystem;
import com.mindpalace.deploy.LiveUpdateManager;
import com.mindpalace.backup.BackupManager;
import com.mindpalace.backup.MemoryManager;
import com.mindpalace.agent.IdleDetector;
import org.joml.Vector3f;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class GameEngine {
    private long window;
    private int width = 1920;
    private int height = 1080;
    private boolean fullscreen = false;
    private boolean running = true;

    private Renderer renderer;
    private FontRenderer fontRenderer;
    private WorldBuilder world;
    private Player player;
    private Input input;
    private HUD hud;
    private BookEditor bookEditor;
    private GitHubClient github;
    private AudioEngine audio;
    private AgentManager agentManager;
    private AgentChat agentChat;
    private DeployManager deployManager;
    private AnimationSystem animationSystem;
    private LiveUpdateManager liveUpdateManager;
    private KnowledgeGraph knowledgeGraph;
    private final List<AgentNPC> npcs = new ArrayList<>();
    private final List<TodoCrystal> crystals = new ArrayList<>();
    private BackupManager backupManager;
    private MemoryManager memoryManager;
    private IdleDetector idleDetector;
    private boolean searchMode;
    private String searchQuery = "";
    private boolean showHelp;
    private GameState state;

    private double lastFrameTime;
    private double accumulator;
    private static final double PHYSICS_DT = 1.0 / 120.0;
    private static final double MAX_FRAME_TIME = 0.25;
    private int fps;
    private double fpsTimer;

    private String loadingText = "";
    private double loadingProgress;
    private boolean loading;

    // Console input reader for book editor
    private BufferedReader consoleReader;
    private String pendingCommand;

    public void run() {
        init();
        startConsoleReader();
        loop();
        cleanup();
    }

    private void init() {
        GLFWErrorCallback.createPrint(System.err).set();

        if (!GLFW.glfwInit())
            throw new IllegalStateException("Failed to initialize GLFW");

        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, GLFW.GLFW_TRUE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
        GLFW.glfwWindowHint(GLFW.GLFW_SAMPLES, 4);

        long monitor = fullscreen ? GLFW.glfwGetPrimaryMonitor() : MemoryUtil.NULL;
        window = GLFW.glfwCreateWindow(width, height, "MindPalace", monitor, MemoryUtil.NULL);

        if (window == MemoryUtil.NULL)
            throw new RuntimeException("Failed to create GLFW window");

        if (!fullscreen) {
            var vidmode = GLFW.glfwGetVideoMode(GLFW.glfwGetPrimaryMonitor());
            GLFW.glfwSetWindowPos(window,
                (vidmode.width() - width) / 2,
                (vidmode.height() - height) / 2);
        }

        GLFW.glfwMakeContextCurrent(window);
        GLFW.glfwSwapInterval(1);
        GLFW.glfwShowWindow(window);
        GL.createCapabilities();

        System.out.println("GPU: " + GL11.glGetString(GL11.GL_RENDERER));
        System.out.println("OpenGL: " + GL11.glGetString(GL11.GL_VERSION));

        state = GameState.LOADING;
        loading = true;
        loadingText = "Initializing engine...";
        loadingProgress = 0.1f;
        renderLoadingFrame();

        input = new Input(window);
        renderer = new Renderer(width, height);
        fontRenderer = new FontRenderer();
        player = new Player();
        hud = new HUD();
        audio = new AudioEngine();
        player.setAudio(audio);

        loadingText = "Scanning repositories...";
        loadingProgress = 0.3f;
        renderLoadingFrame();

        world = new WorldBuilder();
        world.build();

        loadingText = "Populating bookshelves...";
        loadingProgress = 0.7f;
        renderLoadingFrame();

        github = new GitHubClient();
        bookEditor = new BookEditor(github);

        // Start LLM agents from SIMS1337
        agentManager = new AgentManager();
        agentChat = new AgentChat();
        agentManager.setCallbacks(
            msg -> agentChat.addMessage(msg),
            msg -> agentChat.addMessage(msg),
            msg -> System.out.println("[Agent] " + msg)
        );
        agentManager.start();

        // Build the knowledge graph + spawn agent NPCs (bodies in the world)
        knowledgeGraph = new KnowledgeGraph();
        knowledgeGraph.build(world.getRooms());
        spawnNPCs();
        spawnCrystals();
        System.out.println("[NPC] " + npcs.size() + " agents spawned, "
            + crystals.size() + " TODO crystals, KG: "
            + knowledgeGraph.nodeCount() + " nodes / " + knowledgeGraph.edgeCount() + " edges");

        // Deploy system + animations
        deployManager = new DeployManager();
        animationSystem = new AnimationSystem();
        deployManager.setCallback((status, msg) -> {
            System.out.println("[Deploy] " + status + ": " + msg);
            if (status == DeployManager.Status.DEPLOYED && player.getCurrentRoom() != null) {
                Vector3f signPos = player.getCurrentRoom().getDoorPosition();
                if (signPos != null) animationSystem.startDeployAnimation(signPos);
            }
        });

        // Live update system — watches for new repos, animates them into view
        liveUpdateManager = new LiveUpdateManager(github, world);
        liveUpdateManager.setCallback(new LiveUpdateManager.UpdateCallback() {
            @Override
            public void onNewRoom(Room room) {
                // Animate the new room's blocks assembling into view
                Vector3f c = room.getRoomCenter();
                List<Vector3f> targets = new ArrayList<>();
                List<Vector3f> sizes = new ArrayList<>();
                List<Integer> texIds = new ArrayList<>();
                // Floor, walls, ceiling as construction blocks
                targets.add(new Vector3f(c.x, c.y - Room.ROOM_HEIGHT / 2f, c.z));
                sizes.add(new Vector3f(Room.ROOM_WIDTH, 0.1f, Room.ROOM_DEPTH));
                texIds.add(Renderer.TEX_HARDWOOD);
                targets.add(new Vector3f(c.x, c.y, c.z));
                sizes.add(new Vector3f(Room.ROOM_WIDTH, Room.ROOM_HEIGHT, Room.ROOM_DEPTH));
                texIds.add(Renderer.TEX_WALLPAPER);
                animationSystem.startConstructionAnimation(c, targets, sizes, texIds);
                System.out.println("[LiveUpdate] Animating new room into view: " + room.getRepoName());

                // Rebuild the KG so agents can navigate into the new area
                if (knowledgeGraph != null) {
                    knowledgeGraph.build(world.getRooms());
                }
                // Attract agents toward the new room (curiosity)
                for (AgentNPC npc : npcs) {
                    npc.attractTo(room);
                }
            }
            @Override
            public void onNewBook(Room room, String filename) {
                // Book-level updates just pulse the room's sign
                Vector3f dp = room.getDoorPosition();
                if (dp != null) animationSystem.startGlowPulse(dp);
            }
        });
        liveUpdateManager.snapshot();
        liveUpdateManager.start();

        // Idle detection — agents work harder when idle, quiet when playing
        idleDetector = new IdleDetector();

        // Self-managing memory + never-make-code-twice DB
        memoryManager = new MemoryManager(System.getProperty("user.home") + "/AIGEN_SYS/mindpalace_memory");
        memoryManager.start();

        // Cold backup to D: — mirror everything (chats, logs, code, files)
        backupManager = new BackupManager("D:/mindpalace_backup");
        backupManager.start();

        loadingText = "Ready.";
        loadingProgress = 1.0f;
        renderLoadingFrame();

        state = GameState.PLAYING;
        loading = false;
        input.setCursorCaptured(true);
        audio.playAmbientStart();

        lastFrameTime = GLFW.glfwGetTime();
        accumulator = 0.0;
    }

    private void spawnNPCs() {
        // Explorer = tool agent (phi3:mini), Critic = critic agent (tinyllama:1.1b)
        AgentNPC explorer = new AgentNPC("Explorer", AgentNPC.Role.EXPLORER, 42L, knowledgeGraph);
        AgentNPC critic = new AgentNPC("Critic", AgentNPC.Role.CRITIC, 1337L, knowledgeGraph);

        // Attach real SLM brains, gated by the SHARED scheduler (one call at a time)
        com.mindpalace.agent.OllamaClient ollama = new com.mindpalace.agent.OllamaClient();
        com.mindpalace.agent.ModelScheduler sched = agentManager != null ? agentManager.getScheduler() : null;
        if (ollama.isAvailable()) {
            explorer.attachBrain(ollama, sched);
            critic.attachBrain(ollama, sched);
            System.out.println("[NPC] SLM brains attached (" + com.mindpalace.agent.ModelConfig.TOOL_MODEL
                + " + " + com.mindpalace.agent.ModelConfig.CRITIC_MODEL + ") — serialized via scheduler");
        } else {
            System.out.println("[NPC] Ollama unavailable — agents run on KV/KG fallback");
        }

        // Place them at the first two rooms' centers (or hallway start if empty)
        List<Room> rooms = world.getRooms();
        if (!rooms.isEmpty()) {
            Room r0 = rooms.get(0);
            if (r0.getRoomCenter() != null) explorer.setPosition(new Vector3f(r0.getRoomCenter()).add(0, 1.0f, 0));
            if (rooms.size() > 1 && rooms.get(1).getRoomCenter() != null)
                critic.setPosition(new Vector3f(rooms.get(1).getRoomCenter()).add(0, 1.0f, 0));
            else if (r0.getRoomCenter() != null)
                critic.setPosition(new Vector3f(r0.getRoomCenter()).add(1.5f, 1.0f, 0));
        }
        npcs.add(explorer);
        npcs.add(critic);
    }

    private void spawnCrystals() {
        // Scan repo files for TODO/FIXME/HACK comments → spawn a crystal per hit (cap 40)
        int cap = 40;
        for (Room room : world.getRooms()) {
            if (crystals.size() >= cap) break;
            String localPath = room.getLocalPath();
            if (localPath == null) continue;
            for (Book book : room.getBooks()) {
                if (crystals.size() >= cap) break;
                String content = readBookContent(room, book);
                if (content == null) continue;
                String upper = content.toUpperCase();
                int idx = upper.indexOf("TODO");
                if (idx < 0) idx = upper.indexOf("FIXME");
                if (idx < 0) idx = upper.indexOf("HACK");
                if (idx < 0) continue;
                int end = content.indexOf('\n', idx);
                if (end < 0) end = Math.min(content.length(), idx + 60);
                String text = content.substring(idx, Math.min(end, idx + 60)).trim();
                TodoCrystal c = new TodoCrystal(text, room.getRepoName(), book.getFilePath());
                if (book.getWorldX() != 0 || book.getWorldY() != 0 || book.getWorldZ() != 0) {
                    c.setPosition(new Vector3f(book.getWorldX(), book.getWorldY() + 0.3f, book.getWorldZ()));
                } else if (room.getRoomCenter() != null) {
                    c.setPosition(new Vector3f(room.getRoomCenter()).add(0, 0.3f, 0));
                }
                crystals.add(c);
            }
        }
    }

    /** Read a book's file content directly from disk (content is lazily loaded). */
    private String readBookContent(Room room, Book book) {
        String localPath = room.getLocalPath();
        if (localPath == null || book.getFilePath() == null) return null;
        try {
            java.nio.file.Path p = java.nio.file.Path.of(localPath, book.getFilePath());
            if (!java.nio.file.Files.isRegularFile(p)) return null;
            if (java.nio.file.Files.size(p) > 200_000) return null; // skip huge files
            return java.nio.file.Files.readString(p);
        } catch (Exception e) {
            return null;
        }
    }

    private void startConsoleReader() {
        consoleReader = new BufferedReader(new InputStreamReader(System.in));
        Thread t = new Thread(() -> {
            try {
                while (running) {
                    String line = consoleReader.readLine();
                    if (line != null) {
                        synchronized (this) { pendingCommand = line; }
                    }
                }
            } catch (Exception ignored) {}
        }, "console-reader");
        t.setDaemon(true);
        t.start();
    }

    private void renderLoadingFrame() {
        GL11.glClearColor(0.02f, 0.02f, 0.05f, 1.0f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        GLFW.glfwSwapBuffers(window);
        GLFW.glfwPollEvents();
    }

    private void loop() {
        while (running && !GLFW.glfwWindowShouldClose(window)) {
            double currentTime = GLFW.glfwGetTime();
            double frameTime = currentTime - lastFrameTime;
            lastFrameTime = currentTime;

            if (frameTime > MAX_FRAME_TIME) frameTime = MAX_FRAME_TIME;
            accumulator += frameTime;

            while (accumulator >= PHYSICS_DT) {
                update(PHYSICS_DT);
                accumulator -= PHYSICS_DT;
            }

            render(accumulator / PHYSICS_DT);

            fpsTimer += frameTime;
            fps++;
            if (fpsTimer >= 1.0) {
                String title = "MindPalace — " + fps + " FPS";
                if (player.getCurrentRoom() != null)
                    title += " | " + player.getCurrentRoom().getRepoName();
                if (bookEditor.isOpen())
                    title += " | [EDITOR] " + bookEditor.getMode();
                GLFW.glfwSetWindowTitle(window, title);
                fps = 0;
                fpsTimer = 0.0;
            }

            GLFW.glfwPollEvents();
        }
    }

    private void update(double dt) {
        input.update(dt);

        // Idle detection: mark activity on any input (non-draining — does NOT
        // consume mouse deltas, so Player still gets look left/right)
        if (idleDetector != null) {
            if (input.consumeActivity()
                || input.isKeyDown(GLFW.GLFW_KEY_W) || input.isKeyDown(GLFW.GLFW_KEY_A)
                || input.isKeyDown(GLFW.GLFW_KEY_S) || input.isKeyDown(GLFW.GLFW_KEY_D)) {
                idleDetector.markActivity();
            }
            idleDetector.update();
            // Pace the model scheduler: quiet down (15 min) while playing,
            // 5-min floor when idle. Never below the hard floor.
            if (agentManager != null && agentManager.getScheduler() != null) {
                agentManager.getScheduler().setSpacingMs(idleDetector.getSpacingMs());
            }
        }

        // Process console commands for editor
        String cmd = null;
        synchronized (this) {
            if (pendingCommand != null) {
                cmd = pendingCommand;
                pendingCommand = null;
            }
        }
        if (cmd != null) {
            if (searchMode) {
                searchQuery = cmd;
                Room found = findRepoByName(searchQuery);
                if (found != null && found.getDoorPosition() != null) {
                    Vector3f dp = found.getDoorPosition();
                    player.getCamera().setPosition(dp.x + (found.getHallwaySide() == 0 ? 1.5f : -1.5f),
                        player.getCamera().getPosition().y, dp.z);
                    System.out.println("[Search] Jumped to " + found.getDisplayLabel());
                } else {
                    System.out.println("[Search] No repo matching '" + searchQuery + "'");
                }
                searchMode = false;
                input.setCursorCaptured(true);
            } else if (bookEditor.isOpen()) {
                bookEditor.handleCommand(cmd);
            } else if (agentChat != null && agentChat.isOpen() && agentManager != null) {
                // Route to agent chat
                agentChat.addMessage("[You] " + cmd);
                agentManager.onUserChat(cmd);
            }
        }

        // ESC toggles
        if (input.wasKeyPressed(GLFW.GLFW_KEY_ESCAPE)) {
            if (bookEditor.isOpen()) {
                bookEditor.close();
                input.setCursorCaptured(true);
                state = GameState.PLAYING;
            } else if (state == GameState.PLAYING) {
                state = GameState.MENU;
                input.setCursorCaptured(false);
            } else {
                state = GameState.PLAYING;
                input.setCursorCaptured(true);
            }
        }

        if (state == GameState.PLAYING) {
            player.update(dt, input, world);

            // Sync chat-typing state to player (suppress door interaction)
            player.setChatTyping(agentChat != null && agentChat.isTyping());

            // Fog of war: reveal hexes around the player
            world.getFogOfWar().reveal(player.getPosition());

            // Update door animations for all rooms
            for (Room room : world.getRooms()) {
                room.updateDoorAnimation((float) dt);
            }

            // Update agent NPCs (bodies + behaviors)
            for (AgentNPC npc : npcs) {
                npc.update((float) dt, world.getRooms());
                // Surface the SLM's reasoning into the chat HUD (coherent thread)
                String reason = npc.consumeReason();
                if (reason != null && agentChat != null) {
                    agentChat.addMessage("[" + npc.getName() + "] " + reason);
                }
                // Explorer picks up nearby crystals
                if (npc.getRole() == AgentNPC.Role.EXPLORER && npc.getCarriedCrystal() == null) {
                    for (TodoCrystal c : crystals) {
                        if (c.isCarried() || c.getPosition() == null) continue;
                        if (npc.getPosition().distance(c.getPosition()) < 1.5f) {
                            npc.pickUpCrystal(c);
                            System.out.println("[NPC] Explorer picked up TODO: " + c.getLabel());
                            break;
                        }
                    }
                }
            }

            // Update agent context when in a room
            if (player.getCurrentRoom() != null && agentManager != null) {
                agentManager.setContext(player.getCurrentRoom(), null);
            }

            // Book click detection — left click in a room
            if (input.isLeftClick() && player.getCurrentRoom() != null) {
                Book clicked = findBookInSights(player.getCurrentRoom());
                if (clicked != null) {
                    bookEditor.open(clicked, player.getCurrentRoom(),
                        player.getPosition(), player.getLookDirection());
                    // Set agent context to this book
                    if (agentManager != null) {
                        agentManager.setContext(player.getCurrentRoom(), clicked);
                    }
                    state = GameState.BOOK_VIEW;
                    input.setCursorCaptured(false);
                }
            }

            // Enter toggles chat typing (cursor pops up, type, Enter to send)
            if (input.wasKeyPressed(GLFW.GLFW_KEY_ENTER) && agentChat != null && !bookEditor.isOpen()) {
                if (agentChat.isTyping()) {
                    String msg = agentChat.commitInput();
                    if (msg != null && agentManager != null) {
                        agentChat.addMessage("[You] " + msg);
                        agentManager.onUserChat(msg);
                    }
                    input.setCursorCaptured(true);
                } else {
                    agentChat.toggleTyping();
                    input.setCursorCaptured(false);
                }
            }

            // While typing, capture characters + backspace
            if (agentChat != null && agentChat.isTyping()) {
                String typed = input.drainTypedChars();
                if (!typed.isEmpty()) agentChat.appendInput(typed);
                if (input.wasKeyPressed(GLFW.GLFW_KEY_BACKSPACE)) agentChat.backspace();
            }

            // / toggles search mode
            if (input.wasKeyPressed(GLFW.GLFW_KEY_SLASH) && !bookEditor.isOpen()) {
                searchMode = !searchMode;
                searchQuery = "";
                if (searchMode) {
                    input.setCursorCaptured(false);
                    System.out.println("[Search] Type repo name, Enter to jump, ESC to cancel");
                } else {
                    input.setCursorCaptured(true);
                }
            }
        }

        if (input.wasKeyPressed(GLFW.GLFW_KEY_F11))
            toggleFullscreen();

        // F1 toggles help overlay
        if (input.wasKeyPressed(GLFW.GLFW_KEY_F1))
            showHelp = !showHelp;

        // Update animations
        if (animationSystem != null) animationSystem.update((float) dt);

        // Trigger deploy on book save
        if (bookEditor.isOpen() && bookEditor.isDirty() && player.getCurrentRoom() != null) {
            deployManager.deploy(player.getCurrentRoom(),
                "mindpalace: saved " + bookEditor.getCurrentBook().getFilename());
            bookEditor.clearDirty();
        }
    }

    /**
     * Find the book the player is looking at, using each placed book's real
     * world position (matches what is actually drawn on the shelves).
     */
    private Book findBookInSights(Room room) {
        Vector3f origin = player.getPosition();
        Vector3f dir = player.getLookDirection();
        float bookW = 0.10f;
        float bookD = 0.30f;
        float caseHeight = Room.ROOM_HEIGHT - 0.2f;
        float shelfSpacing = (caseHeight - 0.12f) / 3f; // matches WorldBuilder (pt = 0.06)
        float bookH = shelfSpacing * 0.75f;
        Book best = null;
        float bestT = Float.MAX_VALUE;
        for (Book book : room.getBooks()) {
            if (!book.isPlaced()) continue;
            float bx = book.getWorldX(), by = book.getWorldY(), bz = book.getWorldZ();
            Vector3f hit = rayAABB(origin, dir,
                bx - bookW / 2f, by - bookH / 2f, bz - bookD / 2f,
                bx + bookW / 2f, by + bookH / 2f, bz + bookD / 2f);
            if (hit != null) {
                float t = hit.distance(origin);
                if (t < bestT) { bestT = t; best = book; }
            }
        }
        return best;
    }

    private Vector3f rayAABB(Vector3f origin, Vector3f dir, float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        float tMin = 0f, tMax = 10f;
        float[] bounds = {minX, maxX, minY, maxY, minZ, maxZ};
        float[] origins = {origin.x, origin.y, origin.z};
        float[] dirs = {dir.x, dir.y, dir.z};

        for (int i = 0; i < 3; i++) {
            if (Math.abs(dirs[i]) < 0.0001f) {
                if (origins[i] < bounds[i * 2] || origins[i] > bounds[i * 2 + 1]) return null;
            } else {
                float invD = 1f / dirs[i];
                float t0 = (bounds[i * 2] - origins[i]) * invD;
                float t1 = (bounds[i * 2 + 1] - origins[i]) * invD;
                if (t0 > t1) { float tmp = t0; t0 = t1; t1 = tmp; }
                tMin = Math.max(tMin, t0);
                tMax = Math.min(tMax, t1);
                if (tMin > tMax) return null;
            }
        }
        return new Vector3f(origin).add(dir.x * tMin, dir.y * tMin, dir.z * tMin);
    }

    private void render(double alpha) {
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        renderer.beginFrame(player.getCamera());
        world.render(renderer, player.getCamera());

        // Render agent NPCs (bodies) + TODO crystals
        renderNPCs();
        renderCrystals();

        // Render neon sign text
        if (fontRenderer != null && fontRenderer.isReady()) {
            renderNeonSignText();
            renderFloorMap();
            renderScreenHUD();
            renderBookSpineText();
            renderBookTooltip();
            renderBookHighlight();
            renderFloorSigns();
        }

        if (state == GameState.PLAYING) {
            hud.render(renderer, player, world);
        }

        if (bookEditor.isOpen()) {
            bookEditor.render(renderer);
            bookEditor.renderText(fontRenderer, player.getCamera(), width, height);
        }

        if (agentChat != null) {
            agentChat.render(renderer, fontRenderer, player.getCamera(), width, height);
        }

        // Render deploy animations
        if (animationSystem != null && animationSystem.isActive()) {
            animationSystem.render(renderer);
        }

        // Help overlay
        if (showHelp) renderHelpOverlay();

        GLFW.glfwSwapBuffers(window);
    }

    private void renderNPCs() {
        Camera cam = player.getCamera();
        for (AgentNPC npc : npcs) {
            Vector3f p = npc.getPosition();
            if (cam.getPosition().distance(p) > 30f) continue;

            // Body — a small capsule-ish stack of cubes, bobbing while walking
            float bob = (float) Math.sin(npc.getBobPhase()) * 0.05f;
            Vector3f bodyPos = new Vector3f(p.x, p.y + bob, p.z);
            int tex = npc.getBodyTexture();

            // Torso
            renderer.drawCube(bodyPos, new Vector3f(0.4f, 0.6f, 0.25f), tex);
            // Head
            renderer.drawCube(new Vector3f(bodyPos.x, bodyPos.y + 0.45f, bodyPos.z),
                new Vector3f(0.25f, 0.25f, 0.25f), Renderer.TEX_WHITE);
            // Carried crystal (if any) floats above head
            if (npc.getCarriedCrystal() != null) {
                renderer.drawCube(new Vector3f(bodyPos.x, bodyPos.y + 0.8f, bodyPos.z),
                    new Vector3f(0.12f, 0.12f, 0.12f), Renderer.TEX_NEON_GREEN);
            }

            // Name label
            if (fontRenderer != null && fontRenderer.isReady()) {
                Matrix4f proj = cam.getProjectionMatrix((float) width / height);
                Matrix4f view = cam.getViewMatrix();
                Vector3f labelPos = new Vector3f(bodyPos.x, bodyPos.y + 0.9f, bodyPos.z);
                String label = npc.getName() + " [" + npc.getState() + "]";
                Vector3f color = npc.getRole() == AgentNPC.Role.EXPLORER
                    ? new Vector3f(0.2f, 0.9f, 1.0f)
                    : new Vector3f(1.0f, 0.7f, 0.2f);
                fontRenderer.renderBillboard(label, labelPos, 0.05f, color, proj, view, cam.getPosition());
            }
        }
    }

    private void renderCrystals() {
        Camera cam = player.getCamera();
        for (TodoCrystal c : crystals) {
            if (c.isCarried() || c.getPosition() == null) continue;
            Vector3f p = c.getPosition();
            if (cam.getPosition().distance(p) > 20f) continue;

            // Hex crystal — a tall thin cube (height = complexity)
            float h = c.getHeight();
            renderer.drawCube(new Vector3f(p.x, p.y + h / 2f, p.z),
                new Vector3f(0.15f, h, 0.15f), Renderer.TEX_NEON_GREEN);

            // Label
            if (fontRenderer != null && fontRenderer.isReady()) {
                Matrix4f proj = cam.getProjectionMatrix((float) width / height);
                Matrix4f view = cam.getViewMatrix();
                Vector3f labelPos = new Vector3f(p.x, p.y + h + 0.15f, p.z);
                fontRenderer.renderBillboard(c.getLabel(), labelPos, 0.03f,
                    new Vector3f(0.3f, 1.0f, 0.4f), proj, view, cam.getPosition());
            }
        }
    }

    private void renderNeonSignText() {
        Camera cam = player.getCamera();
        Matrix4f proj = cam.getProjectionMatrix((float) width / height);
        Matrix4f view = cam.getViewMatrix();
        Vector3f camPos = cam.getPosition();

        for (Room room : world.getRooms()) {
            Vector3f dp = room.getDoorPosition();
            if (dp == null) continue;
            if (room.isFogged() && !world.getFogOfWar().isRoomRevealed(room)) continue;
            float dist = camPos.distance(dp);
            if (dist > 25f) continue;

            float signY = (room.getFloor() == 0 ? 0 : WorldBuilder.HALLWAY_HEIGHT + 1.0f)
                + WorldBuilder.HALLWAY_HEIGHT - 0.3f;
            float wallX = room.getHallwaySide() == 0
                ? -WorldBuilder.HALLWAY_WIDTH / 2f
                : WorldBuilder.HALLWAY_WIDTH / 2f;
            float offsetX = wallX > 0 ? -0.20f : 0.20f;
            Vector3f signPos = new Vector3f(wallX + offsetX, signY, dp.z);

            Vector3f color = room.isPrivate()
                ? new Vector3f(1.0f, 0.2f, 0.6f)
                : new Vector3f(0.0f, 0.9f, 1.0f);

            String name = room.getRepoName();
            if (name.length() > 14) name = name.substring(0, 12) + "..";

            // Billboard — always faces camera, 200% bigger
            fontRenderer.renderBillboard(name, signPos, 0.24f, color, proj, view, camPos);
        }
    }

    private void renderFloorMap() {
        Camera cam = player.getCamera();
        Matrix4f proj = cam.getProjectionMatrix((float) width / height);
        Matrix4f view = cam.getViewMatrix();
        Vector3f camPos = cam.getPosition();

        for (Room room : world.getRooms()) {
            Vector3f dp = room.getDoorPosition();
            if (dp == null) continue;
            if (room.isFogged() && !world.getFogOfWar().isRoomRevealed(room)) continue;
            float dist = camPos.distance(dp);
            if (dist > 15f) continue;

            // Floor label position — in front of door on the floor
            float wallX = room.getHallwaySide() == 0
                ? -WorldBuilder.HALLWAY_WIDTH / 2f
                : WorldBuilder.HALLWAY_WIDTH / 2f;
            float offsetX = wallX > 0 ? -0.8f : 0.8f;
            float floorY = room.getFloor() == 0 ? 0.02f : WorldBuilder.HALLWAY_HEIGHT + 1.0f + 0.02f;

            Vector3f floorPos = new Vector3f(wallX + offsetX, floorY, dp.z);

            // Short name for floor
            String name = room.getRepoName();
            if (name.length() > 10) name = name.substring(0, 8) + "..";

            Vector3f color = room.isPrivate()
                ? new Vector3f(0.8f, 0.3f, 0.5f)
                : new Vector3f(0.3f, 0.7f, 0.9f);

            fontRenderer.renderFloorText(name, floorPos, 0.10f, color, proj, view);
        }
    }

    private void renderScreenHUD() {
        Camera cam = player.getCamera();
        Matrix4f proj = cam.getProjectionMatrix((float) width / height);
        Matrix4f view = cam.getViewMatrix();
        Vector3f camPos = cam.getPosition();
        Vector3f camFront = cam.getFront();
        Vector3f camRight = new Vector3f(camFront).cross(new Vector3f(0, 1, 0)).normalize();

        // HUD at bottom of view, 3m in front
        Vector3f hudCenter = new Vector3f(camPos).add(
            camFront.x * 3f - camRight.x * 0f,
            camFront.y * 3f - 0.6f,
            camFront.z * 3f - camRight.z * 0f);

        String hotkeys = "WASD:Move  Mouse:Look  Enter:Door  Click:Book  ESC:Menu  F11:Fullscreen";
        fontRenderer.renderBillboard(hotkeys, hudCenter, 0.06f,
            new Vector3f(0.7f, 0.7f, 0.7f), proj, view, camPos);

        // Room info at top
        Vector3f hudTop = new Vector3f(camPos).add(
            camFront.x * 3f, camFront.y * 3f + 0.5f, camFront.z * 3f);
        String roomInfo = player.getCurrentRoom() != null
            ? player.getCurrentRoom().getDisplayLabel()
            : "MindPalace — " + world.getRooms().size() + " rooms";
        fontRenderer.renderBillboard(roomInfo, hudTop, 0.08f,
            new Vector3f(0.0f, 0.9f, 1.0f), proj, view, camPos);

        // Minimap — top-right corner
        renderMinimap(cam, proj, view, camPos, camFront, camRight);
    }

    private void renderMinimap(Camera cam, Matrix4f proj, Matrix4f view,
                                Vector3f camPos, Vector3f camFront, Vector3f camRight) {
        // Position minimap in top-right of view, 2.5m in front
        Vector3f mapCenter = new Vector3f(camPos).add(
            camFront.x * 2.5f + camRight.x * 1.2f,
            camFront.y * 2.5f + 0.4f,
            camFront.z * 2.5f + camRight.z * 1.2f);

        // Floor indicator
        int floor = player.getCurrentRoom() != null ? player.getCurrentRoom().getFloor() : 0;
        String mapLabel = "F" + (floor + 1) + "  " + world.getRooms().size() + " rooms";
        fontRenderer.renderBillboard(mapLabel, mapCenter, 0.05f,
            new Vector3f(0.5f, 0.9f, 0.5f), proj, view, camPos);

        // Room dots — show nearby rooms on current floor
        for (Room room : world.getRooms()) {
            if (room.getFloor() != floor) continue;
            if (room.isFogged() && !world.getFogOfWar().isRoomRevealed(room)) continue;
            Vector3f dp = room.getDoorPosition();
            if (dp == null) continue;
            float dist = camPos.distance(dp);
            if (dist > 20f) continue;

            // Map room position relative to player onto minimap
            float dx = (dp.x - camPos.x) * 0.15f;
            float dz = (dp.z - camPos.z) * 0.15f;
            Vector3f dotPos = new Vector3f(mapCenter).add(
                camRight.x * dx, 0, camFront.x * dz + camFront.z * dz);

            Vector3f dotColor = room.isPrivate()
                ? new Vector3f(1.0f, 0.3f, 0.5f)
                : new Vector3f(0.3f, 0.8f, 1.0f);
            fontRenderer.renderBillboard(".", dotPos, 0.04f, dotColor, proj, view, camPos);
        }

        // Player dot (green)
        fontRenderer.renderBillboard("@", mapCenter, 0.06f,
            new Vector3f(0.0f, 1.0f, 0.0f), proj, view, camPos);
    }

    private void renderBookSpineText() {
        Camera cam = player.getCamera();
        Matrix4f proj = cam.getProjectionMatrix((float) width / height);
        Matrix4f view = cam.getViewMatrix();
        Vector3f camPos = cam.getPosition();

        // Only render spines in current room
        Room room = player.getCurrentRoom();
        if (room == null) return;

        for (Book book : room.getBooks()) {
            float dist = camPos.distance(
                new Vector3f(book.getWorldX(), book.getWorldY(), book.getWorldZ()));
            if (dist > 8f) continue;

            String name = book.getFilename();
            if (name.length() > 10) name = name.substring(0, 8) + "..";
            Vector3f pos = new Vector3f(book.getWorldX(), book.getWorldY(), book.getWorldZ());
            fontRenderer.renderBillboard(name, pos, 0.03f,
                new Vector3f(0.9f, 0.9f, 0.9f), proj, view, camPos);
        }
    }

    private void renderBookTooltip() {
        Camera cam = player.getCamera();
        Matrix4f proj = cam.getProjectionMatrix((float) width / height);
        Matrix4f view = cam.getViewMatrix();
        Vector3f camPos = cam.getPosition();

        Room room = player.getCurrentRoom();
        if (room == null) return;

        // Find the book the player is looking at
        Book looked = findBookInSights(room);
        if (looked == null) return;

        // Position tooltip above the book
        Vector3f tipPos = new Vector3f(
            looked.getWorldX(), looked.getWorldY() + 0.15f, looked.getWorldZ());

        String tip = looked.getFilename() + " | " + looked.getLanguage()
            + " | " + formatSize(looked.getSizeBytes());
        fontRenderer.renderBillboard(tip, tipPos, 0.04f,
            new Vector3f(1.0f, 1.0f, 0.6f), proj, view, camPos);
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0);
        return String.format("%.1fMB", bytes / (1024.0 * 1024.0));
    }

    private void renderBookHighlight() {
        Room room = player.getCurrentRoom();
        if (room == null) return;
        Book looked = findBookInSights(room);
        if (looked == null) return;

        // Draw a glowing outline cube around the book
        Vector3f pos = new Vector3f(looked.getWorldX(), looked.getWorldY(), looked.getWorldZ());
        float s = 0.12f;
        renderer.drawCube(pos, new Vector3f(s, s, s), Renderer.TEX_NEON_AMBER);
    }

    private void renderFloorSigns() {
        Camera cam = player.getCamera();
        Matrix4f proj = cam.getProjectionMatrix((float) width / height);
        Matrix4f view = cam.getViewMatrix();
        Vector3f camPos = cam.getPosition();

        for (Hallway hw : world.getHallways()) {
            Vector3f s = hw.getStart();
            float signY = s.y + WorldBuilder.HALLWAY_HEIGHT - 0.5f;
            float signZ = s.z + 1.5f;
            Vector3f signPos = new Vector3f(0, signY, signZ);
            if (camPos.distance(signPos) > 20f) continue;

            String label = "Floor " + (hw.getFloor() + 1);
            fontRenderer.renderBillboard(label, signPos, 0.15f,
                new Vector3f(0.2f, 1.0f, 0.3f), proj, view, camPos);
        }
    }

    private void renderHelpOverlay() {
        Camera cam = player.getCamera();
        Matrix4f proj = cam.getProjectionMatrix((float) width / height);
        Matrix4f view = cam.getViewMatrix();
        Vector3f camPos = cam.getPosition();
        Vector3f camFront = cam.getFront();
        Vector3f camRight = new Vector3f(camFront).cross(new Vector3f(0, 1, 0)).normalize();

        Vector3f helpCenter = new Vector3f(camPos).add(
            camFront.x * 2.5f, camFront.y * 2.5f, camFront.z * 2.5f);

        String[] lines = {
            "=== CONTROLS ===",
            "WASD: Move    Mouse: Look    Shift: Sprint",
            "Enter: Open/Close Door    Space: Jump",
            "Click: Open Book    ESC: Menu/Close",
            "/: Search Repo    Tab: Agent Chat",
            "F1: Help    F11: Fullscreen",
            "",
            "=== AGENTS ===",
            com.mindpalace.agent.ModelConfig.TOOL_MODEL + " (tool) + " + com.mindpalace.agent.ModelConfig.CRITIC_MODEL + " (critic)",
            "Auto-cycle every 5 min in rooms",
            "Enter to chat, type in-game",
            "",
            "=== EDITOR ===",
            ":e edit  :n new  :d delete  :s suggest  :q quit"
        };

        float y = helpCenter.y + 0.6f;
        for (String line : lines) {
            Vector3f linePos = new Vector3f(helpCenter.x, y, helpCenter.z);
            fontRenderer.renderBillboard(line, linePos, 0.05f,
                new Vector3f(0.7f, 0.9f, 1.0f), proj, view, camPos);
            y -= 0.12f;
        }
    }

    private void toggleFullscreen() {
        fullscreen = !fullscreen;
        long monitor = fullscreen ? GLFW.glfwGetPrimaryMonitor() : MemoryUtil.NULL;
        if (fullscreen) {
            var vidmode = GLFW.glfwGetVideoMode(GLFW.glfwGetPrimaryMonitor());
            width = vidmode.width();
            height = vidmode.height();
        } else {
            width = 1920;
            height = 1080;
        }
        GLFW.glfwSetWindowMonitor(window, monitor, 0, 0, width, height, GLFW.GLFW_DONT_CARE);
        renderer.resize(width, height);
    }

    private Room findRepoByName(String query) {
        String q = query.toLowerCase().trim();
        Room best = null;
        int bestScore = Integer.MAX_VALUE;
        for (Room room : world.getRooms()) {
            String name = room.getRepoName().toLowerCase();
            if (name.equals(q)) return room; // exact match
            if (name.contains(q)) {
                int score = name.length() - q.length();
                if (score < bestScore) { bestScore = score; best = room; }
            }
        }
        return best;
    }

    private void cleanup() {
        if (backupManager != null) backupManager.stop();
        if (memoryManager != null) memoryManager.stop();
        if (liveUpdateManager != null) liveUpdateManager.stop();
        if (deployManager != null) deployManager.shutdown();
        audio.cleanup();
        renderer.cleanup();
        GLFW.glfwDestroyWindow(window);
        GLFW.glfwTerminate();
        GLFW.glfwSetErrorCallback(null).free();
    }
}
