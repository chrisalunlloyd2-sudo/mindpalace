package com.mindpalace.engine;

import org.lwjgl.glfw.GLFW;

/**
 * Input system — keyboard + mouse for FPS controls.
 * Tracks held keys, just-pressed keys, mouse delta.
 */
public class Input {
    private final long window;
    private final boolean[] keys = new boolean[GLFW.GLFW_KEY_LAST + 1];
    private final boolean[] keysPrev = new boolean[GLFW.GLFW_KEY_LAST + 1];

    private double mouseX, mouseY;
    private double deltaX, deltaY;
    private boolean firstMouse = true;
    private double lastMouseX, lastMouseY;

    private boolean leftClick, rightClick;
    private boolean leftClickPrev, rightClickPrev;

    public Input(long window) {
        this.window = window;

        // Mouse callback
        GLFW.glfwSetCursorPosCallback(window, (w, x, y) -> {
            if (firstMouse) {
                lastMouseX = x;
                lastMouseY = y;
                firstMouse = false;
            }
            deltaX = x - lastMouseX;
            deltaY = y - lastMouseY;
            lastMouseX = x;
            lastMouseY = y;
            mouseX = x;
            mouseY = y;
        });

        // Mouse button callback
        GLFW.glfwSetMouseButtonCallback(window, (w, button, action, mods) -> {
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                leftClick = action == GLFW.GLFW_PRESS;
            }
            if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                rightClick = action == GLFW.GLFW_PRESS;
            }
        });
    }

    public void update(double dt) {
        // Copy current state to previous
        System.arraycopy(keys, 0, keysPrev, 0, keys.length);
        leftClickPrev = leftClick;
        rightClickPrev = rightClick;

        // Poll valid keys only (GLFW key codes start at 32 = GLFW_KEY_SPACE)
        for (int i = 32; i <= GLFW.GLFW_KEY_LAST; i++) {
            keys[i] = GLFW.glfwGetKey(window, i) == GLFW.GLFW_PRESS;
        }
    }

    public boolean isKeyDown(int key) {
        return keys[key];
    }

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

    public boolean isLeftClick() {
        return leftClick && !leftClickPrev;
    }

    public boolean isRightClick() {
        return rightClick && !rightClickPrev;
    }

    public double getMouseX() { return mouseX; }
    public double getMouseY() { return mouseY; }
}
