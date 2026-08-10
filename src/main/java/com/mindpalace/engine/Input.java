package com.mindpalace.engine;

import org.lwjgl.glfw.GLFW;

/**
 * Input — keyboard + raw mouse delta (Quake-style).
 * Tracks previous cursor position, computes delta between callbacks.
 * No recenter, no virtual drift.
 */
public class Input {
    private final long window;
    private final boolean[] keys = new boolean[GLFW.GLFW_KEY_LAST + 1];
    private final boolean[] keysPrev = new boolean[GLFW.GLFW_KEY_LAST + 1];

    private double mouseDX, mouseDY;
    private double prevX = -1, prevY = -1;
    private boolean cursorCaptured = true;

    private boolean leftClick, rightClick;
    private boolean leftClickPrev, rightClickPrev;

    public Input(long window) {
        this.window = window;

        GLFW.glfwSetCursorPosCallback(window, (win, x, y) -> {
            if (prevX < 0) { prevX = x; prevY = y; return; }
            mouseDX += x - prevX;
            mouseDY += y - prevY;
            prevX = x;
            prevY = y;
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
    }

    public void setCursorCaptured(boolean captured) {
        cursorCaptured = captured;
        if (captured) {
            GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
            prevX = -1; prevY = -1; // reset on capture
        } else {
            GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
        }
    }

    public boolean isKeyDown(int key) { return keys[key]; }
    public boolean isKeyJustPressed(int key) { return keys[key] && !keysPrev[key]; }
    public boolean isKeyJustReleased(int key) { return !keys[key] && keysPrev[key]; }

    public double getMouseDX() { double d = mouseDX; mouseDX = 0; return d; }
    public double getMouseDY() { double d = mouseDY; mouseDY = 0; return d; }
    public boolean isLeftClick() { return leftClick && !leftClickPrev; }
    public boolean isRightClick() { return rightClick && !rightClickPrev; }
}
