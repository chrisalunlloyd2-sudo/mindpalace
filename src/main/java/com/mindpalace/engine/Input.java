package com.mindpalace.engine;

import org.lwjgl.glfw.GLFW;

/**
 * Input — keyboard + raw mouse via GLFW_CURSOR_DISABLED.
 * Standard FPS approach: cursor locked, raw deltas from callback.
 */
public class Input {
    private final long window;
    private final boolean[] keys = new boolean[GLFW.GLFW_KEY_LAST + 1];
    private final boolean[] keysPrev = new boolean[GLFW.GLFW_KEY_LAST + 1];
    private final boolean[] synthPress = new boolean[GLFW.GLFW_KEY_LAST + 1];

    private double lastX, lastY;
    private double accumDX, accumDY;
    private boolean captured;
    private boolean firstMouse = true;

    private boolean leftClick, rightClick;
    private boolean leftClickJust, rightClickJust;

    // Activity flag — set on ANY input (mouse move, key, click) WITHOUT draining
    // the mouse delta accumulator. Used by idle detection so it never eats
    // the deltas the Player needs for look left/right.
    private volatile boolean activityFlag;

    // Character input (for in-game chat typing)
    private final StringBuilder charBuffer = new StringBuilder();
    private boolean charCallbackSet;

    public Input(long window) {
        this.window = window;

        GLFW.glfwSetCursorPosCallback(window, (win, x, y) -> {
            if (firstMouse) { lastX = x; lastY = y; firstMouse = false; return; }
            accumDX += x - lastX;
            accumDY += y - lastY;
            lastX = x;
            lastY = y;
            activityFlag = true;  // mouse moved
        });

        GLFW.glfwSetMouseButtonCallback(window, (win, button, action, mods) -> {
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                if (action == GLFW.GLFW_PRESS) leftClickJust = true;
                leftClick = action == GLFW.GLFW_PRESS;
            }
            if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                if (action == GLFW.GLFW_PRESS) rightClickJust = true;
                rightClick = action == GLFW.GLFW_PRESS;
            }
            activityFlag = true;
        });

        // Character input for chat typing
        GLFW.glfwSetCharCallback(window, (win, codepoint) -> {
            synchronized (charBuffer) {
                if (codepoint >= 32 && codepoint < 0x10000) {
                    charBuffer.append((char) codepoint);
                }
            }
            activityFlag = true;
        });
        charCallbackSet = true;
    }

    /** Consume-and-clear the activity flag (non-draining of mouse deltas). */
    public boolean consumeActivity() {
        boolean v = activityFlag;
        activityFlag = false;
        return v;
    }

    /** Drain typed characters since last call. */
    public String drainTypedChars() {
        synchronized (charBuffer) {
            String s = charBuffer.toString();
            charBuffer.setLength(0);
            return s;
        }
    }

    public void update(double dt) {
        System.arraycopy(keys, 0, keysPrev, 0, keys.length);

        for (int i = 32; i <= GLFW.GLFW_KEY_LAST; i++)
            keys[i] = GLFW.glfwGetKey(window, i) == GLFW.GLFW_PRESS;

        // Merge synthetic (scripted) presses into the current frame so the
        // behavioral tester can drive real key paths through wasKeyPressed().
        for (int i = 32; i <= GLFW.GLFW_KEY_LAST; i++) {
            if (synthPress[i]) { keys[i] = true; synthPress[i] = false; }
        }
    }

    /** Script a single-frame key press (behavioral tester / agent driver). */
    public void injectKeyPress(int key) { synthPress[key] = true; }
    /** Script a single-frame left-click (behavioral tester / agent driver). */
    public void injectLeftClick() { leftClickJust = true; }

    /**
     * PERF-A2 (t_5155f104, docs/AUTONOMOUS_PERF_TEST.md D2): script a HELD key
     * (W/A/S/D/Shift/Space). The engine loop calls this each perf frame AFTER
     * input.update()'s GLFW poll overwrote keys[] and BEFORE player.update()
     * consumes it, so injected holds ride the same isKeyDown() path the real
     * keyboard feeds. Re-injected every frame, so GLFW's per-frame overwrite
     * cannot clear it mid-segment.
     */
    public void injectKeyHold(int key, boolean down) { keys[key] = down; }

    /**
     * PERF-A2: synthetic look deltas — accumulates into the SAME accumDX/DY
     * the mouse callback feeds, drained by Player via getMouseDX/DY. Units:
     * camera-rotate pixels — the exact currency of the real mouse callback
     * (Camera.rotate applies its own sensitivity). InputTrack converts its
     * deg/s look rates to pixels using the camera sensitivity, so an injected
     * turn renders identically to a real mouse move.
     */
    public void injectMouseDelta(double dx, double dy) {
        accumDX += dx;
        accumDY += dy;
        activityFlag = true;
    }

    public void setCursorCaptured(boolean cap) {
        captured = cap;
        if (cap) {
            GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
            firstMouse = true;
            accumDX = 0; accumDY = 0;
        } else {
            GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
        }
    }

    public boolean isKeyDown(int key) { return keys[key]; }
    public boolean wasKeyPressed(int key) { return keys[key] && !keysPrev[key]; }
    public boolean wasKeyReleased(int key) { return !keys[key] && keysPrev[key]; }

    public double getMouseDX() { double d = accumDX; accumDX = 0; return d; }
    public double getMouseDY() { double d = accumDY; accumDY = 0; return d; }

    /** Returns true once per click — resets after read. */
    public boolean isLeftClick() {
        boolean v = leftClickJust;
        leftClickJust = false;
        return v;
    }

    public boolean isRightClick() {
        boolean v = rightClickJust;
        rightClickJust = false;
        return v;
    }
}
