package com.mindpalace.engine;

import org.lwjgl.glfw.GLFW;

/**
 * Input — keyboard + mouse. Cursor-hide+recenter method (Intel HD compatible).
 * Ignores the synthetic cursor-pos callback from recenter to prevent drift.
 */
public class Input {
    private final long window;
    private final boolean[] keys = new boolean[GLFW.GLFW_KEY_LAST + 1];
    private final boolean[] keysPrev = new boolean[GLFW.GLFW_KEY_LAST + 1];

    private double mouseX, mouseY;
    private double centerX, centerY;
    private double accumDX, accumDY;
    private boolean captured;
    private boolean skipNext;

    private boolean leftClick, rightClick;
    private boolean leftClickPrev, rightClickPrev;

    public Input(long window) {
        this.window = window;

        int[] w = new int[1], h = new int[1];
        GLFW.glfwGetWindowSize(window, w, h);
        centerX = w[0] / 2.0;
        centerY = h[0] / 2.0;

        GLFW.glfwSetCursorPosCallback(window, (win, x, y) -> {
            if (skipNext) { skipNext = false; return; }
            accumDX += x - mouseX;
            accumDY += y - mouseY;
            mouseX = x;
            mouseY = y;
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

        // Recenter cursor each frame (skip the synthetic event)
        if (captured) {
            skipNext = true;
            GLFW.glfwSetCursorPos(window, centerX, centerY);
            mouseX = centerX;
            mouseY = centerY;
        }
    }

    public void setCursorCaptured(boolean cap) {
        captured = cap;
        if (cap) {
            GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_HIDDEN);
            skipNext = true;
            GLFW.glfwSetCursorPos(window, centerX, centerY);
            mouseX = centerX;
            mouseY = centerY;
            accumDX = 0; accumDY = 0;
        } else {
            GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
        }
    }

    public boolean isKeyDown(int key) { return keys[key]; }
    public boolean isKeyJustPressed(int key) { return keys[key] && !keysPrev[key]; }
    public boolean isKeyJustReleased(int key) { return !keys[key] && keysPrev[key]; }

    public double getMouseDX() { double d = accumDX; accumDX = 0; return d; }
    public double getMouseDY() { double d = accumDY; accumDY = 0; return d; }
    public boolean isLeftClick() { return leftClick && !leftClickPrev; }
    public boolean isRightClick() { return rightClick && !rightClickPrev; }
}
