package com.mindpalace.engine;

import com.mindpalace.render.Renderer;
import com.mindpalace.render.FontRenderer;
import com.mindpalace.render.Camera;
import com.mindpalace.world.WorldBuilder;
import com.mindpalace.world.Book;
import com.mindpalace.world.Room;
import com.mindpalace.entity.Player;
import com.mindpalace.ui.HUD;
import com.mindpalace.ui.BookEditor;
import com.mindpalace.github.GitHubClient;
import com.mindpalace.audio.AudioEngine;
import org.joml.Vector3f;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

import java.io.BufferedReader;
import java.io.InputStreamReader;

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

        loadingText = "Ready.";
        loadingProgress = 1.0f;
        renderLoadingFrame();

        state = GameState.PLAYING;
        loading = false;
        input.setCursorCaptured(true);

        lastFrameTime = GLFW.glfwGetTime();
        accumulator = 0.0;
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

        // Process console commands for editor
        String cmd = null;
        synchronized (this) {
            if (pendingCommand != null) {
                cmd = pendingCommand;
                pendingCommand = null;
            }
        }
        if (cmd != null && bookEditor.isOpen()) {
            bookEditor.handleCommand(cmd);
        }

        // ESC toggles
        if (input.isKeyJustPressed(GLFW.GLFW_KEY_ESCAPE)) {
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

            // Book click detection — left click in a room
            if (input.isLeftClick() && player.getCurrentRoom() != null) {
                Book clicked = findBookInSights(player.getCurrentRoom());
                if (clicked != null) {
                    bookEditor.open(clicked, player.getCurrentRoom(),
                        player.getPosition(), player.getLookDirection());
                    state = GameState.BOOK_VIEW;
                    input.setCursorCaptured(false);
                }
            }
        }

        if (input.isKeyJustPressed(GLFW.GLFW_KEY_F11))
            toggleFullscreen();
    }

    private Book findBookInSights(Room room) {
        Vector3f origin = player.getPosition();
        Vector3f dir = player.getLookDirection();
        Vector3f c = room.getRoomCenter();
        float w = Room.ROOM_WIDTH, d = Room.ROOM_DEPTH, h = Room.ROOM_HEIGHT;
        int side = room.getHallwaySide();

        // Check back wall bookcase
        float bz = side == 0 ? c.z + d / 2f - 0.25f : c.z - d / 2f + 0.25f;
        Book hit = raycastBooks(origin, dir, c.x, c.y, bz, w - 0.6f, true);
        if (hit != null) return hit;

        // Check left wall bookcase
        float lx = c.x - w / 2f + 0.25f;
        hit = raycastBooks(origin, dir, lx, c.y, c.z, d - 0.6f, false);
        if (hit != null) return hit;

        // Check right wall bookcase
        float rx = c.x + w / 2f - 0.25f;
        hit = raycastBooks(origin, dir, rx, c.y, c.z, d - 0.6f, false);
        return hit;
    }

    private Book raycastBooks(Vector3f origin, Vector3f dir, float caseX, float caseY, float caseZ, float caseWidth, boolean facingZ) {
        float caseDepth = 0.5f;
        float caseBottom = caseY - Room.ROOM_HEIGHT / 2f + 0.1f;
        float caseTop = caseY + Room.ROOM_HEIGHT / 2f - 0.1f;
        float caseHeight = caseTop - caseBottom;
        float shelfSpacing = caseHeight / 3f;
        float shelfY0 = caseBottom + 0.06f + shelfSpacing / 2f;
        float bookH = shelfSpacing * 0.75f;
        float bookW = 0.10f;
        float bookGap = 0.02f;
        float usableWidth = caseWidth - 0.12f - 0.2f;
        int maxBooks = (int) (usableWidth / (bookW + bookGap));

        for (int row = 0; row < 3; row++) {
            float sy = shelfY0 + row * shelfSpacing;
            for (int b = 0; b < maxBooks; b++) {
                float offset = -usableWidth / 2f + b * (bookW + bookGap) + bookW / 2f;
                float bx, bz, bw, bd;
                if (facingZ) {
                    bx = caseX + offset;
                    bz = caseZ;
                    bw = bookW;
                    bd = caseDepth * 0.6f;
                } else {
                    bx = caseX;
                    bz = caseZ + offset;
                    bw = caseDepth * 0.6f;
                    bd = bookW;
                }

                // Ray-AABB intersection
                Vector3f hit = rayAABB(origin, dir,
                    bx - bw / 2f, sy - bookH / 2f, bz - bd / 2f,
                    bx + bw / 2f, sy + bookH / 2f, bz + bd / 2f);
                if (hit != null) {
                    // Find which book this is
                    Room room = player.getCurrentRoom();
                    if (room != null) {
                        int wallIdx = facingZ ? 0 : (caseX < room.getRoomCenter().x ? 1 : 2);
                        int perWall = Math.min(40, room.getBooks().size() / 3);
                        int startIdx = wallIdx * perWall;
                        int bookIdx = startIdx + row * (perWall / 3) + b;
                        if (bookIdx < room.getBooks().size()) {
                            return room.getBooks().get(bookIdx);
                        }
                    }
                }
            }
        }
        return null;
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

        // Render neon sign text
        if (fontRenderer != null && fontRenderer.isReady()) {
            renderNeonSignText();
        }

        if (state == GameState.PLAYING) {
            hud.render(renderer, player, world);
        }

        if (bookEditor.isOpen()) {
            bookEditor.render(renderer);
        }

        GLFW.glfwSwapBuffers(window);
    }

    private void renderNeonSignText() {
        Camera cam = player.getCamera();
        Matrix4f proj = cam.getProjectionMatrix((float) width / height);
        Matrix4f view = cam.getViewMatrix();
        Vector3f camPos = cam.getPosition();

        for (Room room : world.getRooms()) {
            Vector3f dp = room.getDoorPosition();
            if (dp == null) continue;
            float dist = camPos.distance(dp);
            if (dist > 25f) continue;

            // Sign position — same as renderNeonSign in WorldBuilder
            float signY = (room.getFloor() == 0 ? 0 : WorldBuilder.HALLWAY_HEIGHT + 1.0f)
                + WorldBuilder.HALLWAY_HEIGHT - 0.3f;
            float wallX = room.getHallwaySide() == 0
                ? -WorldBuilder.HALLWAY_WIDTH / 2f
                : WorldBuilder.HALLWAY_WIDTH / 2f;
            float offsetX = wallX > 0 ? -0.20f : 0.20f;
            Vector3f signPos = new Vector3f(wallX + offsetX, signY, dp.z);

            // Facing direction (into hallway)
            Vector3f facing = new Vector3f(wallX > 0 ? -1 : 1, 0, 0);

            // Color: cyan for public, pink for private
            Vector3f color = room.isPrivate()
                ? new Vector3f(1.0f, 0.2f, 0.6f)
                : new Vector3f(0.0f, 0.9f, 1.0f);

            // Truncate long names
            String name = room.getRepoName();
            if (name.length() > 18) name = name.substring(0, 16) + "..";

            float charSize = 0.07f;
            fontRenderer.renderText(name, signPos, charSize, color, proj, view, facing);
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

    private void cleanup() {
        audio.cleanup();
        renderer.cleanup();
        GLFW.glfwDestroyWindow(window);
        GLFW.glfwTerminate();
        GLFW.glfwSetErrorCallback(null).free();
    }
}
