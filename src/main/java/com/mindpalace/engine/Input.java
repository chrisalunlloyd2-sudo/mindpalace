package com.mindpalace.engine;

import org.lwjgl.glfw.GLFW;

/**
 * Input system — keyboard + mouse for FPS controls.
 * Uses cursor-hide + recenter method (compatible with all GPUs).
 */
public class Input {
    private final long window;
    private final boolean[] keys = new boolean[GLFW.GLFW_KEY_LAST + 1];
    private final boolean[] keysPrev = new boolean[GLFW.GLFW_KEY_LAST + 1];

    private double deltaX, deltaY;
    private double centerX, centerY;
    private boolean cursorCaptured = true;

    private boolean leftClick, rightClick;
    private boolean leftClickPrev, rightClickPrev;

    public Input(long window) {
        this.window = window;

        // Get initial window center for cursor recenter
        int[] w = new int[1], h = new int[1];
        GLFW.glfwGetWindowSize(window, w, h);
        centerX = w[0] / 2.0;
        centerY = h[0] / 2.0;

        // Mouse button callback
        GLFW.glfwSetMouseButtonCallback(window, (win, button, action, mods) -> {
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                leftClick = action == GLFW.GLFW_PRESS;
            }
            if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                rightClick = action == GLFW.GLFW_PRESS;
            }
        });
    }

    public void update(double dt) {
        // Copy current key state to previous
        System.arraycopy(keys, 0, keysPrev, 0, keys.length);
        leftClickPrev = leftClick;
        rightClickPrev = rightClick;

        // Poll valid keys only (GLFW key codes start at 32)
        for (int i = 32; i <= GLFW.GLFW_KEY_LAST; i++) {
            keys[i] = GLFW.glfwGetKey(window, i) == GLFW.GLFW_PRESS;
        }

        // Mouse delta via cursor recenter (works on all GPUs)
        if (cursorCaptured) {
            double[] mx = new double[1], my = new double[1];
            GLFW.glfwGetCursorPos(window, mx, my);
            deltaX = mx[0] - centerX;
            deltaY = my[0] - centerY;
            GLFW.glfwSetCursorPos(window, centerX, centerY);
        } else {
            deltaX = 0;
            deltaY = 0;
        }
    }

    public void setCursorCaptured(boolean captured) {
        this.cursorCaptured = captured;
        if (captured) {
            GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_HIDDEN);
            GLFW.glfwSetCursorPos(window, centerX, centerY);
        } else {
            GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
        }
    }

    public boolean isKeyDown(int key) { return keys[key]; }

    public boolean isKeyJustPressed(int key) {
        return keys[key] && !keysPrev[key];
    }

    public boolean isKeyJustReleased(int key) {
        return !keys[key] && keysPrev[key];
    }

    public double getMouseDX() {
        double dx = deltaX;
        deltaX = 0;
        return dx;
    }

    public double getMouseDY() {
        double dy = deltaY;
        deltaY = 0;
        return dy;
    }

    public boolean isLeftClick() { return leftClick && !leftClickPrev; }
    public boolean isRightClick() { return rightClick && !rightClickPrev; }
}
