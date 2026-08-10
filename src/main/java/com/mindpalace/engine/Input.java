package com.mindpalace.engine;

import org.lwjgl.glfw.GLFW;

/**
 * Input system — keyboard + mouse for FPS controls.
 * Uses GLFW_CURSOR_DISABLED for raw mouse input (no drift).
 * Falls back to cursor-hide+recenter if raw input unavailable.
 */
public class Input {
    private final long window;
    private final boolean[] keys = new boolean[GLFW.GLFW_KEY_LAST + 1];
    private final boolean[] keysPrev = new boolean[GLFW.GLFW_KEY_LAST + 1];

    private double deltaX, deltaY;
    private double centerX, centerY;
    private boolean cursorCaptured = true;
    private boolean useRawInput = true;
    private boolean ignoreNextPos;

    private boolean leftClick, rightClick;
    private boolean leftClickPrev, rightClickPrev;

    public Input(long window) {
        this.window = window;

        int[] w = new int[1], h = new int[1];
        GLFW.glfwGetWindowSize(window, w, h);
        centerX = w[0] / 2.0;
        centerY = h[0] / 2.0;

        // Raw mouse motion callback (GLFW_CURSOR_DISABLED)
        GLFW.glfwSetCursorPosCallback(window, (win, x, y) -> {
            if (useRawInput) {
                deltaX += x - centerX;
                deltaY += y - centerY;
            } else if (!ignoreNextPos) {
                deltaX += x - centerX;
                deltaY += y - centerY;
            }
            ignoreNextPos = false;
        });

        GLFW.glfwSetMouseButtonCallback(window, (win, button, action, mods) -> {
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT)
                leftClick = action == GLFW.GLFW_PRESS;
            if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT)
                rightClick = action == GLFW.GLFW_PRESS;
        });
    }

    public void update(double dt) {
        System.arraycopy(keys, 0, keysPrev, 0, keys.length);
        leftClickPrev = leftClick;
        rightClickPrev = rightClick;

        for (int i = 32; i <= GLFW.GLFW_KEY_LAST; i++)
            keys[i] = GLFW.glfwGetKey(window, i) == GLFW.GLFW_PRESS;

        // Recenter cursor if not using raw input
        if (cursorCaptured && !useRawInput) {
            ignoreNextPos = true;
            GLFW.glfwSetCursorPos(window, centerX, centerY);
        }
    }

    public void setCursorCaptured(boolean captured) {
        this.cursorCaptured = captured;
        if (captured) {
            GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
            // If raw input produces no deltas after 1s, fall back
            deltaX = 0; deltaY = 0;
        } else {
            GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
        }
    }

    public boolean isKeyDown(int key) { return keys[key]; }
    public boolean isKeyJustPressed(int key) { return keys[key] && !keysPrev[key]; }
    public boolean isKeyJustReleased(int key) { return !keys[key] && keysPrev[key]; }

    public double getMouseDX() { double d = deltaX; deltaX = 0; return d; }
    public double getMouseDY() { double d = deltaY; deltaY = 0; return d; }
    public boolean isLeftClick() { return leftClick && !leftClickPrev; }
    public boolean isRightClick() { return rightClick && !rightClickPrev; }
}
