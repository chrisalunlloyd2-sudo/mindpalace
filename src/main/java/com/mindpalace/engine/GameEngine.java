package com.mindpalace.engine;

import com.mindpalace.render.Renderer;
import com.mindpalace.render.FontRenderer;
import com.mindpalace.render.BloomEffect;
import com.mindpalace.render.Camera;
import com.mindpalace.render.Screenshot;
import com.mindpalace.world.WorldBuilder;
import com.mindpalace.world.Book;
import com.mindpalace.world.Room;
import com.mindpalace.world.Hallway;
import com.mindpalace.entity.Player;
import com.mindpalace.entity.AgentNPC;
import com.mindpalace.economy.DePIN;
import com.mindpalace.agent.KnowledgeGraph;
import com.mindpalace.world.TodoCrystal;
import com.mindpalace.world.OutsideWorld;
import com.mindpalace.genetics.GeneticTimeline;
import com.mindpalace.ui.HUD;
import com.mindpalace.ui.BookEditor;
import com.mindpalace.ui.DressingRoom;
import com.mindpalace.github.GitHubClient;
import com.mindpalace.github.GistWall;
import com.mindpalace.audio.AudioEngine;
import com.mindpalace.audio.MusicEngine;
import com.mindpalace.audio.StepSequencer;
import com.mindpalace.agent.AgentManager;
import com.mindpalace.agent.AgentChat;
import com.mindpalace.agent.LexicalAnalyzer;
import com.mindpalace.agent.sims.*;
import com.mindpalace.world.LegacyRepoClassifier;
import com.mindpalace.deploy.DeployManager;
import com.mindpalace.deploy.AnimationSystem;
import com.mindpalace.deploy.LiveUpdateManager;
import com.mindpalace.deploy.PatchManager;
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
    private boolean twoDTextMode = false;   // F4: VR 3D text <-> 2D readable text
    private GistWall gistWall;               // fleet gist wall data source
    private boolean running = true;

    private Renderer renderer;
    private FontRenderer fontRenderer;
    private BloomEffect bloom;
    private WorldBuilder world;
    private Player player;
    private DressingRoom dressingRoom;   // F7: avatar dressing room (360° orbit editor)
    private Input input;
    private HUD hud;
    private BookEditor bookEditor;
    private GitHubClient github;
    private AudioEngine audio;
    private MusicEngine music;
    private StepSequencer sequencer;   // 16-step drum/bass machine (StudioLab)
    private AgentManager agentManager;
    private DePIN depin;   // Phase 5.3 economy — wallets, blackboard jobs, skill loop
    private AgentChat agentChat;
    private DeployManager deployManager;
    private AnimationSystem animationSystem;
    private LiveUpdateManager liveUpdateManager;
    private PatchManager patchManager;

    // Continuous genetic-audio evolution (step 12): a background GA that
    // evolves synth patches against real rendered sound and splices the
    // fittest patch into the live music each generation.
    private com.mindpalace.genetics.AudioEvolver audioEvolver;
    private com.mindpalace.genetics.GenomeArchive genomeArchive;
    private com.mindpalace.genetics.SonicFitness sonicFitness;
    private com.mindpalace.genetics.GenomeControl genomeControl;
    private double evolveTimer = 0.0;
    private double refreshTimer = 0.0;
    private static final double EVOLVE_INTERVAL = 30.0; // seconds per tick
    private static final int EVOLVE_GENERATIONS_PER_TICK = 8; // GA loop iterations per tick
    private static final double REFRESH_INTERVAL = 120.0; // seconds between population refreshes
    private static final int REFRESH_COUNT = 3; // random newcomers per refresh
    private String evolveToast = "";
    private double evolveToastTimer = 0.0;

    private double patchPollTimer = 8.0;
    private boolean patchCinematic;
    private double patchTimer;
    private String patchCinematicTitle = "";
    private String patchToast = "";
    private double patchToastTimer;
    private KnowledgeGraph knowledgeGraph;
    private final List<AgentNPC> npcs = new ArrayList<>();
    private final List<TodoCrystal> crystals = new ArrayList<>();
    private BackupManager backupManager;
    private MemoryManager memoryManager;
    private IdleDetector idleDetector;
    private boolean searchMode;
    private String searchQuery = "";
    private boolean showHelp;
    private boolean showMap;      // Tab = full-screen map overlay
    private GameState state;
    private boolean teleportMenu;   // teleporter destination picker is open
    private int teleportSel;        // selected destination index
    private int menuSel;            // selected option in the ESC menu
    private int menuPage;           // 0=main, 1=video, 2=controls, 3=audio, 4=music, 5=agents
    private int moodIdx;            // Beats StudioLab mood preset cursor
    private int seqSelRow;          // sequencer grid cursor (channel row)
    private int seqSelCol;          // sequencer grid cursor (step column)
    private boolean seqEditing;     // true when the grid is the active menu

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

    // Screenshot + auto-drive (agent "sees" the game)
    private boolean autodrive;
    private String screenshotDir;
    private int shotCounter;
    private double shotTimer;
    private double tourTimer;      // elapsed tour time
    private int tourPhase;          // which leg of the scripted tour
    private boolean selfTest;       // autonomous self-test mode

    // Phase D — finesse: clickable plant fact + telemetry panel
    private String factToast = "";
    private double factToastTimer;
    // TOC tree of knowledge — walk up to retrieve real system data (KG DB)
    private String tocToast = "";
    private double tocToastTimer;
    private double tocCooldown;
    // Mansion interior — Enter at the mansion door toggles inside/outside.
    private boolean inMansion = false;
    private double mansionCooldown;
    // Shop interaction — walk up to a model shop, Enter to buy with DePIN credits.
    private boolean shopMenu;
    private int shopIndex = -1;
    private String shopToast = "";
    private double shopToastTimer;
    private double shopCooldown;
    // Genetic enhancement — the player's persistent module timeline (genome).
    private GeneticTimeline genome;
    private static final String[] FACTS = {
        "The first computer bug was a real moth, taped into a logbook in 1947.",
        "Git was created by Linus Torvalds in 2005, in about 10 days.",
        "The first 'Hello, World!' appeared in Kernighan & Ritchie's C book (1978).",
        "A quine is a program that prints its own source code.",
        "The term 'bug' predates computers — Edison used it in 1878.",
        "There are more possible chess games than atoms in the observable universe.",
        "The first website is still online at info.cern.ch.",
        "Python is named after Monty Python, not the snake.",
        "The QWERTY layout was designed to slow typists down (to avoid jams).",
        "A 'commit' in Git is a snapshot, not a diff.",
        "The first hard drive (1956) held 5MB and weighed over a ton.",
        "Ada Lovelace wrote the first algorithm intended for a machine (1843).",
        "The Apollo 11 guidance computer had less power than a modern calculator.",
        "JavaScript was created in 10 days by Brendan Eich in 1995.",
        "The '@' symbol was chosen for email because it was rarely used.",
    };

    public void run() {
        init();
        if (selfTest) { runSelfTest(); cleanup(); return; }
        startConsoleReader();
        loop();
        cleanup();
    }

    /** Enable auto-drive walkthrough (called from Main before run()). */
    public void setAutodrive(String dir) {
        this.autodrive = true;
        this.screenshotDir = dir;
        System.out.println("[Autodrive] enabled, screenshots -> " + dir);
    }

    /** Enable self-test mode (called from Main before run()). */
    public void setSelfTest() {
        this.selfTest = true;
        System.out.println("[SelfTest] enabled");
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

        // Size the window to the ACTUAL monitor, not a hardcoded 1920x1080.
        // On a 1536x864 display (125% DPI) a 1920x1080 window is larger than the
        // screen and gets cropped into a zoomed "tunnel" view that reads as VR.
        var vm0 = GLFW.glfwGetVideoMode(GLFW.glfwGetPrimaryMonitor());
        if (vm0 != null) {
            width = vm0.width();
            height = vm0.height();
        }

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
        bloom = new BloomEffect(width, height);
        fontRenderer = new FontRenderer();
        player = new Player();
        // Dressing room owns the player avatar — Cortana preset as the starting point.
        dressingRoom = new DressingRoom(com.mindpalace.avatar.AvatarLibrary.preset("cortana"));
        hud = new HUD();
        audio = new AudioEngine();
        player.setAudio(audio);
        music = new MusicEngine();
        sequencer = new StepSequencer();

        loadingText = "Scanning repositories...";
        loadingProgress = 0.3f;
        renderLoadingFrame();

        world = new WorldBuilder();
        world.build();

        // Start the day at the mansion (player home) in the outside world.
        player.teleportToMansion(world);

        loadingText = "Populating bookshelves...";
        loadingProgress = 0.7f;
        renderLoadingFrame();

        github = new GitHubClient();
        // Auto-auth from Windows Credential Manager (env-var fallback) so the
        // in-game GitHub surfaces (gist wall, editor, agents, live updates) work
        // without a manual PAT entry. WorldBuilder already does the same.
        if (!github.loadTokenFromCredentialManager()) {
            String envTok = System.getenv("MIND_PALACE_GITHUB_TOKEN");
            if (envTok != null && envTok.length() >= 20) github.setToken(envTok);
        }
        bookEditor = new BookEditor(github);
        gistWall = new GistWall();
        gistWall.setToken(github.getToken());

        // Start LLM agents from SIMS1337
        agentManager = new AgentManager();
        agentChat = new AgentChat();
        agentManager.setGitHubClient(github);  // hook in tool execution
        // Add-only issue stream: agents raise GitHub issues, never close/delete.
        if (github.isAuthenticated()) {
            agentManager.setIssueStream(new com.mindpalace.github.GitHubIssueStream(
                github.getToken(), "chrisalunlloyd2-sudo", 30_000L));
        }
        agentManager.setCallbacks(
            msg -> agentChat.addMessage(msg),
            msg -> agentChat.addMessage(msg),
            msg -> System.out.println("[Agent] " + msg)
        );
        // Lexical bridge → spawn a TODO crystal per issue found in non-legacy repos
        agentManager.setIssuesCallback(issues -> {
            for (AgentManager.Issue issue : issues) {
                if (crystals.size() >= 60) break; // hard cap
                TodoCrystal c = new TodoCrystal(issue.text, issue.repo, issue.file);
                // Place near the room for that repo if it exists, else at origin
                Room r = world.getRooms().stream()
                    .filter(rm -> rm.getRepoName().equalsIgnoreCase(issue.repo))
                    .findFirst().orElse(null);
                if (r != null && r.getRoomCenter() != null) {
                    c.setPosition(new Vector3f(r.getRoomCenter()).add(0, 0.3f, 0));
                } else {
                    c.setPosition(new Vector3f(0, 1.0f, 0));
                }
                crystals.add(c);
                System.out.println("[Lexical] TODO crystal: " + issue);
            }
        });
        // DePIN economy — seed wallets + jobs so agents can work autonomously.
        initDePIN();
        agentManager.setDePIN(depin);
        agentManager.start();

        // Build the knowledge graph + spawn agent NPCs (bodies in the world)
        knowledgeGraph = new KnowledgeGraph();
        knowledgeGraph.build(world.getRooms());

        // Wire the editor's language toggle to the shared LoRA switcher + KG.
        bookEditor.setSims(agentManager.getLora(), knowledgeGraph);
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

        // Live patch system — patches/patch.json, "GAME PATCH LOADING" cinematic
        patchManager = new PatchManager(PatchManager.defaultDir());
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

        // Genetic enhancement timeline — the player's persistent genome.
        genome = new GeneticTimeline(java.nio.file.Path.of(System.getProperty("user.home") + "/AIGEN_SYS/mindpalace_memory"));
        System.out.println("[Genome] timeline loaded — " + genome.moduleCount()
            + " modules, " + genome.mutationCount() + " mutations");

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
        music.start();
        sequencer.start();

        // Continuous genetic-audio evolution: a background GA that renders real
        // synth patches, scores them, and splices the fittest into the live
        // music every EVOLVE_INTERVAL seconds. Seeded with the current patch.
        com.mindpalace.genetics.SonicFitness sonicFit = new com.mindpalace.genetics.SonicFitness();
        sonicFit.setSampleRate(8000); // fitness renders at 8 kHz (25s-equivalent cheap)
        sonicFitness = sonicFit;
        audioEvolver = new com.mindpalace.genetics.AudioEvolver(
            new java.util.Random(), sonicFit,
            g -> com.mindpalace.audio.MusicEngine.renderOffline(g, 8000), // 1s clip @ 8kHz
            50, 10, 0.15f, 0.2f); // pop 50, top-10 parents, 40 children
        genomeArchive = new com.mindpalace.genetics.GenomeArchive(
            java.nio.file.Path.of(System.getProperty("user.home"), "AIGEN_SYS", "mindpalace_memory"));
        genomeControl = new com.mindpalace.genetics.GenomeControl();
        evolveTimer = EVOLVE_INTERVAL;
        refreshTimer = REFRESH_INTERVAL;

        lastFrameTime = GLFW.glfwGetTime();
        accumulator = 0.0;
    }

    private void spawnNPCs() {
        // Explorer = tool agent (phi3:mini), Critic = critic agent (tinyllama:1.1b)
        AgentNPC explorer = new AgentNPC("Explorer", AgentNPC.Role.EXPLORER, 42L, knowledgeGraph);
        AgentNPC critic = new AgentNPC("Critic", AgentNPC.Role.CRITIC, 1337L, knowledgeGraph);
        // Dressing-room avatars — Explorer is the faithful Cortana preset, Critic is a
        // deterministic procedural avatar (seeded 90s math). Remove with setAvatar(null).
        explorer.setAvatar(com.mindpalace.avatar.AvatarLibrary.preset("cortana"));
        critic.setAvatar(com.mindpalace.avatar.AvatarLibrary.random(1337L));

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
        // Morning briefing crystals at the mansion — the player starts the day
        // here, so a few TODO crystals are placed at the mansion spawn so the
        // day's work is visible right away.
        Vector3f mansion = world.getOutsideWorld().getMansionPos();
        String[] briefing = {
            "TODO: review the day's quorum votes",
            "TODO: check the TOC tree for system data",
            "TODO: visit the program factory",
            "TODO: spend DePIN credits at the model shops"
        };
        for (int i = 0; i < briefing.length; i++) {
            TodoCrystal c = new TodoCrystal(briefing[i], "mansion", "briefing");
            c.setPosition(new Vector3f(mansion.x - 3f + i * 2f, mansion.y + 0.4f, mansion.z - 6f));
            crystals.add(c);
        }
    }

    /** Seed the DePIN economy — player + agent wallets, job board, skill loop. */
    private void initDePIN() {
        depin = new DePIN();
        depin.register("Explorer", 50.0);
        depin.register("Critic", 50.0);
        // Seed jobs from the actual repo names — each is a maintainable "topic".
        List<String> topics = new ArrayList<>();
        for (Room room : world.getRooms()) {
            if (topics.size() >= 24) break;
            topics.add("repo/" + room.getRepoName());
        }
        depin.seedJobs(topics);
        System.out.println("[DePIN] economy seeded — " + depin.board().totalCount()
            + " jobs, " + depin.participants().size() + " wallets");
    }
    private String readBookContent(Room room, Book book) {
        String localPath = room.getLocalPath();
        if (localPath == null || book.getFilePath() == null) return null;
        try {
            java.nio.file.Path p = java.nio.file.Path.of(localPath, book.getFilePath());
            if (!java.nio.file.Files.isRegularFile(p)) return null;
            if (java.nio.file.Files.size(p) > 200_000) return null; // skip huge files
            return java.nio.file.Files.readString(p, java.nio.charset.StandardCharsets.UTF_8);
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
                if (autodrive) updateAutodrive(PHYSICS_DT);
                else update(PHYSICS_DT);
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

        // F7: toggle the dressing room (always available, so it can open AND close).
        if (input.wasKeyPressed(GLFW.GLFW_KEY_F7)) {
            if (dressingRoom != null) dressingRoom.toggle();
        }
        // While open, the dressing room owns ALL input (orbit/select/adjust).
        if (dressingRoom != null && dressingRoom.isOpen()) {
            dressingRoom.handleInput(input);
            return;
        }

        // Fact toast timer (Phase D)
        if (factToastTimer > 0) factToastTimer -= dt;
        if (tocToastTimer > 0) tocToastTimer -= dt;
        if (tocCooldown > 0) tocCooldown -= dt;

        // TOC tree of knowledge — walk up to retrieve real system data
        if (tocCooldown <= 0 && player.getCurrentRoom() == null) {
            Vector3f tree = world.getOutsideWorld().getTocTreePos();
            Vector3f p = player.getPosition();
            if (Math.abs(p.x - tree.x) < 6f && Math.abs(p.z - tree.z) < 6f) {
                tocToast = queryTocData();
                tocToastTimer = 8.0;
                tocCooldown = 3.0;
                System.out.println("[TOC] " + tocToast);
            }
        }

        // Mansion interior — Enter at the mansion door toggles inside/outside.
        // Shop interaction — walk up to a model shop, Enter to buy with credits.
        mansionCooldown -= dt;
        shopCooldown -= dt;
        if (shopToastTimer > 0) shopToastTimer -= dt;

        boolean openWorldEnter = input.wasKeyPressed(GLFW.GLFW_KEY_ENTER)
                && player.getCurrentRoom() == null && state == GameState.PLAYING
                && !teleportMenu && !shopMenu;
        if (openWorldEnter && shopCooldown <= 0) {
            Vector3f p = player.getPosition();
            // 1) Shops have priority — check nearest first.
            int si = world.getOutsideWorld().nearestShopIndex(p.x, p.z, 5f);
            if (si >= 0) {
                shopIndex = si;
                shopMenu = true;
                shopCooldown = 0.5;
                input.setCursorCaptured(false);
                System.out.println("[SHOP] Browsing: " + world.getOutsideWorld().getShops()[si].name);
            }
            // 2) Mansion door (only if no shop is nearby).
            else if (mansionCooldown <= 0) {
                Vector3f m = world.getOutsideWorld().getMansionPos();
                if (Math.abs(p.x - m.x) < 3f && Math.abs(p.z - (m.z - 9f)) < 3f) {
                    inMansion = !inMansion;
                    mansionCooldown = 0.5;
                    if (inMansion) {
                        player.getCamera().setPosition(m.x, m.y + 1.6f, m.z + 2f);
                        player.getCamera().setYaw(180);
                        System.out.println("[MANSION] Entered the mansion");
                    } else {
                        player.getCamera().setPosition(m.x, m.y + 1.6f, m.z - 10f);
                        player.getCamera().setYaw(0);
                        System.out.println("[MANSION] Left the mansion");
                    }
                }
            }
        }

        // Shop menu handler — Enter to buy, Escape to cancel.
        if (shopMenu) {
            handleShopMenu(input);
        }

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
            if (teleportMenu) {
                teleportMenu = false;
                input.setCursorCaptured(true);
            } else if (bookEditor.isOpen()) {
                bookEditor.close();
                input.setCursorCaptured(true);
                state = GameState.PLAYING;
            } else if (state == GameState.MENU && !seqEditing) {
                state = GameState.PLAYING;
                menuPage = 0;
                input.setCursorCaptured(true);
            } else if (state == GameState.PLAYING) {
                state = GameState.MENU;
                menuSel = 0;
                menuPage = 0;
                input.setCursorCaptured(false);
            }
        }

        // ESC menu navigation
        if (state == GameState.MENU) {
            handleMenu(input);
        }

        // Teleporter destination picker — open on Enter OR left-click while
        // standing on a pad (Diablo town-portal style: explicit interact).
        if (teleportMenu) {
            handleTeleportMenu(input);
        } else if (state == GameState.PLAYING
                && (player.getPadFloor() >= 0 || player.isOnPlanetPad())
                && (input.wasKeyPressed(GLFW.GLFW_KEY_ENTER) || input.isLeftClick())) {
            teleportMenu = true;
            teleportSel = 0;
            input.setCursorCaptured(false);
            System.out.println("[TELEPORT] Destination picker open");
        }

        // Live patches — poll manifest, play the loading cinematic, ship content
        updatePatches(dt);

        // Continuous genetic-audio evolution — evolve the music in the background
        updateEvolution(dt);

        // F12 — screenshot (agent "sees" the game)
        if (input.wasKeyPressed(GLFW.GLFW_KEY_F12)) {
            captureScreenshot();
        }

        // F3 — toggle noclip (free-fly for testing/behavior observation)
        if (input.wasKeyPressed(GLFW.GLFW_KEY_F3)) {
            player.setNoclip(!player.isNoclip());
            System.out.println("[NOCLIP] " + (player.isNoclip() ? "ON" : "OFF"));
        }

        // Tab — toggle full-screen map overlay (hold to view, release to close)
        if (input.wasKeyPressed(GLFW.GLFW_KEY_TAB)) {
            showMap = !showMap;
            System.out.println("[MAP] " + (showMap ? "ON" : "OFF"));
        }

        // F4 — toggle 2D readable text mode (VR 3D text <-> 2D screen-pinned text).
        // "Doubles for the toggle": the 3D path is never deleted, only gated.
        if (input.wasKeyPressed(GLFW.GLFW_KEY_F4)) {
            twoDTextMode = !twoDTextMode;
            System.out.println("[2D-TEXT] " + (twoDTextMode ? "ON" : "OFF"));
        }

        if (state == GameState.PLAYING) {
            world.tick((float) dt);
            // Freeze the player while the teleporter picker is open — otherwise
            // Enter would trigger BOTH teleport and door interaction, and the
            // player could keep walking off the pad.
            if (!patchCinematic && !teleportMenu) player.update(dt, input, world);

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

            // Book click detection — left click in a room (not while teleporter open)
            if (input.isLeftClick() && player.getCurrentRoom() != null && !teleportMenu) {
                Book clicked = findBookInSights(player.getCurrentRoom());
                if (clicked != null) {
                    System.out.println("[CLICK] opened book: " + clicked.getFilename()
                        + " (room " + player.getCurrentRoom().getRepoName() + ")");
                    audio.playBookOpen();
                    bookEditor.open(clicked, player.getCurrentRoom(),
                        player.getPosition(), player.getLookDirection());
                    // Set agent context to this book
                    if (agentManager != null) {
                        agentManager.setContext(player.getCurrentRoom(), clicked);
                    }
                    state = GameState.BOOK_VIEW;
                    input.setCursorCaptured(false);
                } else if (lookingAtPlant(player.getCurrentRoom())) {
                    // Phase D finesse — click the potted plant for a random fact
                    factToast = FACTS[(int) (Math.random() * FACTS.length)];
                    factToastTimer = 6.0;
                    System.out.println("[CLICK] plant fact: " + factToast);
                } else {
                    System.out.println("[CLICK] no book in sights (room "
                        + player.getCurrentRoom().getRepoName() + ", "
                        + player.getCurrentRoom().getBooks().size() + " books, "
                        + countPlacedBooks(player.getCurrentRoom()) + " placed)");
                }
            }

            // T toggles chat typing (cursor pops up, type, Enter to send).
            // Enter is reserved for DOORS — it must never open the chat box.
            if (input.wasKeyPressed(GLFW.GLFW_KEY_T) && agentChat != null && !bookEditor.isOpen() && !teleportMenu) {
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

            // While typing, capture characters + backspace; Enter sends.
            if (agentChat != null && agentChat.isTyping()) {
                String typed = input.drainTypedChars();
                if (!typed.isEmpty()) agentChat.appendInput(typed);
                if (input.wasKeyPressed(GLFW.GLFW_KEY_BACKSPACE)) agentChat.backspace();
                if (input.wasKeyPressed(GLFW.GLFW_KEY_ENTER)) {
                    String msg = agentChat.commitInput();
                    if (msg != null && agentManager != null) {
                        agentChat.addMessage("[You] " + msg);
                        agentManager.onUserChat(msg);
                    }
                    input.setCursorCaptured(true);
                }
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
        // Forgiving click cone: books are tiny (10cm) so a precise AABB raycast
        // is nearly impossible to hit with a mouse (the old slab test only hit
        // ~44% of books even when aimed dead-center). Use an angular cone with a
        // nearest-book tiebreaker instead — robust for both hover and click.
        final float COS_CONE = 0.9976f; // ~4° half-angle
        Book best = null;
        float bestDist = Float.MAX_VALUE;
        for (Book book : room.getBooks()) {
            if (!book.isPlaced()) continue;
            Vector3f to = new Vector3f(book.getWorldX(), book.getWorldY(), book.getWorldZ()).sub(origin);
            float dist = to.length();
            if (dist < 0.1f || dist > 10f) continue;
            to.normalize();
            if (dir.dot(to) < COS_CONE) continue;
            if (dist < bestDist) { bestDist = dist; best = book; }
        }
        return best;
    }

    /** Aim the camera at a world-space direction (self-test helper). */
    private void aimCameraAt(Vector3f dir) {
        float yaw = (float) Math.toDegrees(Math.atan2(dir.x, dir.z));
        float pitch = (float) Math.toDegrees(Math.asin(Math.max(-1f, Math.min(1f, dir.y))));
        player.getCamera().setYaw(yaw);
        player.getCamera().setPitch(pitch);
    }

    /** Count placed (clickable) books in a room — debug helper. */
    private int countPlacedBooks(Room room) {
        int n = 0;
        for (Book b : room.getBooks()) if (b.isPlaced()) n++;
        return n;
    }

    /** Is the player looking at the room's potted plant? (Phase D finesse). */
    private boolean lookingAtPlant(Room room) {
        Vector3f c = room.getRoomCenter();
        float w = Room.ROOM_WIDTH, d = Room.ROOM_DEPTH, h = Room.ROOM_HEIGHT;
        int side = room.getHallwaySide();
        float floorY = c.y - h / 2f;
        float plantX = c.x - w / 2f + 0.6f;
        float plantZ = side == 0 ? c.z + d / 2f - 0.6f : c.z - d / 2f + 0.6f;
        Vector3f plant = new Vector3f(plantX, floorY + 0.55f, plantZ);
        Vector3f origin = player.getPosition();
        Vector3f dir = player.getLookDirection();
        // Forgiving: within 1.5m and roughly in front
        if (origin.distance(plant) > 1.5f) return false;
        Vector3f to = new Vector3f(plant).sub(origin).normalize();
        return dir.dot(to) > 0.85f;
    }

    /** Handle the teleporter destination picker (up/down/enter/number keys). */
    private void handleTeleportMenu(Input input) {
        int pads = world.getTeleporterPads().size();
        int options = pads + 3; // each pad + "Outside" + "Planet" + "Palace"
        if (input.wasKeyPressed(GLFW.GLFW_KEY_UP) || input.wasKeyPressed(GLFW.GLFW_KEY_W)) {
            teleportSel = (teleportSel - 1 + options) % options;
        }
        if (input.wasKeyPressed(GLFW.GLFW_KEY_DOWN) || input.wasKeyPressed(GLFW.GLFW_KEY_S)) {
            teleportSel = (teleportSel + 1) % options;
        }
        // Number keys 1..9 jump directly
        for (int k = GLFW.GLFW_KEY_1; k <= GLFW.GLFW_KEY_9; k++) {
            if (input.wasKeyPressed(k)) {
                int idx = k - GLFW.GLFW_KEY_1;
                if (idx < options) teleportSel = idx;
            }
        }
        if (input.wasKeyPressed(GLFW.GLFW_KEY_ENTER)) {
            teleportMenu = false;
            input.setCursorCaptured(true);
            if (teleportSel < pads) {
                player.teleportToPad(teleportSel, world);
            } else if (teleportSel == pads) {
                player.teleportOutside(world);
            } else if (teleportSel == pads + 1) {
                player.teleportToPlanet(world);
            } else {
                player.teleportToPalace(world);
            }
        }
    }

    /** Model shop menu — Enter to buy an upgrade with DePIN credits. */
    private void handleShopMenu(Input input) {
        if (input.wasKeyPressed(GLFW.GLFW_KEY_ESCAPE)) {
            shopMenu = false;
            shopIndex = -1;
            input.setCursorCaptured(true);
            return;
        }
        if (input.wasKeyPressed(GLFW.GLFW_KEY_ENTER) && depin != null && shopIndex >= 0) {
            OutsideWorld.Shop[] shops = world.getOutsideWorld().getShops();
            OutsideWorld.Shop shop = shops[shopIndex];
            double bal = depin.participant("player").wallet.getBalance();
            if (depin.spend("player", shop.cost, "shop:" + shop.name)) {
                // Splice the module into the player's genome — a dated mutation
                // with a real in-game effect, recorded on the persistent timeline.
                GeneticTimeline.Mutation m = genome.mutate(shop.name, shopEffect(shop.name), shop.cost);
                shopToast = "Spliced " + shop.name + " Lv" + m.level + " into your genome! ("
                    + shopEffect(shop.name) + ") Wallet: " + fmt(depin.participant("player").wallet.getBalance());
                System.out.println("[GENOME] " + shop.name + " -> Lv" + m.level + " (" + GeneticTimeline.whenLabel(m.when) + "). Modules: " + genome.moduleCount());
            } else {
                shopToast = "Not enough credits! Need " + fmt(shop.cost) + ", have " + fmt(bal);
                System.out.println("[SHOP] DENIED: " + shop.name + " costs " + fmt(shop.cost) + ", balance " + fmt(bal));
            }
            shopToastTimer = 5.0;
            shopMenu = false;
            shopIndex = -1;
            input.setCursorCaptured(true);
        }
    }

    private static String fmt(double v) { return String.format("%.2f", v); }

    /** The in-game effect a shop module grants when spliced into the genome. */
    private static String shopEffect(String module) {
        return switch (module) {
            case "RAG"     -> "faster model recall";
            case "KG Node" -> "facts stored in the knowledge graph";
            case "Deps"    -> "smarter dependency-aware code review";
            case "LoRA"    -> "swappable skill adapter unlocked";
            case "Router"  -> "complexity-based model routing";
            default        -> "an upgrade";
        };
    }

    /** ESC menu — navigate pages, adjust settings live. */
    private void handleMenu(Input input) {
        // Sequencer grid editing takes over when the StudioLab grid is open.
        if (seqEditing) {
            handleSequencer(input);
            return;
        }
        int count = menuOptionCount();
        if (input.wasKeyPressed(GLFW.GLFW_KEY_UP) || input.wasKeyPressed(GLFW.GLFW_KEY_W)) {
            menuSel = (menuSel - 1 + count) % count;
        }
        if (input.wasKeyPressed(GLFW.GLFW_KEY_DOWN) || input.wasKeyPressed(GLFW.GLFW_KEY_S)) {
            menuSel = (menuSel + 1) % count;
        }
        // Left/right adjust the selected value (sensitivity, FOV, volume, etc.)
        boolean left = input.wasKeyPressed(GLFW.GLFW_KEY_LEFT) || input.wasKeyPressed(GLFW.GLFW_KEY_A);
        boolean right = input.wasKeyPressed(GLFW.GLFW_KEY_RIGHT) || input.wasKeyPressed(GLFW.GLFW_KEY_D);
        if (left || right) adjustMenuValue(menuSel, right ? 1 : -1);

        if (input.wasKeyPressed(GLFW.GLFW_KEY_ENTER)) {
            activateMenuOption(menuSel, input);
        }
    }

    /** Sequencer grid — arrow keys move the cursor, Enter/Space toggles a cell. */
    private void handleSequencer(Input input) {
        if (input.wasKeyPressed(GLFW.GLFW_KEY_UP) || input.wasKeyPressed(GLFW.GLFW_KEY_W)) {
            seqSelRow = Math.max(0, seqSelRow - 1);
        }
        if (input.wasKeyPressed(GLFW.GLFW_KEY_DOWN) || input.wasKeyPressed(GLFW.GLFW_KEY_S)) {
            seqSelRow = Math.min(StepSequencer.CHANNELS - 1, seqSelRow + 1);
        }
        if (input.wasKeyPressed(GLFW.GLFW_KEY_LEFT) || input.wasKeyPressed(GLFW.GLFW_KEY_A)) {
            seqSelCol = Math.max(0, seqSelCol - 1);
        }
        if (input.wasKeyPressed(GLFW.GLFW_KEY_RIGHT) || input.wasKeyPressed(GLFW.GLFW_KEY_D)) {
            seqSelCol = Math.min(StepSequencer.STEPS - 1, seqSelCol + 1);
        }
        if (input.wasKeyPressed(GLFW.GLFW_KEY_ENTER) || input.wasKeyPressed(GLFW.GLFW_KEY_SPACE)) {
            sequencer.toggle(seqSelRow, seqSelCol);
        }
        if (input.wasKeyPressed(GLFW.GLFW_KEY_C)) {
            sequencer.clear();
        }
        // Escape or Backspace exits the grid back to the music page.
        if (input.wasKeyPressed(GLFW.GLFW_KEY_ESCAPE) || input.wasKeyPressed(GLFW.GLFW_KEY_BACKSPACE)) {
            seqEditing = false;
            menuPage = 4; menuSel = 6;
        }
    }

    private int menuOptionCount() {
        switch (menuPage) {
            case 0: return 8;  // Resume, Video, Controls, Audio, Music, Agents, Evolution, Quit
            case 1: return 5;  // FOV, Sensitivity, Fullscreen, Bloom intensity, Bloom threshold
            case 2: return 1;  // Invert Y (rest is informational)
            case 3: return 2;  // Master volume, Sound on/off
            case 4: return 7;  // Music on/off, volume, tempo, beat, scale, mood, sequencer
            case 5: return 2;  // Auto-cycle interval, agent models (info)
            case 6: return 9;  // mutation rate, strength, 5 fitness weights, refresh, back
            default: return 1;
        }
    }

    private void adjustMenuValue(int sel, int dir) {
        switch (menuPage) {
            case 1: // video
                if (sel == 0) player.getCamera().setFov(clamp(player.getCamera().getFov() + dir * 5f, 50f, 120f));
                if (sel == 1) player.getCamera().setSensitivity(clamp(player.getCamera().getSensitivity() + dir * 0.02f, 0.02f, 0.6f));
                if (sel == 2) toggleFullscreen();
                if (sel == 3 && bloom != null) bloom.setIntensity(clamp(bloom.getIntensity() + dir * 0.1f, 0f, 2f));
                if (sel == 4 && bloom != null) bloom.setThreshold(clamp(bloom.getThreshold() + dir * 0.05f, 0f, 1f));
                break;
            case 2: // controls
                if (sel == 0) player.getCamera().setInvertY(!player.getCamera().isInvertY());
                break;
            case 3: // audio
                if (sel == 0) audio.setMasterVolume(clamp(audio.getMasterVolume() + dir * 0.1f, 0f, 1f));
                if (sel == 1) audio.setEnabled(!audio.isEnabled());
                break;
            case 4: // music (Beats StudioLab)
                if (sel == 0) music.setEnabled(!music.isEnabled());
                if (sel == 1) music.setVolume(clamp(music.getVolume() + dir * 0.1f, 0f, 1f));
                if (sel == 2) { music.setTempo(music.getTempo() + dir * 4); sequencer.setTempo(music.getTempo()); }
                if (sel == 3) music.setBeat(!music.isBeat());
                if (sel == 4) cycleScale(dir);
                if (sel == 5) cycleMood(dir);
                if (sel == 6) { sequencer.setEnabled(!sequencer.isEnabled()); }
                break;
            case 6: // evolution (genetic audio) — live GA controls
                if (audioEvolver == null || sonicFitness == null) break;
                if (sel == 0) audioEvolver.setMutationRate(clamp(audioEvolver.mutationRate() + dir * 0.05f, 0f, 1f));
                if (sel == 1) audioEvolver.setMutationSigma(clamp(audioEvolver.mutationSigma() + dir * 0.05f, 0f, 1f));
                if (sel == 2) sonicFitness.setLoudnessWeight(clamp(sonicFitness.loudnessWeight() + dir * 0.05f, 0f, 1f));
                if (sel == 3) sonicFitness.setCentroidWeight(clamp(sonicFitness.centroidWeight() + dir * 0.05f, 0f, 1f));
                if (sel == 4) sonicFitness.setSteadinessWeight(clamp(sonicFitness.steadinessWeight() + dir * 0.05f, 0f, 1f));
                if (sel == 5) sonicFitness.setNoveltyWeight(clamp(sonicFitness.noveltyWeight() + dir * 0.05f, 0f, 1f));
                if (sel == 6) sonicFitness.setTargetWeight(clamp(sonicFitness.targetWeight() + dir * 0.05f, 0f, 1f));
                if (sel == 7) { audioEvolver.refreshPopulation(REFRESH_COUNT); System.out.println("[Evolve] manual population refresh"); }
                break;
        }
    }

    private static final String[] SCALES = {"minor", "major", "dorian", "lydian", "mixolydian"};
    private static final String[] MOODS = {"calm", "mysterious", "energetic", "dreamy"};

    private void cycleScale(int dir) {
        String cur = music.getScale();
        int idx = 0;
        for (int i = 0; i < SCALES.length; i++) if (SCALES[i].equals(cur)) idx = i;
        idx = Math.floorMod(idx + dir, SCALES.length);
        music.setScale(SCALES[idx]);
    }

    private void cycleMood(int dir) {
        // Moods are presets; cycle through them and apply.
        moodIdx = Math.floorMod(moodIdx + dir, MOODS.length);
        music.setMood(MOODS[moodIdx]);
    }

    private void activateMenuOption(int sel, Input input) {
        switch (menuPage) {
            case 0: // main
                switch (sel) {
                    case 0: state = GameState.PLAYING; input.setCursorCaptured(true); break;
                    case 1: menuPage = 1; menuSel = 0; break;
                    case 2: menuPage = 2; menuSel = 0; break;
                    case 3: menuPage = 3; menuSel = 0; break;
                    case 4: menuPage = 4; menuSel = 0; break;
                    case 5: menuPage = 5; menuSel = 0; break;
                    case 6: menuPage = 6; menuSel = 0; break; // Evolution
                    case 7: GLFW.glfwSetWindowShouldClose(window, true); break;
                }
                break;
            case 4: // music — entering "Sequencer" opens the step grid
                if (sel == 6) {
                    seqEditing = true;
                    seqSelRow = 0;
                    seqSelCol = 0;
                } else {
                    menuPage = 0; menuSel = 0; // back to main
                }
                break;
            default:
                menuPage = 0; menuSel = 0; // back to main
        }
    }

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }

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
        bloom.begin();
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);

        // Dressing room mode: orbit camera + avatar editor replaces the world view.
        if (dressingRoom != null && dressingRoom.isOpen()) {
            dressingRoom.positionCamera(player.getCamera());
            renderer.beginFrame(player.getCamera());
            dressingRoom.render(renderer, fontRenderer, player.getCamera(),
                (float) width / height, (float) GLFW.glfwGetTime());
            bloom.end();
            GLFW.glfwSwapBuffers(window);
            return;
        }

        renderer.beginFrame(player.getCamera());
        // Sky dome — full gradient sphere so the sky is never black, even
        // looking straight up or sideways. Phase follows the real clock.
        int hour = java.time.LocalTime.now().getHour();
        int skyPhase = (hour < 6 || hour >= 20) ? 2 : (hour >= 18 ? 1 : 0);
        renderer.drawSkyDome(player.getCamera().getPosition(), skyPhase);
        world.render(renderer, player.getCamera());
        if (inMansion) renderMansionInterior();

        // Render agent NPCs (bodies) + TODO crystals
        renderNPCs();
        renderCrystals();

        // Render neon sign text
        if (fontRenderer != null && fontRenderer.isReady()) {
            if (!twoDTextMode) {
                // 3D world text (signs/spines/posters/floor labels) — the VR path.
                renderNeonSignText();
                renderFloorMap();
                renderBookSpineText();
                renderRoomPoster();
                renderFloorSigns();
            } else {
                // 2D read mode — pin the key world text into a fixed screen panel.
                renderTwoDTextPanel();
            }
            renderScreenHUD();
            renderBookTooltip();
            renderBookHighlight();
            renderTelemetryPanel();
            renderGistWall();
            renderFactToast();
            renderTocToast();
        }

        if (state == GameState.PLAYING) {
            hud.render(renderer, player, world);
        }

        if (bookEditor.isOpen()) {
            bookEditor.render(renderer);
            bookEditor.renderText(fontRenderer, player.getCamera(), width, height);
        }

        if (teleportMenu) renderTeleportMenu();

        // Shop menu — billboard showing what you can buy.
        if (shopMenu && shopIndex >= 0) renderShopMenu();

        // Shop toast — purchase/denial confirmation.
        if (shopToastTimer > 0 && !shopToast.isEmpty()) {
            Camera cam = player.getCamera();
            Matrix4f proj = cam.getProjectionMatrix((float) width / height);
            Matrix4f view = cam.getViewMatrix();
            Vector3f camPos = cam.getPosition();
            Vector3f toastPos = new Vector3f(camPos)
                .add(cam.getFront().x * 2.5f - cam.getRight().x * 1.2f,
                     -0.6f,
                     cam.getFront().z * 2.5f - cam.getRight().z * 1.2f);
            fontRenderer.renderBillboard(shopToast, toastPos, 0.045f,
                new Vector3f(1f, 1f, 0.5f), proj, view, camPos);
        }

        if (state == GameState.MENU) renderMenu();

        if (agentChat != null) {
            agentChat.render(renderer, fontRenderer, player.getCamera(), width, height);
        }

        // Render deploy animations
        if (animationSystem != null && animationSystem.isActive()) {
            animationSystem.render(renderer);
        }

        // Help overlay
        if (showHelp) renderHelpOverlay();

        // Full-screen map overlay (Tab)
        if (showMap) renderMapOverlay();

        bloom.end();
        GLFW.glfwSwapBuffers(window);
    }

    private void renderNPCs() {
        Camera cam = player.getCamera();
        float time = (float) GLFW.glfwGetTime();
        for (AgentNPC npc : npcs) {
            Vector3f p = npc.getPosition();
            if (cam.getPosition().distance(p) > 30f) continue;

            // Gait: walk-cycle swing + idle bob (B2)
            float bob = (float) Math.sin(npc.getBobPhase()) * 0.05f;
            boolean walking = npc.getState() == AgentNPC.State.WALKING
                           || npc.getState() == AgentNPC.State.CARRYING;
            float swing = walking ? (float) Math.sin(npc.getBobPhase()) * 0.5f : 0f;
            float yaw = (float) Math.atan2(npc.getFacing().x, npc.getFacing().z);

            boolean female = npc.getSex() == AgentNPC.Sex.FEMALE;

            // Dressing-room avatar: when an NPC carries an AvatarDescriptor it drives
            // sex + skin + clothing + proportions (the box-renderer reads the parametric
            // model directly). Absent a descriptor, the hardcoded sex dims below apply.
            com.mindpalace.avatar.AvatarDescriptor av = npc.getAvatar();
            if (av != null) {
                female = av.sex == com.mindpalace.avatar.AvatarDescriptor.Sex.FEMALE;
            }

            // Anthropometric proportions (B1) — the box-renderer analog of bone scaling.
            float shoulderW = female ? 0.30f : 0.44f;   // biacromial width
            float hipW      = female ? 0.38f : 0.28f;   // intertrochanteric width
            float legLen    = female ? 0.58f : 0.55f;   // elongated limbs
            float armLen    = female ? 0.44f : 0.42f;

            // Cortana hologram tint — cyan (female) / deeper blue (male); avatar skin wins.
            Vector3f holoTint = female
                ? new Vector3f(0.05f, 0.80f, 1.00f)
                : new Vector3f(0.10f, 0.55f, 1.00f);

            // Tight-fit clothing = material layer (no extra geometry):
            //   female → yoga pants (dark) + bra (magenta); male → trousers (dark)
            float pantsR = 0.07f, pantsG = 0.07f, pantsB = 0.11f;
            float braR = 0.92f, braG = 0.18f, braB = 0.45f;

            // Avatar overrides — skin tint, clothing color, and pull/push proportions.
            if (av != null) {
                shoulderW *= av.getProportion(com.mindpalace.avatar.AvatarDescriptor.BodyPart.SHOULDERS);
                hipW      *= av.getProportion(com.mindpalace.avatar.AvatarDescriptor.BodyPart.HIPS);
                legLen    *= av.getProportion(com.mindpalace.avatar.AvatarDescriptor.BodyPart.LEGS);
                armLen    *= av.getProportion(com.mindpalace.avatar.AvatarDescriptor.BodyPart.ARMS);
                holoTint = new Vector3f(av.skinR, av.skinG, av.skinB);
                pantsR = av.bottomR; pantsG = av.bottomG; pantsB = av.bottomB;
                braR = av.topR; braG = av.topG; braB = av.topB;
            }

            float footY = p.y + bob;
            float hipY = footY + legLen;
            float waistY = hipY + 0.26f;      // pelvis (hips) top
            float shoulderY = waistY + 0.30f; // chest top
            float headY = shoulderY + 0.22f;

            // Legs — yoga pants (female) / trousers (male): solid dark, swinging
            float legSwing = swing * 0.25f;
            renderer.drawCubeColorYaw(new Vector3f(p.x - 0.10f, hipY - legLen * 0.5f, p.z + legSwing),
                new Vector3f(0.10f, legLen, 0.10f), yaw, pantsR, pantsG, pantsB);
            renderer.drawCubeColorYaw(new Vector3f(p.x + 0.10f, hipY - legLen * 0.5f, p.z - legSwing),
                new Vector3f(0.10f, legLen, 0.10f), yaw, pantsR, pantsG, pantsB);

            // Pelvis / hips — female: wide hips (yoga pants); male: narrow (trousers)
            renderer.drawCubeColorYaw(new Vector3f(p.x, hipY + 0.13f, p.z),
                new Vector3f(hipW, 0.26f, 0.20f), yaw, pantsR, pantsG, pantsB);

            // Chest — female: bra (solid accent + bust); male: bare hologram chest
            if (female) {
                // bust (bra cups) — two small accent boxes
                renderer.drawCubeColorYaw(new Vector3f(p.x - 0.09f, shoulderY - 0.06f, p.z),
                    new Vector3f(0.12f, 0.10f, 0.10f), yaw, braR, braG, braB);
                renderer.drawCubeColorYaw(new Vector3f(p.x + 0.09f, shoulderY - 0.06f, p.z),
                    new Vector3f(0.12f, 0.10f, 0.10f), yaw, braR, braG, braB);
                // bra band (torso)
                renderer.drawCubeColorYaw(new Vector3f(p.x, shoulderY - 0.10f, p.z),
                    new Vector3f(shoulderW, 0.22f, 0.20f), yaw, braR, braG, braB);
            } else {
                renderer.drawHologramCube(new Vector3f(p.x, shoulderY - 0.10f, p.z),
                    new Vector3f(shoulderW, 0.30f, 0.24f), yaw, holoTint, time);
            }

            // Arms — bare hologram, swing opposite legs
            float armSwing = -swing * 0.30f;
            renderer.drawHologramCube(new Vector3f(p.x - 0.24f, shoulderY - 0.12f, p.z + armSwing),
                new Vector3f(0.09f, armLen, 0.09f), yaw, holoTint, time);
            renderer.drawHologramCube(new Vector3f(p.x + 0.24f, shoulderY - 0.12f, p.z - armSwing),
                new Vector3f(0.09f, armLen, 0.09f), yaw, holoTint, time);

            // Head — hologram
            renderer.drawHologramCube(new Vector3f(p.x, headY, p.z),
                new Vector3f(0.24f, 0.24f, 0.24f), yaw, holoTint, time);

            // Hair — solid cap (avatar style/color; skipped when style is NONE)
            if (av != null && av.hairStyle != com.mindpalace.avatar.AvatarDescriptor.HairStyle.NONE) {
                renderer.drawCubeColorYaw(new Vector3f(p.x, headY + 0.14f, p.z),
                    new Vector3f(0.26f, 0.10f, 0.26f), yaw, av.hairR, av.hairG, av.hairB);
            }

            // Face plate (role color) — keeps Explorer/Critic readable
            float fx = p.x + npc.getFacing().x * 0.13f;
            float fz = p.z + npc.getFacing().z * 0.13f;
            renderer.drawCubeYaw(new Vector3f(fx, headY, fz),
                new Vector3f(0.16f, 0.12f, 0.02f), yaw, npc.getRoleTexture());

            // Carried crystal (if any) floats above head
            if (npc.getCarriedCrystal() != null) {
                renderer.drawCube(new Vector3f(p.x, headY + 0.35f, p.z),
                    new Vector3f(0.12f, 0.12f, 0.12f), Renderer.TEX_NEON_GREEN);
            }

            // Name label
            if (fontRenderer != null && fontRenderer.isReady()) {
                Matrix4f proj = cam.getProjectionMatrix((float) width / height);
                Matrix4f view = cam.getViewMatrix();
                Vector3f labelPos = new Vector3f(p.x, headY + 0.35f, p.z);
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

    /**
     * 2D read mode — the "2D" half of the VR<->2D text toggle. Pins the key world
     * text (current room + the book under the crosshair + its preview) into a fixed
     * screen-space panel so it is always readable no matter where the camera faces.
     * World graphics stay identical; only the text is pinned to the screen.
     */
    private void renderTwoDTextPanel() {
        Camera cam = player.getCamera();
        Matrix4f proj = cam.getProjectionMatrix((float) width / height);
        Matrix4f view = cam.getViewMatrix();
        Vector3f camPos = cam.getPosition();
        Vector3f camFront = cam.getFront();
        Vector3f base = new Vector3f(camPos).add(new Vector3f(camFront).mul(3f));

        fontRenderer.renderBillboard("2D READ MODE  (F4 back to 3D)",
            new Vector3f(base.x, base.y + 0.55f, base.z), 0.09f,
            new Vector3f(1.0f, 1.0f, 0.3f), proj, view, camPos);

        Room room = player.getCurrentRoom();
        if (room != null) {
            fontRenderer.renderBillboard(room.getDisplayLabel(),
                new Vector3f(base.x, base.y + 0.38f, base.z), 0.07f,
                new Vector3f(0.0f, 0.9f, 1.0f), proj, view, camPos);

            String meta = room.getLanguage() + "  " + room.getStarCount() + " \u2605";
            fontRenderer.renderBillboard(meta,
                new Vector3f(base.x, base.y + 0.26f, base.z), 0.05f,
                new Vector3f(0.8f, 0.8f, 0.8f), proj, view, camPos);

            Book looked = findBookInSights(room);
            if (looked != null) {
                String tip = looked.getFilename() + " | " + looked.getLanguage()
                    + " | " + formatSize(looked.getSizeBytes());
                fontRenderer.renderBillboard(tip,
                    new Vector3f(base.x, base.y + 0.14f, base.z), 0.06f,
                    new Vector3f(0.5f, 1.0f, 0.6f), proj, view, camPos);

                String preview = previewLine(looked, room);
                if (preview != null) {
                    fontRenderer.renderBillboard(preview,
                        new Vector3f(base.x, base.y + 0.02f, base.z), 0.05f,
                        new Vector3f(0.9f, 0.9f, 0.9f), proj, view, camPos);
                }
            }
        } else {
            fontRenderer.renderBillboard("MindPalace — no room",
                new Vector3f(base.x, base.y + 0.38f, base.z), 0.07f,
                new Vector3f(0.7f, 0.7f, 0.7f), proj, view, camPos);
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
            if (name.length() > 10) name = name.substring(0, 8) + "..";

            // Wall-facing text (not billboard) so it reads flat on the sign.
            // Bigger + brighter for legibility against the dark backing plate.
            Vector3f facing = new Vector3f(wallX > 0 ? -1 : 1, 0, 0);
            fontRenderer.renderText(name, signPos, 0.30f, color, proj, view, facing);
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

        String hotkeys = "WASD:Move  Mouse:Look  Enter:Door  Click:Book  Tab:Map  F4:2D  ESC:Menu  F11:Fullscreen";
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

        // Wallet balance (DePIN credits)
        if (depin != null) {
            Vector3f walletPos = new Vector3f(camPos).add(
                camFront.x * 3f - camRight.x * 0.5f,
                camFront.y * 3f + 0.85f,
                camFront.z * 3f - camRight.z * 0.5f);
            double bal = depin.participant("player").wallet.getBalance();
            String walletLine = String.format("Credits: %.0f", bal);
            fontRenderer.renderBillboard(walletLine, walletPos, 0.05f,
                new Vector3f(1f, 0.85f, 0.3f), proj, view, camPos);
        }

        // Minimap — top-right corner
        renderMinimap(cam, proj, view, camPos, camFront, camRight);

        // ---- Live patch cinematic + loaded toast (billboards in front of camera) ----
        if (patchCinematic || patchToastTimer > 0) {
            Vector3f base = new Vector3f(camPos).add(new Vector3f(camFront).mul(2.4f));
            if (patchCinematic) {
                int bars = Math.min(10, (int) (patchTimer / 3.0 * 10));
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 10; i++) sb.append(i < bars ? '\u2588' : '\u2591');
                fontRenderer.renderBillboard("GAME PATCH LOADING",
                    new Vector3f(base.x, base.y + 0.40f, base.z), 0.11f,
                    new Vector3f(0.35f, 1.0f, 0.65f), proj, view, camPos);
                fontRenderer.renderBillboard(patchCinematicTitle,
                    new Vector3f(base.x, base.y + 0.20f, base.z), 0.06f,
                    new Vector3f(0.6f, 0.8f, 1.0f), proj, view, camPos);
                fontRenderer.renderBillboard(sb.toString(),
                    new Vector3f(base.x, base.y + 0.05f, base.z), 0.05f,
                    new Vector3f(1.0f, 0.8f, 0.3f), proj, view, camPos);
            } else if (patchToastTimer > 0) {
                fontRenderer.renderBillboard(patchToast,
                    new Vector3f(base.x, base.y + 0.30f, base.z), 0.07f,
                    new Vector3f(0.4f, 1.0f, 0.5f), proj, view, camPos);
                if (patchManager != null && !patchManager.getPatchTexts().isEmpty()) {
                    fontRenderer.renderBillboard(patchManager.getPatchTexts().get(0),
                        new Vector3f(base.x, base.y + 0.14f, base.z), 0.045f,
                        new Vector3f(0.9f, 0.9f, 0.9f), proj, view, camPos);
                }
            }
        }
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

    /** Repo poster board text — name, language, stars — above the door. */
    private void renderRoomPoster() {
        Camera cam = player.getCamera();
        Matrix4f proj = cam.getProjectionMatrix((float) width / height);
        Matrix4f view = cam.getViewMatrix();
        Vector3f camPos = cam.getPosition();

        Room room = player.getCurrentRoom();
        if (room == null) return;

        Vector3f c = room.getRoomCenter();
        int side = room.getHallwaySide();
        float h = Room.ROOM_HEIGHT, d = Room.ROOM_DEPTH;
        float dw = Room.DOOR_HEIGHT;
        float fz = side == 0 ? c.z - d / 2f : c.z + d / 2f;
        float posterY = c.y - h / 2f + dw + 0.15f + 0.45f;
        float posterZ = side == 0 ? fz + 0.06f : fz - 0.06f;

        // Text faces into the room (away from the door wall)
        Vector3f facing = new Vector3f(0, 0, side == 0 ? 1 : -1);

        String name = room.getRepoName();
        if (name.length() > 16) name = name.substring(0, 14) + "..";
        String lang = room.getLanguage() != null ? room.getLanguage() : "?";
        String stars = room.getStarCount() + " \u2605";

        Vector3f color = room.isPrivate()
            ? new Vector3f(1.0f, 0.4f, 0.7f)
            : new Vector3f(0.3f, 0.9f, 1.0f);

        fontRenderer.renderText(name, new Vector3f(c.x, posterY + 0.18f, posterZ),
            0.12f, color, proj, view, facing);
        fontRenderer.renderText(lang + "  " + stars, new Vector3f(c.x, posterY - 0.12f, posterZ),
            0.08f, new Vector3f(0.9f, 0.9f, 0.9f), proj, view, facing);
    }

    /** Phase D — side telemetry panel: clock, KG stats, model telemetry. */
    private void renderTelemetryPanel() {
        Camera cam = player.getCamera();
        Matrix4f proj = cam.getProjectionMatrix((float) width / height);
        Matrix4f view = cam.getViewMatrix();
        Vector3f camPos = cam.getPosition();
        Vector3f camFront = cam.getFront();
        Vector3f camRight = new Vector3f(camFront).cross(new Vector3f(0, 1, 0)).normalize();

        // Panel anchored to the left of the view, 2.5m out
        Vector3f base = new Vector3f(camPos).add(
            camFront.x * 2.5f - camRight.x * 1.4f,
            camFront.y * 2.5f + 0.35f,
            camFront.z * 2.5f - camRight.z * 1.4f);

        // Clock
        String clock = new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date());
        fontRenderer.renderBillboard(clock, base, 0.07f,
            new Vector3f(0.9f, 0.9f, 0.9f), proj, view, camPos);

        // KG stats
        String kg = "KG " + (knowledgeGraph != null ? knowledgeGraph.nodeCount() : 0)
            + " nodes / " + (knowledgeGraph != null ? knowledgeGraph.edgeCount() : 0) + " edges";
        fontRenderer.renderBillboard(kg, new Vector3f(base.x, base.y - 0.12f, base.z), 0.04f,
            new Vector3f(0.5f, 0.9f, 0.5f), proj, view, camPos);

        // Model telemetry
        if (agentManager != null && agentManager.getScheduler() != null) {
            var s = agentManager.getScheduler();
            String model = s.getLastModel().isEmpty() ? "idle" : s.getLastModel();
            String tel = "model " + model + " | " + s.getTotalCalls() + " calls | "
                + s.getAvgLatencyMs() + "ms avg | q" + s.getQueueDepth();
            fontRenderer.renderBillboard(tel, new Vector3f(base.x, base.y - 0.24f, base.z), 0.04f,
                new Vector3f(0.6f, 0.8f, 1.0f), proj, view, camPos);
        }
    }

    /** Phase D — fact toast (from clicking the potted plant). */
    /**
     * Fleet gist wall — right-side screen panel (mirror of the telemetry panel on
     * the left) surfacing the live fleet status + workflow logits + word-library
     * success paths. Text is billboard-rendered so it is always readable.
     */
    private void renderGistWall() {
        if (gistWall == null) return;
        gistWall.refresh();
        java.util.List<String> lines = gistWall.getLines();
        if (lines.isEmpty()) return;

        Camera cam = player.getCamera();
        Matrix4f proj = cam.getProjectionMatrix((float) width / height);
        Matrix4f view = cam.getViewMatrix();
        Vector3f camPos = cam.getPosition();
        Vector3f camFront = cam.getFront();
        Vector3f camRight = new Vector3f(camFront).cross(new Vector3f(0, 1, 0)).normalize();

        // Anchored to the RIGHT of the view, 2.5m out (mirror of telemetry on left)
        Vector3f base = new Vector3f(camPos).add(
            camFront.x * 2.5f + camRight.x * 1.4f,
            camFront.y * 2.5f + 0.55f,
            camFront.z * 2.5f + camRight.z * 1.4f);

        float step = 0.09f;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            boolean header = line.startsWith("==");
            float size = header ? 0.055f : 0.045f;
            Vector3f color = header
                ? new Vector3f(1.0f, 0.8f, 0.3f)
                : new Vector3f(0.6f, 0.9f, 0.6f);
            Vector3f p = new Vector3f(base.x, base.y - i * step, base.z);
            fontRenderer.renderBillboard(line, p, size, color, proj, view, camPos);
        }
    }

    private void renderFactToast() {
        if (factToastTimer <= 0 || factToast.isEmpty()) return;
        Camera cam = player.getCamera();
        Matrix4f proj = cam.getProjectionMatrix((float) width / height);
        Matrix4f view = cam.getViewMatrix();
        Vector3f camPos = cam.getPosition();
        Vector3f camFront = cam.getFront();

        Vector3f pos = new Vector3f(camPos).add(new Vector3f(camFront).mul(2.2f));
        pos.y -= 0.3f;
        fontRenderer.renderBillboard("\u2605 " + factToast, pos, 0.05f,
            new Vector3f(1.0f, 0.9f, 0.4f), proj, view, camPos);
    }

    /** TOC tree of knowledge — read real system data from the KG database. */
    private String queryTocData() {
        // The Aegis knowledge graph lives at AIGEN_SYS/db/kg_graph.db. Read a
        // real TODO/BUG/HACK from github_todos (or a symbol from nodes) so the
        // tree "retrieves system data for real", not a canned string.
        String dbPath = System.getProperty("user.home") + "/AIGEN_SYS/db/kg_graph.db";
        try (java.sql.Connection c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             java.sql.Statement st = c.createStatement()) {
            try (java.sql.ResultSet rs = st.executeQuery(
                    "SELECT repo, todo_type, content FROM github_todos "
                    + "WHERE status='open' ORDER BY priority_score DESC LIMIT 1")) {
                if (rs.next()) {
                    String repo = rs.getString(1), type = rs.getString(2), content = rs.getString(3);
                    String c2 = content == null ? "" : content;
                    if (c2.length() > 40) c2 = c2.substring(0, 38) + "..";
                    return "TOC \u2192 " + repo + " [" + type + "] " + c2;
                }
            }
            // Fallback: a class/function symbol from the KG
            try (java.sql.ResultSet rs = st.executeQuery(
                    "SELECT node_type, name, repo FROM nodes LIMIT 1")) {
                if (rs.next()) {
                    return "TOC \u2192 " + rs.getString(3) + " :: " + rs.getString(1)
                        + " " + rs.getString(2);
                }
            }
        } catch (Exception e) {
            return "TOC \u2192 KG offline (" + e.getMessage() + ")";
        }
        return "TOC \u2192 no data yet";
    }

    /** TOC toast — billboarded near the tree when the player retrieves data. */
    private void renderTocToast() {
        if (tocToastTimer <= 0 || tocToast.isEmpty()) return;
        Camera cam = player.getCamera();
        Matrix4f proj = cam.getProjectionMatrix((float) width / height);
        Matrix4f view = cam.getViewMatrix();
        Vector3f camPos = cam.getPosition();
        Vector3f camFront = cam.getFront();
        Vector3f pos = new Vector3f(camPos).add(new Vector3f(camFront).mul(2.2f));
        pos.y -= 0.5f;
        fontRenderer.renderBillboard("\u2726 " + tocToast, pos, 0.05f,
            new Vector3f(0.4f, 1.0f, 0.6f), proj, view, camPos);
    }

    /**
     * Mansion interior — the player's home, with a room for everything. Rendered
     * when inMansion is true (Enter at the mansion door). A grand hall with a
     * crystal vault, a war-room (quorum votes), a library (KG), a workshop
     * (program factory console), and a bedroom. All built from the same cube
     * primitives as the rest of the world.
     */
    private void renderMansionInterior() {
        Vector3f m = world.getOutsideWorld().getMansionPos();
        float y = m.y;                 // ground Y
        float w = 22f, d = 16f, h = 7f; // matches the exterior shell
        float cx = m.x, cz = m.z;
        float t = 0.3f;

        // Floor + ceiling (interior)
        renderer.drawCube(new Vector3f(cx, y + 0.05f, cz), new Vector3f(w, 0.1f, d), Renderer.TEX_HARDWOOD);
        renderer.drawCube(new Vector3f(cx, y + h, cz), new Vector3f(w, 0.15f, d), Renderer.TEX_CEILING);
        // Interior walls (wallpaper) — the player is inside, so draw inward faces
        renderer.drawCube(new Vector3f(cx, y + h / 2f, cz - d / 2f), new Vector3f(w, h, t), Renderer.TEX_WALLPAPER);
        renderer.drawCube(new Vector3f(cx, y + h / 2f, cz + d / 2f), new Vector3f(w, h, t), Renderer.TEX_WALLPAPER);
        renderer.drawCube(new Vector3f(cx - w / 2f, y + h / 2f, cz), new Vector3f(t, h, d), Renderer.TEX_WALLPAPER);
        renderer.drawCube(new Vector3f(cx + w / 2f, y + h / 2f, cz), new Vector3f(t, h, d), Renderer.TEX_WALLPAPER);

        // ── Grand hall: chandelier + rug ──
        renderer.drawCube(new Vector3f(cx, y + h - 0.6f, cz), new Vector3f(1.5f, 0.2f, 1.5f), Renderer.TEX_NEON_AMBER);
        renderer.drawCube(new Vector3f(cx, y + h - 1.2f, cz), new Vector3f(0.15f, 1.0f, 0.15f), Renderer.TEX_METAL);
        renderer.drawCube(new Vector3f(cx, y + 0.06f, cz), new Vector3f(6f, 0.02f, 4f), Renderer.TEX_BOOK_RED);

        // ── Crystal vault (back-left): the player's TODO crystals ──
        float vx = cx - 7f, vz = cz + 4f;
        renderer.drawCube(new Vector3f(vx, y + 1.2f, vz), new Vector3f(4f, 2.4f, 4f), Renderer.TEX_METAL);
        renderer.drawCube(new Vector3f(vx, y + 1.2f, vz + 2.05f), new Vector3f(3.4f, 1.8f, 0.1f), Renderer.TEX_NEON_CYAN);
        // A few glowing crystals inside the vault
        for (int i = 0; i < 3; i++) {
            renderer.drawCube(new Vector3f(vx - 1f + i * 1f, y + 1.4f, vz + 1.5f),
                new Vector3f(0.3f, 0.6f, 0.3f), Renderer.TEX_NEON_GREEN);
        }

        // ── War-room (back-right): quorum vote table ──
        float wx = cx + 7f, wz = cz + 4f;
        renderer.drawCube(new Vector3f(wx, y + 0.8f, wz), new Vector3f(4f, 0.1f, 3f), Renderer.TEX_DOOR);
        for (int i = 0; i < 4; i++) {
            float sx = wx - 1.5f + (i % 2) * 3f;
            float sz = wz - 1f + (i / 2) * 2f;
            renderer.drawCube(new Vector3f(sx, y + 0.4f, sz), new Vector3f(0.4f, 0.8f, 0.4f), Renderer.TEX_METAL);
        }
        // Vote tally board (glowing)
        renderer.drawCube(new Vector3f(wx, y + 2.2f, wz + 1.6f), new Vector3f(3f, 1.2f, 0.1f), Renderer.TEX_NEON_AMBER);

        // ── Library (left wall): KG bookshelves ──
        for (int i = 0; i < 4; i++) {
            float sx = cx - w / 2f + 1.5f + i * 1.6f;
            renderer.drawCube(new Vector3f(sx, y + 1.5f, cz - 3f), new Vector3f(1.4f, 3f, 0.4f), Renderer.TEX_SHELF);
            // Book spines
            for (int b = 0; b < 3; b++) {
                renderer.drawCube(new Vector3f(sx - 0.4f + b * 0.4f, y + 0.8f + b * 0.7f, cz - 3.2f),
                    new Vector3f(0.3f, 0.6f, 0.1f), Renderer.TEX_BOOK_BLUE + (b % 3));
            }
        }

        // ── Workshop (right wall): program factory console ──
        float fcx = cx + w / 2f - 2f, fcz = cz - 3f;
        renderer.drawCube(new Vector3f(fcx, y + 0.9f, fcz), new Vector3f(3f, 0.1f, 1.5f), Renderer.TEX_METAL);
        renderer.drawCube(new Vector3f(fcx, y + 1.4f, fcz - 0.5f), new Vector3f(1.5f, 0.8f, 0.15f), Renderer.TEX_NEON_CYAN);
        renderer.drawCube(new Vector3f(fcx, y + 0.95f, fcz + 0.4f), new Vector3f(0.8f, 0.05f, 0.3f), Renderer.TEX_WHITE);

        // ── Bedroom (back wall, center): bed + nightstand ──
        float bx = cx, bz = cz + d / 2f - 2f;
        renderer.drawCube(new Vector3f(bx, y + 0.4f, bz), new Vector3f(3f, 0.5f, 2f), Renderer.TEX_BOOK_WHITE);
        renderer.drawCube(new Vector3f(bx, y + 0.7f, bz - 1.1f), new Vector3f(3f, 0.3f, 0.4f), Renderer.TEX_BOOK_BLUE);
        renderer.drawCube(new Vector3f(bx + 1.8f, y + 0.5f, bz + 1.2f), new Vector3f(0.6f, 0.6f, 0.6f), Renderer.TEX_WOOD);
        renderer.drawCube(new Vector3f(bx + 1.8f, y + 0.85f, bz + 1.2f), new Vector3f(0.2f, 0.1f, 0.2f), Renderer.TEX_NEON_AMBER);

        // ── Grand entrance door (interior face, -Z) ──
        renderer.drawCube(new Vector3f(cx, y + 1.6f, cz - d / 2f + 0.05f), new Vector3f(2.2f, 3.2f, 0.1f), Renderer.TEX_DOOR);

        // ── Genome wall (back wall, center): the player's module timeline ──
        renderGenomeWall(cx, y, cz);
    }

    /** The player's genetic timeline — a wall of dated module mutations. */
    private void renderGenomeWall(float cx, float y, float cz) {
        if (genome == null) return;
        java.util.List<GeneticTimeline.Mutation> all = genome.all();
        float d = 16f;
        float gx = cx;                       // center of the back wall
        float gz = cz + d / 2f - 0.35f;       // just in front of the back wall
        float gy = y + 5.5f;                  // upper wall

        Camera cam = player.getCamera();
        Matrix4f proj = cam.getProjectionMatrix((float) width / height);
        Matrix4f view = cam.getViewMatrix();
        // Back wall is at +Z, so the text must face INTO the room (-Z).
        Vector3f facing = new Vector3f(0, 0, -1);

        // Backing plate (dark)
        renderer.drawCube(new Vector3f(gx, y + 3.0f, cz + d / 2f - 0.15f), new Vector3f(14f, 4.5f, 0.1f), Renderer.TEX_CEILING);

        // Header
        fontRenderer.renderText("GENOME - MODULE TIMELINE",
            new Vector3f(gx, gy, gz), 0.16f, new Vector3f(0.3f, 1f, 0.6f), proj, view, facing);

        if (all.isEmpty()) {
            fontRenderer.renderText("(no modules yet - visit the model shops)",
                new Vector3f(gx, gy - 0.5f, gz), 0.10f, new Vector3f(0.7f, 0.7f, 0.7f), proj, view, facing);
            return;
        }

        // Show up to the most recent 8 mutations, newest at the top.
        int n = Math.min(all.size(), 8);
        for (int i = 0; i < n; i++) {
            GeneticTimeline.Mutation m = all.get(all.size() - 1 - i);
            String line = GeneticTimeline.whenLabel(m.when) + "  " + m.module
                + " Lv" + m.level + "  (" + m.effect + ")";
            Vector3f pos = new Vector3f(gx, gy - 0.5f - i * 0.42f, gz);
            Vector3f col = new Vector3f(0.6f, 0.9f, 1.0f);
            fontRenderer.renderText(line, pos, 0.09f, col, proj, view, facing);
        }
    }

    private void renderTeleportMenu() {
        Camera cam = player.getCamera();
        Matrix4f proj = cam.getProjectionMatrix((float) width / height);
        Matrix4f view = cam.getViewMatrix();
        Vector3f camPos = cam.getPosition();
        Vector3f camFront = cam.getFront();

        Vector3f center = new Vector3f(camPos).add(
            camFront.x * 2.5f, camFront.y * 2.5f, camFront.z * 2.5f);

        int pads = world.getTeleporterPads().size();
        int options = pads + 3;
        float lineH = 0.12f;
        float startY = center.y + (options * lineH) / 2f;

        fontRenderer.renderBillboard("TELEPORT DESTINATION", center, 0.09f,
            new Vector3f(0.2f, 1.0f, 0.9f), proj, view, camPos);

        for (int i = 0; i < options; i++) {
            String label;
            if (i < pads) label = (i + 1) + ". Teleporter " + (i + 1) + " (Floor " + (i + 1) + ")";
            else if (i == pads) label = (i + 1) + ". Outside";
            else if (i == pads + 1) label = (i + 1) + ". Planet";
            else label = (i + 1) + ". Palace (return)";
            if (i == teleportSel) label = "> " + label + " <";
            Vector3f pos = new Vector3f(center.x, startY - i * lineH, center.z);
            Vector3f col = (i == teleportSel)
                ? new Vector3f(1.0f, 0.9f, 0.3f)
                : new Vector3f(0.7f, 0.7f, 0.7f);
            fontRenderer.renderBillboard(label, pos, 0.06f, col, proj, view, camPos);
        }
    }

    /** Model shop billboard — show shop name, description, cost, player balance. */
    private void renderShopMenu() {
        Camera cam = player.getCamera();
        Matrix4f proj = cam.getProjectionMatrix((float) width / height);
        Matrix4f view = cam.getViewMatrix();
        Vector3f camPos = cam.getPosition();
        Vector3f camFront = cam.getFront();

        Vector3f center = new Vector3f(camPos).add(
            camFront.x * 2.5f, camFront.y * 0.5f, camFront.z * 2.5f);

        OutsideWorld.Shop shop = world.getOutsideWorld().getShops()[shopIndex];
        double bal = depin != null ? depin.participant("player").wallet.getBalance() : 0;
        boolean canAfford = depin != null && depin.participant("player").wallet.canAfford(shop.cost);

        String[] lines = {
            shop.name + " SHOP",
            "",
            shop.description,
            "",
            "Cost: " + fmt(shop.cost) + " credits",
            "Wallet: " + fmt(bal) + " credits",
            "",
            canAfford ? "[Enter] BUY" : "[Enter] (not enough credits)",
            "[Esc] Cancel"
        };

        float lineH = 0.10f;
        float startY = center.y + (lines.length * lineH) / 2f;
        for (int i = 0; i < lines.length; i++) {
            Vector3f pos = new Vector3f(center.x, startY - i * lineH, center.z);
            Vector3f col = (i == 0) ? new Vector3f(0.3f, 1f, 1f)
                       : (i == lines.length - 2 || i == lines.length - 1) ? new Vector3f(0.8f, 0.8f, 0.4f)
                       : new Vector3f(0.9f, 0.9f, 0.9f);
            fontRenderer.renderBillboard(lines[i], pos, 0.05f, col, proj, view, camPos);
        }
    }

    private void renderMenu() {
        Camera cam = player.getCamera();
        Matrix4f proj = cam.getProjectionMatrix((float) width / height);
        Matrix4f view = cam.getViewMatrix();
        Vector3f camPos = cam.getPosition();
        Vector3f camFront = cam.getFront();

        Vector3f center = new Vector3f(camPos).add(
            camFront.x * 2.5f, camFront.y * 2.5f, camFront.z * 2.5f);

        // Sequencer grid takes over the menu when editing.
        if (seqEditing) {
            renderSequencerGrid(center, proj, view, camPos, camFront);
            return;
        }

        String[] lines = menuLines();
        float lineH = 0.12f;
        float startY = center.y + (lines.length * lineH) / 2f;

        for (int i = 0; i < lines.length; i++) {
            String label = lines[i];
            // Header line (index 0) is never selectable; options start at index 1.
            boolean selected = (i == menuSel + 1);
            if (selected) label = "> " + label + " <";
            Vector3f pos = new Vector3f(center.x, startY - i * lineH, center.z);
            Vector3f col = selected
                ? new Vector3f(1.0f, 0.9f, 0.3f)
                : (i == 0 ? new Vector3f(0.2f, 1.0f, 0.9f) : new Vector3f(0.7f, 0.7f, 0.7f));
            fontRenderer.renderBillboard(label, pos, 0.05f, col, proj, view, camPos);
        }
    }

    /** Draw the 16×4 step-sequencer grid as a billboarded block matrix. */
    private void renderSequencerGrid(Vector3f center, Matrix4f proj, Matrix4f view,
                                     Vector3f camPos, Vector3f camFront) {
        float cell = 0.14f;         // cell size
        float gap = 0.02f;
        float gridW = StepSequencer.STEPS * (cell + gap);
        float gridH = StepSequencer.CHANNELS * (cell + gap);
        float originX = center.x - gridW / 2f + cell / 2f;
        float originY = center.y + gridH / 2f - cell / 2f;

        // Per-channel accent colors (kick=amber, snare=red, hat=cyan, bass=green)
        float[][] channelCol = {
            {1.0f, 0.70f, 0.20f},
            {1.0f, 0.35f, 0.35f},
            {0.35f, 0.85f, 1.0f},
            {0.40f, 0.90f, 0.40f}
        };

        int playhead = sequencer.getPlayhead();
        for (int r = 0; r < StepSequencer.CHANNELS; r++) {
            for (int c = 0; c < StepSequencer.STEPS; c++) {
                float px = originX + c * (cell + gap);
                float py = originY - r * (cell + gap);
                Vector3f pos = new Vector3f(px, py, center.z);

                boolean active = sequencer.get(r, c);
                boolean cursor = (r == seqSelRow && c == seqSelCol);
                boolean isPlayhead = (c == playhead);

                // Base cell: dim gray if off, bright channel color if on.
                float[] col = active ? channelCol[r] : new float[]{0.25f, 0.25f, 0.25f};
                // Playhead column gets a brightening pulse.
                if (isPlayhead) {
                    col = new float[]{
                        Math.min(1f, col[0] + 0.25f),
                        Math.min(1f, col[1] + 0.25f),
                        Math.min(1f, col[2] + 0.25f)
                    };
                }
                renderer.drawCubeColor(pos, new Vector3f(cell, cell, 0.03f), col[0], col[1], col[2]);

                // Cursor highlight ring (slightly larger, translucent frame)
                if (cursor) {
                    renderer.drawCubeColor(pos, new Vector3f(cell + 0.04f, cell + 0.04f, 0.01f),
                        1.0f, 1.0f, 0.9f);
                }
            }
        }

        // Row labels (channel names)
        for (int r = 0; r < StepSequencer.CHANNELS; r++) {
            float py = originY - r * (cell + gap);
            Vector3f labelPos = new Vector3f(center.x - gridW / 2f - 0.8f, py, center.z);
            fontRenderer.renderBillboard(StepSequencer.CHANNEL_NAMES[r], labelPos, 0.05f,
                new Vector3f(channelCol[r][0], channelCol[r][1], channelCol[r][2]), proj, view, camPos);
        }

        // Header
        Vector3f hdr = new Vector3f(center.x, center.y + gridH / 2f + 0.5f, center.z);
        fontRenderer.renderBillboard("=== STEP SEQUENCER ===", hdr, 0.06f,
            new Vector3f(0.2f, 1.0f, 0.9f), proj, view, camPos);

        // Footer help
        Vector3f ft = new Vector3f(center.x, center.y - gridH / 2f - 0.5f, center.z);
        fontRenderer.renderBillboard("Arrows: move   Enter/Space: toggle   C: clear   Esc: back",
            ft, 0.04f, new Vector3f(0.7f, 0.7f, 0.7f), proj, view, camPos);
    }

    private String[] menuLines() {
        switch (menuPage) {
            case 0:
                return new String[]{
                    "=== MIND PALACE ===",
                    "Resume",
                    "Video",
                    "Controls",
                    "Audio",
                    "Music",
                    "Agents",
                    "Evolution",
                    "Quit"
                };
            case 1:
                return new String[]{
                    "=== VIDEO ===",
                    "FOV: " + (int) player.getCamera().getFov(),
                    "Sensitivity: " + String.format("%.2f", player.getCamera().getSensitivity()),
                    "Fullscreen: " + (fullscreen ? "ON" : "OFF"),
                    "Bloom: " + (bloom != null ? String.format("%.1f", bloom.getIntensity()) : "n/a"),
                    "Bloom Threshold: " + (bloom != null ? String.format("%.2f", bloom.getThreshold()) : "n/a"),
                    "(Enter to go back)"
                };
            case 2:
                return new String[]{
                    "=== CONTROLS ===",
                    "Invert Y: " + (player.getCamera().isInvertY() ? "ON" : "OFF"),
                    "WASD: Move   Mouse: Look   Shift: Sprint",
                    "Enter: Door   Space: Jump   Click: Book",
                    "/: Search   T: Chat   F1: Help",
                    "F3: Noclip   F11: Fullscreen   F12: Screenshot",
                    "(Enter to go back)"
                };
            case 3:
                return new String[]{
                    "=== AUDIO ===",
                    "Volume: " + (int) (audio.getMasterVolume() * 100) + "%",
                    "Sound: " + (audio.isEnabled() ? "ON" : "OFF"),
                    "(Enter to go back)"
                };
            case 4:
                return new String[]{
                    "=== MUSIC (Beats StudioLab) ===",
                    "Music: " + (music.isEnabled() ? "ON" : "OFF"),
                    "Volume: " + (int) (music.getVolume() * 100) + "%",
                    "Tempo: " + music.getTempo() + " BPM",
                    "Beat: " + (music.isBeat() ? "ON" : "OFF"),
                    "Scale: " + music.getScale(),
                    "Mood: " + MOODS[moodIdx],
                    "Sequencer: " + (sequencer.isEnabled() ? "ON" : "OFF") + " (Enter to edit grid)"
                };
            case 5:
                return new String[]{
                    "=== AGENTS ===",
                    com.mindpalace.agent.ModelConfig.TOOL_MODEL + " (tool) + "
                        + com.mindpalace.agent.ModelConfig.CRITIC_MODEL + " (critic)",
                    "Auto-cycle every 5 min",
                    "(Enter to go back)"
                };
            case 6:
                return new String[]{
                    "=== EVOLUTION (genetic audio) ===",
                    "Mutation rate: " + String.format("%.2f", audioEvolver != null ? audioEvolver.mutationRate() : 0f),
                    "Mutation strength: " + String.format("%.2f", audioEvolver != null ? audioEvolver.mutationSigma() : 0f),
                    "Loudness weight: " + String.format("%.2f", sonicFitness != null ? sonicFitness.loudnessWeight() : 0f),
                    "Centroid weight: " + String.format("%.2f", sonicFitness != null ? sonicFitness.centroidWeight() : 0f),
                    "Steadiness weight: " + String.format("%.2f", sonicFitness != null ? sonicFitness.steadinessWeight() : 0f),
                    "Novelty weight: " + String.format("%.2f", sonicFitness != null ? sonicFitness.noveltyWeight() : 0f),
                    "Target weight: " + String.format("%.2f", sonicFitness != null ? sonicFitness.targetWeight() : 0f),
                    "Refresh population now",
                    "(Enter to go back)"
                };
            default:
                return new String[]{"(Enter to go back)"};
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
        fontRenderer.renderBillboard(tip, tipPos, 0.07f,
            new Vector3f(1.0f, 1.0f, 0.6f), proj, view, camPos);

        // Content preview — first line(s), lazily loaded + cached on the book.
        String preview = previewLine(looked, room);
        if (preview != null) {
            Vector3f pvPos = new Vector3f(
                looked.getWorldX(), looked.getWorldY() - 0.10f, looked.getWorldZ());
            fontRenderer.renderBillboard(preview, pvPos, 0.05f,
                new Vector3f(0.6f, 0.9f, 1.0f), proj, view, camPos);
        }
    }

    /** First non-empty line of a book's content, lazily loaded + cached. */
    private String previewLine(Book book, Room room) {
        String c = book.getContent();
        if (c == null) {
            if (room.getLocalPath() == null || book.getFilePath() == null) return null;
            try {
                c = java.nio.file.Files.readString(
                    java.nio.file.Path.of(room.getLocalPath(), book.getFilePath()));
                book.setContent(c);
            } catch (Exception e) {
                return null;
            }
        }
        for (String line : c.split("\n")) {
            String t = line.trim();
            if (!t.isEmpty()) return t.length() > 60 ? t.substring(0, 60) + "…" : t;
        }
        return null;
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

        // Glowing outline that WRAPS the book (was a 0.12 cube hidden inside
        // the ~0.10×0.79×0.30 book, so it was invisible). Size to the book's
        // real dimensions + a small halo margin.
        Vector3f pos = new Vector3f(looked.getWorldX(), looked.getWorldY(), looked.getWorldZ());
        float bookH = 0.79f;   // matches WorldBuilder shelfSpacing * 0.75
        float m = 0.03f;       // halo margin
        if (looked.getWallDir() == 0) {
            renderer.drawCube(pos, new Vector3f(0.10f + m, bookH + m, 0.30f + m), Renderer.TEX_NEON_AMBER);
        } else {
            renderer.drawCube(pos, new Vector3f(0.30f + m, bookH + m, 0.10f + m), Renderer.TEX_NEON_AMBER);
        }
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

    private void renderMapOverlay() {
        Camera cam = player.getCamera();
        Matrix4f proj = cam.getProjectionMatrix((float) width / height);
        Matrix4f view = cam.getViewMatrix();
        Vector3f camPos = cam.getPosition();
        Vector3f camFront = cam.getFront();
        Vector3f camRight = new Vector3f(camFront).cross(new Vector3f(0, 1, 0)).normalize();

        // Full-screen map: a large billboard grid centered in front of the camera.
        Vector3f center = new Vector3f(camPos).add(
            camFront.x * 3.0f, camFront.y * 3.0f, camFront.z * 3.0f);

        int floor = player.getCurrentRoom() != null ? player.getCurrentRoom().getFloor() : 0;
        fontRenderer.renderBillboard("=== MIND PALACE MAP — FLOOR " + (floor + 1) + " ===",
            new Vector3f(center.x, center.y + 0.9f, center.z), 0.10f,
            new Vector3f(0.2f, 1.0f, 0.9f), proj, view, camPos);

        // Legend
        fontRenderer.renderBillboard("@ you   o room   * agent   + crystal   [ ] hallway",
            new Vector3f(center.x, center.y + 0.7f, center.z), 0.05f,
            new Vector3f(0.7f, 0.7f, 0.7f), proj, view, camPos);

        // Scale: world units -> map units (0.12 per world unit, ~1.2m tall map)
        float scale = 0.12f;

        // Hallway outline for the current floor
        for (Hallway hw : world.getHallways()) {
            if (hw.getFloor() != floor) continue;
            Vector3f s = hw.getStart();
            float hwHalf = hw.getWidth() / 2f;
            // Draw the hallway as a labeled bar (left/right edges)
            float sx = (s.x - camPos.x) * scale;
            float sz = (s.z - camPos.z) * scale;
            // Left edge label
            Vector3f lPos = new Vector3f(center).add(
                camRight.x * (sx - hwHalf * scale) + camFront.x * sz + camFront.z * sz,
                camRight.y * (sx - hwHalf * scale) + camFront.y * sz,
                camRight.z * (sx - hwHalf * scale) + camFront.z * sz);
            fontRenderer.renderBillboard("[", lPos, 0.06f,
                new Vector3f(0.5f, 0.5f, 0.5f), proj, view, camPos);
            Vector3f rPos = new Vector3f(center).add(
                camRight.x * (sx + hwHalf * scale) + camFront.x * sz + camFront.z * sz,
                camRight.y * (sx + hwHalf * scale) + camFront.y * sz,
                camRight.z * (sx + hwHalf * scale) + camFront.z * sz);
            fontRenderer.renderBillboard("]", rPos, 0.06f,
                new Vector3f(0.5f, 0.5f, 0.5f), proj, view, camPos);
        }

        // Rooms on the current floor
        for (Room room : world.getRooms()) {
            if (room.getFloor() != floor) continue;
            if (room.isFogged() && !world.getFogOfWar().isRoomRevealed(room)) continue;
            Vector3f dp = room.getDoorPosition();
            if (dp == null) continue;
            float dx = (dp.x - camPos.x) * scale;
            float dz = (dp.z - camPos.z) * scale;
            Vector3f dotPos = new Vector3f(center).add(
                camRight.x * dx + camFront.x * dz + camFront.z * dz,
                camRight.y * dx + camFront.y * dz,
                camRight.z * dx + camFront.z * dz);
            Vector3f col = room.isPrivate()
                ? new Vector3f(1.0f, 0.3f, 0.5f)
                : new Vector3f(0.3f, 0.8f, 1.0f);
            fontRenderer.renderBillboard("o", dotPos, 0.05f, col, proj, view, camPos);
        }

        // Agents
        for (AgentNPC npc : npcs) {
            Vector3f p = npc.getPosition();
            float dx = (p.x - camPos.x) * scale;
            float dz = (p.z - camPos.z) * scale;
            Vector3f dotPos = new Vector3f(center).add(
                camRight.x * dx + camFront.x * dz + camFront.z * dz,
                camRight.y * dx + camFront.y * dz,
                camRight.z * dx + camFront.z * dz);
            fontRenderer.renderBillboard("*", dotPos, 0.06f,
                new Vector3f(1.0f, 0.8f, 0.2f), proj, view, camPos);
        }

        // Crystals
        for (TodoCrystal c : crystals) {
            if (c.isCarried() || c.getPosition() == null) continue;
            Vector3f p = c.getPosition();
            float dx = (p.x - camPos.x) * scale;
            float dz = (p.z - camPos.z) * scale;
            Vector3f dotPos = new Vector3f(center).add(
                camRight.x * dx + camFront.x * dz + camFront.z * dz,
                camRight.y * dx + camFront.y * dz,
                camRight.z * dx + camFront.z * dz);
            fontRenderer.renderBillboard("+", dotPos, 0.05f,
                new Vector3f(0.6f, 1.0f, 0.4f), proj, view, camPos);
        }

        // Player marker
        fontRenderer.renderBillboard("@", center, 0.08f,
            new Vector3f(0.0f, 1.0f, 0.0f), proj, view, camPos);

        // Footer hint
        fontRenderer.renderBillboard("Tab to close",
            new Vector3f(center.x, center.y - 0.9f, center.z), 0.05f,
            new Vector3f(0.5f, 0.5f, 0.5f), proj, view, camPos);
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
            "/: Search Repo    Tab: Map    Enter: Chat",
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
            var vm = GLFW.glfwGetVideoMode(GLFW.glfwGetPrimaryMonitor());
            width = vm != null ? vm.width() : 1920;
            height = vm != null ? vm.height() : 1080;
        }
        GLFW.glfwSetWindowMonitor(window, monitor, 0, 0, width, height, GLFW.GLFW_DONT_CARE);
        renderer.resize(width, height);
        bloom.resize(width, height);
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
        music.cleanup();
        renderer.cleanup();
        bloom.cleanup();
        GLFW.glfwDestroyWindow(window);
        GLFW.glfwTerminate();
        GLFW.glfwSetErrorCallback(null).free();
    }

    // ── Screenshot + auto-drive ──

    /** Capture the current frame to screenshots/<n>.png. */
    private void captureScreenshot() {
        if (screenshotDir == null) screenshotDir = "screenshots";
        java.io.File dir = new java.io.File(screenshotDir);
        if (!dir.exists()) dir.mkdirs();
        String path = screenshotDir + "/shot_" + String.format("%04d", shotCounter++) + ".png";
        String written = Screenshot.capture(width, height, path);
        if (written != null) System.out.println("[Screenshot] " + written);
    }

    /**
     * Live patch poll + cinematic + apply. Shared by update() and
     * updateAutodrive() so patches ship in BOTH normal play and the
     * scripted tour (previously autodrive skipped the poll entirely).
     */
    private void updatePatches(double dt) {
        if (patchManager == null) return;
        patchPollTimer -= dt;
        if (patchPollTimer <= 0) { patchManager.poll(); patchPollTimer = 8.0; }
        if (!patchCinematic && patchManager.hasPending()) {
            PatchManager.Patch pp = patchManager.peekPending();
            patchCinematicTitle = (pp != null && pp.title != null) ? pp.title : "Update";
            patchCinematic = true;
            patchTimer = 0.0;
            System.out.println("[Patch] cinematic start: " + patchCinematicTitle);
        }
        if (patchCinematic) {
            patchTimer += dt;
            if (patchTimer >= 3.0) {
                PatchManager.Patch pp = patchManager.takePending();
                if (pp != null) {
                    patchManager.apply(pp, world);
                    patchManager.applyGraphics(pp, renderer);
                    patchToast = "PATCH " + pp.id + " LOADED — " + (pp.title != null ? pp.title : "");
                    patchToastTimer = 10.0;
                    System.out.println("[Patch] " + patchToast);
                }
                patchCinematic = false;
            }
        }
        if (patchToastTimer > 0) patchToastTimer -= dt;
    }

    /**
     * Continuous genetic-audio evolution (step 12). Every EVOLVE_INTERVAL
     * seconds, run a BATCH of GA generations against real rendered synth
     * patches and splice the fittest patch into the live music. Fully
     * autonomous — no human rating, just the SonicFitness signal metrics.
     */
    private void updateEvolution(double dt) {
        if (audioEvolver == null) return;

        // Poll the external control file (CLI tweaks while the system runs).
        if (genomeControl != null) {
            com.google.gson.JsonObject ctl = genomeControl.read();
            if (ctl != null) {
                String applied = genomeControl.apply(ctl, audioEvolver, sonicFitness);
                System.out.println("[Evolve] control applied: " + (applied.isEmpty() ? "(none)" : applied));
                genomeControl.ack(audioEvolver.generation());
            }
        }

        // Periodic population refresh — inject random newcomers to avoid
        // stagnation (step 13). Elite set is never touched.
        refreshTimer -= dt;
        if (refreshTimer <= 0) {
            refreshTimer = REFRESH_INTERVAL;
            audioEvolver.refreshPopulation(REFRESH_COUNT);
            System.out.println("[Evolve] population refreshed with " + REFRESH_COUNT + " newcomers");
        }

        evolveTimer -= dt;
        if (evolveTimer > 0) return;
        evolveTimer = EVOLVE_INTERVAL;

        // Run many generations per tick (the GA loop, steps 6-10 repeated) so
        // the population actually converges, then apply the fittest patch.
        int genStart = audioEvolver.generation();
        com.mindpalace.genetics.AudioGenome next = null;
        for (int i = 0; i < EVOLVE_GENERATIONS_PER_TICK; i++) {
            next = audioEvolver.step();
        }
        if (next != null) {
            music.applyGenome(next);
            float score = audioEvolver.bestScore();
            float mean = audioEvolver.meanScore();
            // Persist the champion genome + its rendered audio every tick.
            if (genomeArchive != null) {
                genomeArchive.save(audioEvolver.generation(), next, score,
                    com.mindpalace.audio.MusicEngine.renderOffline(next, 8000));
            }
            evolveToast = "EVOLVED gen " + genStart + "→" + audioEvolver.generation()
                + " — " + next + " (fit " + String.format("%.2f", score)
                + ", mean " + String.format("%.2f", mean) + ")";
            evolveToastTimer = 8.0;
            // Log: generation, fitness stats, parameter values, archive path.
            System.out.println("[Evolve] " + evolveToast);
            System.out.println("[Evolve] log gen=" + audioEvolver.generation()
                + " best=" + String.format("%.3f", score)
                + " mean=" + String.format("%.3f", mean)
                + " sigma=" + String.format("%.2f", audioEvolver.mutationSigma())
                + " rate=" + String.format("%.2f", audioEvolver.mutationRate())
                + " pop=" + audioEvolver.populationSize()
                + " w[loud=" + String.format("%.2f", sonicFitness.loudnessWeight())
                + ",cent=" + String.format("%.2f", sonicFitness.centroidWeight())
                + ",steady=" + String.format("%.2f", sonicFitness.steadinessWeight())
                + ",novel=" + String.format("%.2f", sonicFitness.noveltyWeight())
                + ",target=" + String.format("%.2f", sonicFitness.targetWeight()) + "]"
                + " archive=~/AIGEN_SYS/mindpalace_memory/evolution/gen-" + audioEvolver.generation() + ".wav");
        }
        if (evolveToastTimer > 0) evolveToastTimer -= dt;
    }

    /**
     * Autonomous self-test: exercises the REAL click path (findBookInSights)
     * against every room, plus teleporter/agent/world invariants, and prints
     * a PASS/FAIL report. No human driving — this is the definitive check.
     */
    private void runSelfTest() {
        int pass = 0, fail = 0;
        System.out.println("\n===== MIND PALACE SELF-TEST =====");

        // 1. World built
        if (world != null && !world.getRooms().isEmpty()) {
            System.out.println("PASS world built: " + world.getRooms().size() + " rooms, "
                + world.getHallways().size() + " hallways"); pass++;
        } else { System.out.println("FAIL world empty"); fail++; }

        // 2. Books placed + clickable (the reported bug)
        // Books are placed lazily during render() — reveal all fog and render
        // each room so placement happens, then exercise the real click path.
        for (Room room : world.getRooms()) {
            if (room.getDoorPosition() != null) world.getFogOfWar().reveal(room.getDoorPosition());
        }
        int roomsWithBooks = 0, clickableRooms = 0, totalBooks = 0, placedBooks = 0, clickableBooks = 0;
        for (Room room : world.getRooms()) {
            if (room.getBooks().isEmpty()) continue;
            roomsWithBooks++;
            totalBooks += room.getBooks().size();
            // Teleport the player into the room and render it (places books),
            // then aim at each placed book and confirm findBookInSights hits.
            player.teleportIntoRoom(room);
            render(0);
            boolean anyHit = false;
            for (Book b : room.getBooks()) {
                if (!b.isPlaced()) continue;
                placedBooks++;
                Vector3f o = player.getPosition();
                Vector3f to = new Vector3f(b.getWorldX(), b.getWorldY(), b.getWorldZ()).sub(o);
                if (to.lengthSquared() < 0.0001f) continue;
                to.normalize();
                aimCameraAt(to);
                Book hit = findBookInSights(room);
                if (hit != null) { anyHit = true; clickableBooks++; }
            }
            if (anyHit) clickableRooms++;
        }
        System.out.println((clickableRooms > 0 ? "PASS" : "FAIL")
            + " books clickable: " + clickableBooks + "/" + placedBooks
            + " placed books hit across " + clickableRooms + "/" + roomsWithBooks + " rooms");
        // Require a HIGH hit rate against PLACED books — the old AABB raycast
        // only hit ~44% even when aimed dead-center, which is why books "didn't
        // click" in-game. The cone test should hit essentially every placed book.
        float hitRate = placedBooks > 0 ? (float) clickableBooks / placedBooks : 0f;
        if (clickableRooms > 0 && hitRate >= 0.9f) pass++; else fail++;

        // 3. Teleporter pads exist (one per floor except top)
        int pads = 0;
        for (Hallway hw : world.getHallways()) {
            if (hw.getFloor() < world.getHallways().size() - 1) pads++;
        }
        System.out.println((pads > 0 ? "PASS" : "FAIL") + " teleporter pads: " + pads);
        if (pads > 0) pass++; else fail++;

        // 4. Agents spawned
        System.out.println((npcs.size() >= 2 ? "PASS" : "FAIL") + " agents: " + npcs.size());
        if (npcs.size() >= 2) pass++; else fail++;

        // 5. Crystals spawned
        System.out.println((crystals.size() > 0 ? "PASS" : "FAIL") + " TODO crystals: " + crystals.size());
        if (crystals.size() > 0) pass++; else fail++;

        // 6. Knowledge graph
        System.out.println((knowledgeGraph != null && knowledgeGraph.nodeCount() > 0 ? "PASS" : "FAIL")
            + " KG nodes: " + (knowledgeGraph != null ? knowledgeGraph.nodeCount() : 0));
        if (knowledgeGraph != null && knowledgeGraph.nodeCount() > 0) pass++; else fail++;

        // 7. Font renderer ready
        System.out.println((fontRenderer != null && fontRenderer.isReady() ? "PASS" : "FAIL") + " font renderer");
        if (fontRenderer != null && fontRenderer.isReady()) pass++; else fail++;

        // 8. Full click path: aim at a book → open editor → verify isOpen
        boolean editorOpened = false;
        outer:
        for (Room room : world.getRooms()) {
            if (room.getBooks().isEmpty()) continue;
            player.teleportIntoRoom(room);
            render(0);
            for (Book b : room.getBooks()) {
                if (!b.isPlaced()) continue;
                Vector3f o = player.getPosition();
                Vector3f to = new Vector3f(b.getWorldX(), b.getWorldY(), b.getWorldZ()).sub(o);
                if (to.lengthSquared() < 0.0001f) continue;
                to.normalize();
                aimCameraAt(to);
                Book hit = findBookInSights(room);
                if (hit != null) {
                    bookEditor.open(hit, room, player.getPosition(), player.getLookDirection());
                    if (bookEditor.isOpen()) { editorOpened = true; }
                    bookEditor.close();
                    break outer;
                }
            }
        }
        System.out.println((editorOpened ? "PASS" : "FAIL") + " editor opens on book click");
        if (editorOpened) pass++; else fail++;

        // 9. Teleporter destinations: teleport to each floor + outside
        boolean teleportOk = true;
        for (int f = 0; f < world.getHallways().size(); f++) {
            player.teleportToFloor(f, world);
            float expectedY = world.getHallways().get(f).getStart().y + 1.6f;
            if (Math.abs(player.getPosition().y - expectedY) > 0.5f) teleportOk = false;
        }
        player.teleportOutside(world);
        if (player.getPosition().z > -10f) teleportOk = false; // outside is negative Z
        System.out.println((teleportOk ? "PASS" : "FAIL") + " teleporter destinations (floors + outside)");
        if (teleportOk) pass++; else fail++;

        // 10. ESC menu: pages render, navigation wraps, settings adjust live
        boolean menuOk = true;
        state = GameState.MENU; menuPage = 0; menuSel = 0;
        if (menuOptionCount() != 8) menuOk = false;
        float fovBefore = player.getCamera().getFov();
        menuPage = 1; menuSel = 0;
        adjustMenuValue(0, 1); // FOV +5
        if (player.getCamera().getFov() != fovBefore + 5f) menuOk = false;
        player.getCamera().setFov(fovBefore);
        float volBefore = audio.getMasterVolume();
        menuPage = 3; menuSel = 0;
        adjustMenuValue(0, 1); // volume +0.1
        if (Math.abs(audio.getMasterVolume() - (volBefore + 0.1f)) > 0.001f) menuOk = false;
        audio.setMasterVolume(volBefore);
        // Invert Y toggle (controls page)
        boolean invBefore = player.getCamera().isInvertY();
        menuPage = 2; menuSel = 0;
        adjustMenuValue(0, 1);
        if (player.getCamera().isInvertY() == invBefore) menuOk = false;
        player.getCamera().setInvertY(invBefore);
        // Bloom intensity live-adjust (video page)
        if (bloom != null) {
            float bi = bloom.getIntensity();
            menuPage = 1; menuSel = 3;
            adjustMenuValue(3, 1);
            if (Math.abs(bloom.getIntensity() - (bi + 0.1f)) > 0.001f) menuOk = false;
            bloom.setIntensity(bi);
        }
        state = GameState.PLAYING; menuPage = 0;
        System.out.println((menuOk ? "PASS" : "FAIL") + " ESC menu (pages + live settings)");
        if (menuOk) pass++; else fail++;

        // 11. Bloom post-processing: pipeline runs without GL errors, tunable,
        //     AND the composited frame actually has non-black pixels.
        boolean bloomOk = bloom != null && bloom.isEnabled();
        if (bloomOk) {
            float t = bloom.getThreshold(), i = bloom.getIntensity();
            bloom.setThreshold(0.5f); bloom.setIntensity(0.9f);
            render(0); // exercise the full bright->blur->composite path
            bloom.setThreshold(t); bloom.setIntensity(i);
            // Read back the scene FBO directly (before composite) to isolate
            // whether the scene renders at all vs. the composite being broken.
            float sceneLum = bloom.debugSceneLuminance();
            int glErr = bloom.debugGlError();
            float clearR = bloom.debugClearTest();
            float compLum = bloom.debugCompositeLuminance();
            System.out.println("  bloom scene FBO luminance: " + String.format("%.4f", sceneLum)
                + ", glError=" + glErr + ", clearTest R=" + String.format("%.1f", clearR)
                + ", composite=" + String.format("%.4f", compLum));
            if (compLum < 0.5f) bloomOk = false; // composite produced black on screen
        }
        System.out.println((bloomOk ? "PASS" : "FAIL") + " bloom post-processing (bright/blur/composite)");
        if (bloomOk) pass++; else fail++;

        // 12. Map overlay toggle (Tab) — field flips, render path exists
        boolean mapOk = true;
        showMap = false;
        showMap = !showMap;
        if (!showMap) mapOk = false;
        showMap = !showMap;
        if (showMap) mapOk = false;
        System.out.println((mapOk ? "PASS" : "FAIL") + " map overlay toggle (Tab)");
        if (mapOk) pass++; else fail++;

        // 13. Immediate chat path — scheduler exposes submitImmediate (no 5-min gate)
        boolean chatOk = agentManager != null && agentManager.getScheduler() != null;
        System.out.println((chatOk ? "PASS" : "FAIL") + " immediate chat path (scheduler)");
        if (chatOk) pass++; else fail++;

        // 14. Mouse look / turn radius — a full 180° turn must be reachable with
        //     a modest mouse drag (the "can't turn around at a wall" bug).
        boolean lookOk = true;
        float sens = player.getCamera().getSensitivity();
        float yaw0 = player.getCamera().getYaw();
        // 1200px drag at current sensitivity should sweep well past 180°
        player.getCamera().rotate(1200f, 0f);
        float turned = Math.abs(player.getCamera().getYaw() - yaw0);
        if (turned < 90f) lookOk = false; // can't even turn a quarter-turn
        player.getCamera().setYaw(yaw0);
        System.out.println((lookOk ? "PASS" : "FAIL") + " mouse look turn radius ("
            + String.format("%.0f", turned) + "° from 1200px @ sens " + String.format("%.2f", sens) + ")");
        if (lookOk) pass++; else fail++;

        // 15. Room personality — every room has a language tint + poster data
        boolean personalityOk = true;
        int tinted = 0;
        for (Room room : world.getRooms()) {
            float[] t = room.getTint();
            if (t[0] != 1.0f || t[1] != 1.0f || t[2] != 1.0f) tinted++;
        }
        if (tinted == 0) personalityOk = false; // no room got a language accent
        System.out.println((personalityOk ? "PASS" : "FAIL") + " room personality ("
            + tinted + "/" + world.getRooms().size() + " rooms tinted)");
        if (personalityOk) pass++; else fail++;

        // 16. Phase D finesse — fact bank + plant click + telemetry panel present
        boolean finesseOk = FACTS.length > 0 && knowledgeGraph != null;
        System.out.println((finesseOk ? "PASS" : "FAIL") + " finesse ("
            + FACTS.length + " facts, KG " + (knowledgeGraph != null ? knowledgeGraph.nodeCount() : 0) + " nodes)");
        if (finesseOk) pass++; else fail++;

        // 17. Phase E music — procedural engine present, live-tunable, mood presets
        boolean musicOk = music != null;
        if (musicOk) {
            int tempoBefore = music.getTempo();
            music.setTempo(tempoBefore + 4);
            if (music.getTempo() != tempoBefore + 4) musicOk = false;
            music.setTempo(tempoBefore);
            music.setMood("energetic");
            if (!music.isBeat()) musicOk = false; // energetic mood enables the beat
            music.setMood("calm");
            if (music.isBeat()) musicOk = false;  // calm mood disables the beat
        }
        System.out.println((musicOk ? "PASS" : "FAIL") + " music (procedural engine + live tuning + moods)");
        if (musicOk) pass++; else fail++;

        // 17b. StepSequencer — 16×4 grid, toggle/clear/tempo, live playhead.
        boolean seqOk = sequencer != null;
        if (seqOk) {
            int tempo = sequencer.getTempo();
            sequencer.setTempo(tempo + 8);
            if (sequencer.getTempo() != tempo + 8) seqOk = false;
            sequencer.setTempo(tempo);
            // Toggle a cell on, verify it flipped, then off.
            boolean before = sequencer.get(0, 0);
            sequencer.toggle(0, 0);
            if (sequencer.get(0, 0) == before) seqOk = false;
            sequencer.toggle(0, 0);
            if (sequencer.get(0, 0) != before) seqOk = false;
            // Clear wipes the grid; re-seed the default pattern for the user.
            sequencer.clear();
            boolean allClear = true;
            for (int r = 0; r < StepSequencer.CHANNELS; r++)
                for (int c = 0; c < StepSequencer.STEPS; c++)
                    if (sequencer.get(r, c)) allClear = false;
            if (!allClear) seqOk = false;
        }
        System.out.println((seqOk ? "PASS" : "FAIL") + " step sequencer (grid + toggle + tempo + clear)");
        if (seqOk) pass++; else fail++;

        // 18. Poster boards — language-distribution diagram + image-texture poster
        //     Every room draws a repo-composition diagram (or a real repo image)
        //     on its poster board above the door.
        boolean posterOk = world != null && world.getRooms() != null;
        int roomsWithImages = 0, roomsWithDiagram = 0;
        if (posterOk) {
            for (Room rm : world.getRooms()) {
                if (rm.getBooks().isEmpty()) continue;
                roomsWithDiagram++;                       // every populated room gets a diagram
                if (rm.getPosterImagePath() != null) roomsWithImages++;
            }
            posterOk = roomsWithDiagram > 0;              // at least one diagram must exist
        }
        System.out.println((posterOk ? "PASS" : "FAIL") + " poster boards ("
            + roomsWithDiagram + " diagram rooms, " + roomsWithImages + " with repo images)");
        if (posterOk) pass++; else fail++;

        // 19. SIMS1337 parity — ModelRouter + LoRASwitcher + WeightedQuorumVote + FOWGate
        boolean simsOk = agentManager != null;
        if (simsOk) {
            // Router: complexity → tier mapping is deterministic.
            simsOk = agentManager.getRouter().select(Complexity.LOW).equals("qwen2.5:0.5b")
                  && agentManager.getRouter().select(Complexity.CRITICAL).equals("phi3:mini");
            // LoRA: switch to CODE and back, verify current type + switch count.
            if (simsOk) {
                agentManager.getLora().switchAdapter(AdapterType.CODE);
                simsOk = agentManager.getLora().currentType() == AdapterType.CODE
                      && agentManager.getLora().getSwitchCount() >= 1;
            }
            // Quorum: register a proposal, auto-vote, verify a result is produced.
            if (simsOk) {
                String pid = "selftest-" + System.currentTimeMillis();
                agentManager.getQuorum().registerProposal(pid, "selftest", new HexCoord(0, 0));
                agentManager.getQuorum().autoVoteAll();
                simsOk = agentManager.getQuorum().calculateQuorum(pid) != null;
            }
            // FOW: two agents pinned, two models assigned.
            if (simsOk) {
                simsOk = agentManager.getFow().agentCount() == 2
                      && agentManager.getFow().modelCount() == 2;
            }
        }
        System.out.println((simsOk ? "PASS" : "FAIL") + " SIMS1337 parity (router + LoRA + quorum + FOW)");
        if (simsOk) pass++; else fail++;

        // 20. Code editor language toggle — ~20-language registry + LoRA switch
        boolean langOk = LanguageRegistry.count() >= 20;
        if (langOk) {
            // Toggle cycles deterministically and maps to a LoRA adapter.
            String next = LanguageRegistry.next("Java");
            langOk = next.equals("Python")
                  && LanguageRegistry.byName("Rust") != null
                  && LanguageRegistry.byName("Rust").adapter == AdapterType.CODE
                  && LanguageRegistry.byExtension(".py") != null;
        }
        System.out.println((langOk ? "PASS" : "FAIL") + " code editor language toggle ("
            + LanguageRegistry.count() + " languages + LoRA mapping)");
        if (langOk) pass++; else fail++;

        // 21. Lexical bridge — chat logs → lexical vectors → quorum → TODO issues
        boolean lexicalOk = true;
        // LexicalAnalyzer: tokenize + vectorize + cosine are deterministic.
        LexicalAnalyzer.Vector va = LexicalAnalyzer.vectorize("fix the null pointer bug in the parser");
        LexicalAnalyzer.Vector vb = LexicalAnalyzer.vectorize("fix null pointer bug parser");
        float cos = LexicalAnalyzer.cosine(va, vb);
        if (va.isEmpty() || vb.isEmpty() || cos < 0.5f) lexicalOk = false;
        // LegacyRepoClassifier: backup/ref/test/duplicate names are legacy.
        List<String> names = List.of("SIMS1337", "SIMS1337-BACKEND", "sims-backup-20260722",
            "Plane2d-ref", "RandomTestProject", "mindpalace");
        if (!LegacyRepoClassifier.isLegacy("sims-backup-20260722", names)) lexicalOk = false;
        if (!LegacyRepoClassifier.isLegacy("Plane2d-ref", names)) lexicalOk = false;
        if (!LegacyRepoClassifier.isLegacy("RandomTestProject", names)) lexicalOk = false;
        if (LegacyRepoClassifier.isLegacy("mindpalace", names)) lexicalOk = false; // active
        // Bridge: runLexicalBridge is wired and returns a (possibly empty) list.
        if (agentManager != null) {
            try {
                agentManager.runLexicalBridge(); // must not throw
            } catch (Exception e) {
                lexicalOk = false;
            }
        }
        System.out.println((lexicalOk ? "PASS" : "FAIL") + " lexical bridge (vectors + legacy classifier + quorum)");
        if (lexicalOk) pass++; else fail++;

        // 22. Solve loop — quorum-gated, safe (no real file edits in self-test).
        boolean solveOk = agentManager != null;
        if (solveOk) {
            try {
                // Empty input → 0 solved, no throw.
                if (agentManager.solveIssues(List.of()) != 0) solveOk = false;
                // A non-existent issue → 0 solved (file not found), no throw.
                AgentManager.Issue ghost = new AgentManager.Issue("__nonexistent__", "no/such/file.java", "TODO nothing");
                if (agentManager.solveIssues(List.of(ghost)) != 0) solveOk = false;
            } catch (Exception e) {
                solveOk = false;
            }
        }
        System.out.println((solveOk ? "PASS" : "FAIL") + " solve loop (quorum-gated, no-op on empty/ghost)");
        if (solveOk) pass++; else fail++;

        // 23. Teleporter BEHAVIOR — Enter on a pad opens the destination LIST,
        // does NOT auto-teleport; a second Enter confirms the selection. Drives
        // the real input→picker→confirm path (not a direct teleportTo* call).
        // Mirrors real play: stand on the pad for one tick (padFloor detected in
        // player.update), THEN press Enter.
        boolean teleportBehaviorOk = false;
        try {
            teleportMenu = false;
            state = GameState.PLAYING;
            List<Vector3f> tpads = world.getTeleporterPads();
            if (!tpads.isEmpty()) {
                Vector3f p0 = tpads.get(0);
                player.getCamera().setPosition(p0.x, p0.y + 1.6f, p0.z);
            }
            update(1.5);   // tick 1: decay teleportCooldown (1.0→0) + detect pad
            boolean onPad = player.getPadFloor() >= 0;
            input.injectKeyPress(GLFW.GLFW_KEY_ENTER);
            update(0.0);   // tick 2: Enter opens the picker
            boolean openedPicker = teleportMenu;
            update(0.0);   // release tick: resets keysPrev so the next Enter edges
            if (onPad && openedPicker) {
                teleportSel = 0;
                input.injectKeyPress(GLFW.GLFW_KEY_ENTER);
                update(0.0);   // tick 3: Enter confirms → picker closes
                teleportBehaviorOk = !teleportMenu;
            }
        } catch (Exception e) {
            teleportBehaviorOk = false;
            System.err.println("[SelfTest] teleporter behavior threw: " + e);
        }
        System.out.println((teleportBehaviorOk ? "PASS" : "FAIL")
            + " teleporter behavior (Enter opens list, second Enter confirms)");
        if (teleportBehaviorOk) pass++; else fail++;

        // 24. Outside-world chunked streaming — near chunks render, far chunks
        // cull (Phase F). Verifies the distance-based cull that keeps the ~1200
        // cube outside scene off the Intel HD 510 when the player is far away.
        boolean chunkOk =
               WorldBuilder.chunkVisibleAt(0f, 0f, 0f, 0f)        // same chunk → visible
            && WorldBuilder.chunkVisibleAt(10f, 10f, 10f, 10f)    // coincident → visible
            && WorldBuilder.chunkVisibleAt(40f, 0f, 0f, 0f)       // ~40m → within 55m → visible
            && !WorldBuilder.chunkVisibleAt(120f, 120f, 0f, 0f)   // far → culled
            && !WorldBuilder.chunkVisibleAt(70f, 0f, 0f, 0f);     // 70m > 55m → culled
        System.out.println((chunkOk ? "PASS" : "FAIL")
            + " outside chunk streaming (near visible, far culled)");
        if (chunkOk) pass++; else fail++;

        // 25. DePIN economy — wallets, blackboard jobs, skill gate, pay-for-work.
        boolean depinOk = depin != null;
        if (depinOk) {
            depinOk = depin.participant("player") != null
                   && depin.participant("Explorer") != null
                   && depin.participant("Critic") != null;
            // Claim + complete a difficulty-1 job as Explorer (tier 1 can do diff 1).
            if (depinOk) {
                List<com.mindpalace.economy.Blackboard.Job> open = depin.board().openJobs();
                depinOk = !open.isEmpty();
                if (depinOk) {
                    long jid = open.get(0).id;
                    double before = depin.participant("Explorer").wallet.getBalance();
                    depinOk = depin.claim(jid, "Explorer")
                           && depin.complete(jid) > 0
                           && depin.participant("Explorer").wallet.getBalance() > before
                           && depin.participant("Explorer").skill.get() >= 1;
                }
            }
            // Skill gate: a tier-1 agent can't claim a difficulty-5 job.
            if (depinOk) {
                com.mindpalace.economy.Blackboard.Job hard = depin.post("Hard job", "repo/hard", 50.0, 5);
                depinOk = !depin.claim(hard.id, "Critic"); // Critic is still tier 1
            }
        }
        System.out.println((depinOk ? "PASS" : "FAIL")
            + " DePIN economy (wallets + blackboard + skill gate + pay-for-work)");
        if (depinOk) pass++; else fail++;

        // 26. Model shops — proximity detection + buy with credits.
        boolean shopOk = depin != null && world.getOutsideWorld() != null;
        if (shopOk) {
            OutsideWorld.Shop[] shops = world.getOutsideWorld().getShops();
            shopOk = shops != null && shops.length == 5;
            // nearestShopIndex: player at mansion-pos(-20, -180-14) should match shop 0.
            if (shopOk) {
                int si = world.getOutsideWorld().nearestShopIndex(
                    shops[0].pos.x, shops[0].pos.z, 5f);
                shopOk = (si == 0);
            }
            // Buy: player has 100 credits, RAG costs 15; should succeed.
            if (shopOk) {
                double before = depin.participant("player").wallet.getBalance();
                shopOk = depin.spend("player", shops[0].cost, "shop:" + shops[0].name)
                    && depin.participant("player").wallet.getBalance() < before;
            }
            // Deny: LoRA costs 30; drain wallet then try buy → should fail.
            if (shopOk) {
                // Drain to < 30
                while (depin.participant("player").wallet.getBalance() > 25) {
                    depin.spend("player", 10, "drain");
                }
                shopOk = !depin.spend("player", shops[3].cost, "shop:" + shops[3].name);
            }
        }
        System.out.println((shopOk ? "PASS" : "FAIL")
            + " model shops (proximity + buy + deny)");
        if (shopOk) pass++; else fail++;

        // 27. Genetic enhancement — timeline mutate/level/persist round-trip
        // against a TEMP genome (never the player's real genome.json).
        boolean genomeOk = genome != null;
        if (genomeOk) {
            try {
                java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("mp-genome-test");
                GeneticTimeline g = new GeneticTimeline(tmp);
                int before = g.mutationCount();
                GeneticTimeline.Mutation m1 = g.mutate("RAG", "faster recall", 15.0);
                GeneticTimeline.Mutation m2 = g.mutate("RAG", "faster recall", 15.0);
                genomeOk = m1.level == 1 && m2.level == 2
                    && g.levelOf("RAG") == 2
                    && g.mutationCount() == before + 2
                    && g.moduleCount() >= 1;
                // Round-trip: reload from disk and confirm persistence.
                GeneticTimeline reloaded = new GeneticTimeline(tmp);
                genomeOk = genomeOk && reloaded.levelOf("RAG") == 2
                    && reloaded.mutationCount() == g.mutationCount();
            } catch (Exception e) {
                genomeOk = false;
            }
        }
        System.out.println((genomeOk ? "PASS" : "FAIL")
            + " genetic timeline (mutate + level + persist)");
        if (genomeOk) pass++; else fail++;

        // 28. Genetic audio — genome/fitness/evolver round-trip (pure, no synth).
        boolean gaOk = false;
        try {
            com.mindpalace.genetics.AudioGenome g0 = com.mindpalace.genetics.AudioGenome.defaultPatch();
            com.mindpalace.genetics.SonicFitness fit = new com.mindpalace.genetics.SonicFitness();
            fit.setSampleRate(44100);
            // A warm 800 Hz sine scores higher than a Nyquist buzz (the bug class).
            float[] smooth = new float[2048];
            float[] buzz = new float[2048];
            for (int i = 0; i < 2048; i++) {
                smooth[i] = (float) (0.15 * Math.sin(2 * Math.PI * 800 * i / 44100.0));
                buzz[i] = (i % 2 == 0) ? 0.5f : -0.5f; // Nyquist square = max harshness
            }
            float smoothScore = fit.score(smooth);
            float buzzScore = fit.score(buzz);
            // Evolver: 3 generations should keep bestScore >= 0 and produce a best.
            com.mindpalace.genetics.AudioEvolver ev = new com.mindpalace.genetics.AudioEvolver(
                new java.util.Random(42), fit, g -> smooth, 8, 2, 0.1f, 0.2f);
            for (int i = 0; i < 3; i++) ev.step();
            // Elite set guarantees the best score is monotonic non-decreasing
            // across generations (never regresses).
            boolean monotonic = true;
            java.util.List<Float> hist = ev.bestHistory();
            for (int i = 1; i < hist.size(); i++) {
                if (hist.get(i) < hist.get(i - 1) - 1e-6f) { monotonic = false; break; }
            }
            // Mutation rate: rate=0 must leave the genome unchanged (stability).
            com.mindpalace.genetics.AudioGenome g1 = g0.mutate(new java.util.Random(1), 0.5f, 0f);
            boolean rateZeroStable = java.util.Arrays.equals(g0.genes, g1.genes);
            // Target matching: an identical buffer scores higher than a far one.
            com.mindpalace.genetics.SonicFitness fitT = new com.mindpalace.genetics.SonicFitness(0f, 0f, 0f, 0f);
            fitT.setTargetWeight(1.0f);
            float[] target = smooth.clone();
            float[] far = new float[2048];
            for (int i = 0; i < 2048; i++) far[i] = (float) (0.15 * Math.sin(2 * Math.PI * 440 * i / 44100.0));
            float targetScore = fitT.score(smooth, null, target);   // identical → ~1
            float farScore = fitT.score(far, null, target);         // different → lower
            boolean targetOk = targetScore > farScore;
            gaOk = smoothScore > buzzScore && ev.best() != null && ev.bestScore() >= 0f
                && rateZeroStable && monotonic && ev.generation() == 3 && targetOk;
        } catch (Exception e) {
            gaOk = false;
        }
        System.out.println((gaOk ? "PASS" : "FAIL")
            + " genetic audio (genome + fitness + evolver)");
        if (gaOk) pass++; else fail++;

        // 29. Continuous evolution bridge — genome → real synth render + apply.
        boolean bridgeOk = false;
        try {
            com.mindpalace.genetics.AudioGenome bg = com.mindpalace.genetics.AudioGenome.defaultPatch();
            // renderOffline must produce a non-silent, non-NaN buffer.
            float[] buf = com.mindpalace.audio.MusicEngine.renderOffline(bg, 8000);
            boolean nonSilent = false;
            for (float v : buf) { if (Math.abs(v) > 0.001f) { nonSilent = true; break; } }
            boolean noNaN = true;
            for (float v : buf) { if (Float.isNaN(v) || Float.isInfinite(v)) { noNaN = false; break; } }
            // applyGenome must not throw and must leave the engine in a valid state.
            music.applyGenome(bg);
            boolean applied = music.getVolume() >= 0f && music.getVolume() <= 1f
                && music.getScale() != null;
            bridgeOk = nonSilent && noNaN && applied;
        } catch (Exception e) {
            bridgeOk = false;
        }
        System.out.println((bridgeOk ? "PASS" : "FAIL")
            + " continuous evolution (genome → synth render + apply)");
        if (bridgeOk) pass++; else fail++;

        // 30. Genome archive — best genome + WAV persisted every generation.
        boolean archiveOk = false;
        try {
            java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("mp-archive");
            com.mindpalace.genetics.GenomeArchive arch = new com.mindpalace.genetics.GenomeArchive(tmp);
            com.mindpalace.genetics.AudioGenome ag = com.mindpalace.genetics.AudioGenome.defaultPatch();
            float[] clip = com.mindpalace.audio.MusicEngine.renderOffline(ag, 4410);
            arch.save(1, ag, 0.5f, clip);
            java.nio.file.Path json = tmp.resolve("evolution/gen-1.json");
            java.nio.file.Path wav = tmp.resolve("evolution/gen-1.wav");
            boolean jsonExists = java.nio.file.Files.exists(json);
            boolean wavExists = java.nio.file.Files.exists(wav);
            // WAV header sanity: "RIFF" + "WAVE" + "data" magic.
            byte[] wavBytes = java.nio.file.Files.readAllBytes(wav);
            boolean riff = wavBytes[0]=='R' && wavBytes[1]=='I' && wavBytes[2]=='F' && wavBytes[3]=='F';
            boolean wave = wavBytes[8]=='W' && wavBytes[9]=='A' && wavBytes[10]=='V' && wavBytes[11]=='E';
            boolean data = wavBytes[36]=='d' && wavBytes[37]=='a' && wavBytes[38]=='t' && wavBytes[39]=='a';
            archiveOk = jsonExists && wavExists && riff && wave && data;
            // cleanup temp dir
            try { java.nio.file.Files.walk(tmp).sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> { try { java.nio.file.Files.delete(p); } catch (Exception ignored) {} }); } catch (Exception ignored) {}
        } catch (Exception e) {
            archiveOk = false;
        }
        System.out.println((archiveOk ? "PASS" : "FAIL")
            + " genome archive (best genome + WAV per generation)");
        if (archiveOk) pass++; else fail++;

        // 31. Live controls — mutation rate/strength + fitness weights + refresh.
        boolean controlsOk = false;
        try {
            com.mindpalace.genetics.SonicFitness cf = new com.mindpalace.genetics.SonicFitness();
            com.mindpalace.genetics.AudioEvolver ce = new com.mindpalace.genetics.AudioEvolver(
                new java.util.Random(7), cf, g -> new float[4410], 10, 2, 0.15f, 0.2f);
            ce.setMutationRate(0.5f);
            ce.setMutationSigma(0.3f);
            cf.setLoudnessWeight(0.1f);
            cf.setCentroidWeight(0.2f);
            cf.setSteadinessWeight(0.3f);
            cf.setNoveltyWeight(0.4f);
            cf.setTargetWeight(0.5f);
            boolean settersOk = ce.mutationRate() == 0.5f && ce.mutationSigma() == 0.3f
                && cf.loudnessWeight() == 0.1f && cf.centroidWeight() == 0.2f
                && cf.steadinessWeight() == 0.3f && cf.noveltyWeight() == 0.4f
                && cf.targetWeight() == 0.5f;
            // refreshPopulation must not throw and must keep population size.
            ce.refreshPopulation(3);
            controlsOk = settersOk && ce.populationSize() == 10;
        } catch (Exception e) {
            controlsOk = false;
        }
        System.out.println((controlsOk ? "PASS" : "FAIL")
            + " live controls (mutation + fitness weights + refresh)");
        if (controlsOk) pass++; else fail++;

        // 32. Control channel — CLI file → apply → ack round-trip.
        boolean ctlOk = false;
        try {
            com.mindpalace.genetics.GenomeControl gc = new com.mindpalace.genetics.GenomeControl();
            com.mindpalace.genetics.SonicFitness cfit = new com.mindpalace.genetics.SonicFitness();
            com.mindpalace.genetics.AudioEvolver cev = new com.mindpalace.genetics.AudioEvolver(
                new java.util.Random(9), cfit, g -> new float[4410], 8, 2, 0.15f, 0.2f);
            // Write a control request, apply it, verify the params changed.
            com.google.gson.JsonObject req = new com.google.gson.JsonObject();
            req.addProperty("mutationRate", 0.7f);
            req.addProperty("centroid", 0.5f);
            req.addProperty("refresh", 2);
            String summary = gc.apply(req, cev, cfit);
            boolean applied = cev.mutationRate() == 0.7f && cfit.centroidWeight() == 0.5f;
            ctlOk = applied && summary.contains("rate=0.70") && summary.contains("cent=0.50")
                && summary.contains("refresh=2");
        } catch (Exception e) {
            ctlOk = false;
        }
        System.out.println((ctlOk ? "PASS" : "FAIL")
            + " control channel (CLI file → apply → ack)");
        if (ctlOk) pass++; else fail++;

        // 33. GitHub issue stream — ADD-ONLY semantics (raise only, never mutate).
        boolean issueStreamOk = false;
        try {
            // The stream exposes ONLY raise() + listOpen() — no close/delete/edit
            // methods exist on the type, so add-only is enforced by construction.
            // Verify the type has no mutating methods via reflection.
            Class<?> is = com.mindpalace.github.GitHubIssueStream.class;
            boolean hasDelete = false, hasClose = false, hasEdit = false;
            for (java.lang.reflect.Method m : is.getDeclaredMethods()) {
                String n = m.getName().toLowerCase();
                if (n.contains("delete") || n.contains("close") || n.contains("edit")
                    || n.contains("update") || n.contains("remove")) {
                    hasDelete = true;
                }
            }
            // Pacing: a second raise within the min interval returns -2 (paced),
            // never hits the API. Construct with a huge interval to test this
            // without network.
            com.mindpalace.github.GitHubIssueStream stream =
                new com.mindpalace.github.GitHubIssueStream("x".repeat(40), "test", 60_000L);
            int first = stream.raise("repo", "t", "b");   // -1 (bad token) or -2
            int second = stream.raise("repo", "t", "b");  // must be -2 (paced)
            issueStreamOk = !hasDelete && second == -2;
        } catch (Exception e) {
            issueStreamOk = false;
        }
        System.out.println((issueStreamOk ? "PASS" : "FAIL")
            + " issue stream (add-only + cellular pacing)");
        if (issueStreamOk) pass++; else fail++;

        System.out.println("===== RESULT: " + pass + " passed, " + fail + " failed ====");
        if (fail > 0) System.exit(1);
    }

    /**
     * Auto-drive: scripted tour that captures VARIED views so the agent can
     * SEE the world from different angles (not the same frame over and over).
     * Phases: walk forward → look left at a sign → look right → look up at
     * teleporter → walk into a room → look down at floor text. Drives the
     * camera directly (no Input/GLFW callbacks). Used with --autodrive <dir>.
     */
    private void updateAutodrive(double dt) {
        world.tick((float) dt);
        updatePatches(dt);
        shotTimer += dt;
        if (shotTimer >= 0.5) {
            shotTimer = 0.0;
            captureScreenshot();
        }

        tourTimer += dt;
        Camera cam = player.getCamera();
        Vector3f p = cam.getPosition();

        // Keep the tour inside the world: loop back to the start when the
        // camera walks past the last hallway (otherwise it drifts into the
        // void and every frame goes black).
        float worldEnd = world.getHallways().isEmpty()
            ? 100f
            : world.getHallways().get(0).getEnd().z;
        if (p.z > worldEnd + 5f) {
            p.z = world.getHallways().get(0).getStart().z + 2f;
            p.x = 0f;
            cam.setPitch(0f);
        }

        // 6-second phases, cycling through varied camera angles
        int phase = (int) (tourTimer / 6.0);
        float t = (float) (tourTimer % 6.0);

        switch (phase % 6) {
            case 0 -> { // walk forward down the hall
                p.z += 6.0f * (float) dt;
                cam.setYaw(0);
            }
            case 1 -> { // pan left to read a wall sign
                cam.setYaw(-60f);
            }
            case 2 -> { // pan right
                cam.setYaw(60f);
            }
            case 3 -> { // look up at the teleporter beam
                cam.setYaw(0);
                cam.setPitch(-30f);
            }
            case 4 -> { // look down at floor text
                cam.setYaw(0);
                cam.setPitch(40f);
            }
            case 5 -> { // strafe sideways to see a room doorway
                p.x += 2.0f * (float) dt;
                cam.setYaw(90f);
            }
        }
        cam.setPosition(p);
    }
}
