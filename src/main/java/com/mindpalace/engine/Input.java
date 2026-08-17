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

    private double lastX, lastY;
    private double accumDX, accumDY;
    private boolean captured;
    private boolean firstMouse = true;

    private boolean leftClick, rightClick;
    private boolean leftClickJust, rightClickJust;

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
        });

        // Character input for chat typing
        GLFW.glfwSetCharCallback(window, (win, codepoint) -> {
            synchronized (charBuffer) {
                if (codepoint >= 32 && codepoint < 0x10000) {
                    charBuffer.append((char) codepoint);
                }
            }
        });
        charCallbackSet = true;
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
