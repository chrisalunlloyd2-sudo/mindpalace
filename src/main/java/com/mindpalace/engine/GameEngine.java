package com.mindpalace.engine;

import com.mindpalace.render.Renderer;
import com.mindpalace.world.WorldBuilder;
import com.mindpalace.entity.Player;
import com.mindpalace.ui.HUD;
import com.mindpalace.github.GitHubClient;
import com.mindpalace.audio.AudioEngine;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

public class GameEngine {
    private long window;
    private int width = 1920;
    private int height = 1080;
    private boolean fullscreen = false;
    private boolean running = true;

    private Renderer renderer;
    private WorldBuilder world;
    private Player player;
    private Input input;
    private HUD hud;
    private GitHubClient github;
    private AudioEngine audio;
    private GameState state;

    private double lastFrameTime;
    private double accumulator;
    private static final double PHYSICS_DT = 1.0 / 120.0;
    private static final double MAX_FRAME_TIME = 0.25;
    private int fps;
    private double fpsTimer;

    // Loading screen
    private String loadingText = "";
    private double loadingProgress;
    private boolean loading;

    public void run() {
        init();
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

        // Show loading screen
        state = GameState.LOADING;
        loading = true;
        loadingText = "Initializing engine...";
        loadingProgress = 0.1f;
        renderLoadingFrame();

        input = new Input(window);
        renderer = new Renderer(width, height);
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

        loadingText = "Ready.";
        loadingProgress = 1.0f;
        renderLoadingFrame();

        // Start playing
        state = GameState.PLAYING;
        loading = false;
        input.setCursorCaptured(true);

        lastFrameTime = GLFW.glfwGetTime();
        accumulator = 0.0;
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
                GLFW.glfwSetWindowTitle(window, title);
                fps = 0;
                fpsTimer = 0.0;
            }

            GLFW.glfwPollEvents();
        }
    }

    private void update(double dt) {
        input.update(dt);

        if (input.isKeyJustPressed(GLFW.GLFW_KEY_ESCAPE)) {
            if (state == GameState.PLAYING) {
                state = GameState.MENU;
                input.setCursorCaptured(false);
            } else {
                state = GameState.PLAYING;
                input.setCursorCaptured(true);
            }
        }

        if (state == GameState.PLAYING)
            player.update(dt, input, world);

        if (input.isKeyJustPressed(GLFW.GLFW_KEY_F11))
            toggleFullscreen();
    }

    private void render(double alpha) {
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        renderer.beginFrame(player.getCamera());
        world.render(renderer);

        if (state == GameState.PLAYING) {
            hud.render(renderer, player, world);
            // Laser aim dot
            renderer.drawLaserDot(player.getCamera());
        }

        GLFW.glfwSwapBuffers(window);
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
