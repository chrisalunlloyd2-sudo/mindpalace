package com.mindpalace.ui;

import com.mindpalace.render.Camera;
import com.mindpalace.render.FontRenderer;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;

/**
 * #304: intro splash — game title + "press any key" before the world view.
 *
 * <p>Pure state plus the existing billboard text path (no new rendering
 * infrastructure — AGENTS.md hard rule #5, cheap adds only). Gated OFF for
 * {@code --demo}/{@code --selftest} so automated boots and the E2E waypoint
 * tour never block on it; dismissed by any input activity (key, click, mouse
 * move) via {@code Input.consumeActivity()}.</p>
 */
public final class SplashScreen {

    private boolean visible;

    /** Hidden under demo/selftest so automated boots go straight to gameplay. */
    public static boolean shouldShow(boolean demoMode, boolean selfTest) {
        return !demoMode && !selfTest;
    }

    public void show() { visible = true; }

    public boolean isVisible() { return visible; }

    /** Any input dismisses; false when already gone. */
    public boolean dismiss() {
        if (!visible) return false;
        visible = false;
        return true;
    }

    /** Dark wash + title block + prompt, billboarded in front of the camera. */
    public void render(FontRenderer font, Camera cam, int width, int height) {
        if (!visible || font == null || cam == null) return;
        GL11.glClearColor(0.02f, 0.02f, 0.05f, 1f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);

        Matrix4f proj = cam.getProjectionMatrix((float) width / height);
        Matrix4f view = cam.getViewMatrix();
        Vector3f camPos = cam.getPosition();
        Vector3f front = cam.getFront();
        Vector3f up = cam.getUp();
        Vector3f anchor = new Vector3f(camPos).add(new Vector3f(front).mul(2.4f));

        font.renderBillboard("MINDPALACE",
            new Vector3f(anchor).add(new Vector3f(up).mul(0.20f)),
            0.115f, new Vector3f(1f, 0.84f, 0.35f), proj, view, camPos);
        font.renderBillboard("your repositories, as rooms",
            new Vector3f(anchor).sub(new Vector3f(up).mul(0.05f)),
            0.042f, new Vector3f(0.75f, 0.78f, 0.85f), proj, view, camPos);
        font.renderBillboard("PRESS ANY KEY",
            new Vector3f(anchor).sub(new Vector3f(up).mul(0.22f)),
            0.055f, new Vector3f(1f, 1f, 1f), proj, view, camPos);
    }
}
