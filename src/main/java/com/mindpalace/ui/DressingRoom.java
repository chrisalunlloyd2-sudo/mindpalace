package com.mindpalace.ui;

import com.mindpalace.avatar.AvatarDescriptor;
import com.mindpalace.engine.Input;
import com.mindpalace.render.Camera;
import com.mindpalace.render.Renderer;
import com.mindpalace.render.FontRenderer;
import org.joml.Vector3f;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

import java.nio.file.*;
import java.util.*;

/**
 * Dressing room — 360° orbit view + parametric avatar editor (the "Photoshop +
 * Facebook avatar + adult Sims" customization screen).
 *
 * Owns the player AvatarDescriptor and mutates it in place. Orbit the camera with
 * the mouse or arrow keys; Tab selects the next attribute; [ and ] (or - and =)
 * pull/push the selected value; H cycles hair style; X toggles sex; F5 saves to
 * avatar.json, F6 loads it.
 *
 * Deterministic + zero-network: every attribute is a number (color channel or a
 * body-part proportion) and every edit is a bounded clamp — nothing runs for free
 * and nothing is baked.
 */
public class DressingRoom {

    private AvatarDescriptor avatar;
    private boolean open = false;

    // 360° orbit state (mouse/arrow driven)
    private float orbitYaw = 0f;      // degrees, horizontal
    private float orbitPitch = 12f;   // degrees, elevation (positive = above)
    private float orbitDist = 2.4f;

    // Editable attribute list + selection
    private final List<Attr> attrs = new ArrayList<>();
    private int sel = 0;

    // ── tiny float getter/setter (avoids double<->float noise in lambdas) ──
    @FunctionalInterface private interface FGet { float get(); }
    @FunctionalInterface private interface FSet { void set(float v); }

    private static final class Attr {
        final String name; final float min, max, step; final FGet get; final FSet set;
        Attr(String n, float mn, float mx, float st, FGet g, FSet s) {
            name = n; min = mn; max = mx; step = st; get = g; set = s;
        }
        float value() { return get.get(); }
        void value(float v) { set.set(Math.max(min, Math.min(max, v))); }
    }

    public DressingRoom(AvatarDescriptor avatar) {
        this.avatar = avatar;
        buildAttrs();
    }

    private void addAttr(String name, float min, float max, float step, FGet get, FSet set) {
        attrs.add(new Attr(name, min, max, step, get, set));
    }

    private void buildAttrs() {
        attrs.clear();
        // color channels (0..1, step 0.05)
        addAttr("Skin R", 0f, 1f, 0.05f, () -> avatar.skinR, v -> avatar.skinR = v);
        addAttr("Skin G", 0f, 1f, 0.05f, () -> avatar.skinG, v -> avatar.skinG = v);
        addAttr("Skin B", 0f, 1f, 0.05f, () -> avatar.skinB, v -> avatar.skinB = v);
        addAttr("Hair R", 0f, 1f, 0.05f, () -> avatar.hairR, v -> avatar.hairR = v);
        addAttr("Hair G", 0f, 1f, 0.05f, () -> avatar.hairG, v -> avatar.hairG = v);
        addAttr("Hair B", 0f, 1f, 0.05f, () -> avatar.hairB, v -> avatar.hairB = v);
        addAttr("Eye R",  0f, 1f, 0.05f, () -> avatar.eyeR,  v -> avatar.eyeR = v);
        addAttr("Eye G",  0f, 1f, 0.05f, () -> avatar.eyeG,  v -> avatar.eyeG = v);
        addAttr("Eye B",  0f, 1f, 0.05f, () -> avatar.eyeB,  v -> avatar.eyeB = v);
        addAttr("Top R",  0f, 1f, 0.05f, () -> avatar.topR,  v -> avatar.topR = v);
        addAttr("Top G",  0f, 1f, 0.05f, () -> avatar.topG,  v -> avatar.topG = v);
        addAttr("Top B",  0f, 1f, 0.05f, () -> avatar.topB,  v -> avatar.topB = v);
        addAttr("Bottom R", 0f, 1f, 0.05f, () -> avatar.bottomR, v -> avatar.bottomR = v);
        addAttr("Bottom G", 0f, 1f, 0.05f, () -> avatar.bottomG, v -> avatar.bottomG = v);
        addAttr("Bottom B", 0f, 1f, 0.05f, () -> avatar.bottomB, v -> avatar.bottomB = v);
        // body-part proportions (pull/push, 0.2..3.0, step 0.1)
        for (AvatarDescriptor.BodyPart p : AvatarDescriptor.BodyPart.values()) {
            final AvatarDescriptor.BodyPart part = p;
            addAttr("Proportion " + p.name(), 0.2f, 3.0f, 0.1f,
                () -> avatar.getProportion(part), v -> avatar.proportions.put(part, v));
        }
    }

    // ── state ──
    public void toggle() { open = !open; if (open) logSelection(); }
    public boolean isOpen() { return open; }
    public void setOpen(boolean o) { open = o; }
    public AvatarDescriptor getAvatar() { return avatar; }
    public void setAvatar(AvatarDescriptor a) { this.avatar = a; buildAttrs(); }

    // ── input ──
    public void handleInput(Input input) {
        // orbit (mouse drag + arrow keys)
        orbitYaw += (float) input.getMouseDX() * 0.3f;
        orbitPitch = clamp(orbitPitch - (float) input.getMouseDY() * 0.3f, -60f, 80f);
        if (input.isKeyDown(GLFW.GLFW_KEY_LEFT))  orbitYaw -= 2.5f;
        if (input.isKeyDown(GLFW.GLFW_KEY_RIGHT)) orbitYaw += 2.5f;
        if (input.isKeyDown(GLFW.GLFW_KEY_UP))    orbitPitch = clamp(orbitPitch + 2.5f, -60f, 80f);
        if (input.isKeyDown(GLFW.GLFW_KEY_DOWN))  orbitPitch = clamp(orbitPitch - 2.5f, -60f, 80f);
        // orbit distance (mouse wheel not available here; use , and .)
        if (input.wasKeyPressed(GLFW.GLFW_KEY_COMMA))  orbitDist = clamp(orbitDist - 0.2f, 1.2f, 6f);
        if (input.wasKeyPressed(GLFW.GLFW_KEY_PERIOD)) orbitDist = clamp(orbitDist + 0.2f, 1.2f, 6f);

        // select attribute
        if (input.wasKeyPressed(GLFW.GLFW_KEY_TAB)) {
            sel = (sel + 1) % attrs.size();
            logSelection();
        }
        // adjust (pull/push) the selected attribute
        if (input.wasKeyPressed(GLFW.GLFW_KEY_LEFT_BRACKET)  || input.wasKeyPressed(GLFW.GLFW_KEY_MINUS)) adjust(-1);
        if (input.wasKeyPressed(GLFW.GLFW_KEY_RIGHT_BRACKET) || input.wasKeyPressed(GLFW.GLFW_KEY_EQUAL)) adjust(+1);
        // discrete toggles
        if (input.wasKeyPressed(GLFW.GLFW_KEY_H)) cycleHairStyle();
        if (input.wasKeyPressed(GLFW.GLFW_KEY_X)) toggleSex();
        // save / load
        if (input.wasKeyPressed(GLFW.GLFW_KEY_F5)) save(Paths.get("avatar.json"));
        if (input.wasKeyPressed(GLFW.GLFW_KEY_F6)) load(Paths.get("avatar.json"));
    }

    private void adjust(int dir) {
        if (attrs.isEmpty()) return;
        Attr a = attrs.get(sel);
        a.value(a.value() + dir * a.step);
        System.out.println("[DressingRoom] " + a.name + " = " + String.format("%.2f", a.value()));
    }

    private void cycleHairStyle() {
        AvatarDescriptor.HairStyle[] styles = AvatarDescriptor.HairStyle.values();
        int idx = Arrays.asList(styles).indexOf(avatar.hairStyle);
        avatar.hairStyle = styles[(idx + 1) % styles.length];
        System.out.println("[DressingRoom] hair = " + avatar.hairStyle);
    }

    private void toggleSex() {
        avatar.sex = avatar.sex == AvatarDescriptor.Sex.FEMALE ? AvatarDescriptor.Sex.MALE : AvatarDescriptor.Sex.FEMALE;
        System.out.println("[DressingRoom] sex = " + avatar.sex);
    }

    private void logSelection() {
        if (attrs.isEmpty()) return;
        Attr a = attrs.get(sel);
        System.out.println("[DressingRoom] editing: " + a.name + " = " + String.format("%.2f", a.value())
            + "  ([ / ] to adjust, Tab to cycle)");
    }

    // ── save / load ──
    public void save(Path p) {
        try { Files.writeString(p, avatar.toJson()); System.out.println("[DressingRoom] saved " + p.toAbsolutePath()); }
        catch (Exception e) { System.err.println("[DressingRoom] save failed: " + e.getMessage()); }
    }
    public void load(Path p) {
        try {
            avatar = AvatarDescriptor.fromJson(Files.readString(p));
            buildAttrs();
            System.out.println("[DressingRoom] loaded " + p.toAbsolutePath());
        } catch (Exception e) { System.err.println("[DressingRoom] load failed: " + e.getMessage()); }
    }

    // ── render ──
    /** Position the camera on the orbit sphere around the avatar (call BEFORE renderer.beginFrame). */
    public void positionCamera(Camera cam) {
        float yawRad = (float) Math.toRadians(orbitYaw);
        float pitchRad = (float) Math.toRadians(orbitPitch);
        Vector3f center = new Vector3f(0f, 1.0f, 0f);
        float cx = center.x + orbitDist * (float) (Math.sin(yawRad) * Math.cos(pitchRad));
        float cy = center.y + orbitDist * (float) (Math.sin(pitchRad));
        float cz = center.z + orbitDist * (float) (Math.cos(yawRad) * Math.cos(pitchRad));
        cam.setPosition(cx, cy, cz);
        Vector3f dir = new Vector3f(center).sub(cam.getPosition()).normalize();
        cam.setPitch((float) Math.toDegrees(Math.asin(dir.y)));
        cam.setYaw((float) Math.toDegrees(Math.atan2(dir.x, dir.z)));
    }

    /** Draw the avatar + attribute list (assumes camera positioned + frame begun). */
    public void render(Renderer renderer, FontRenderer font, Camera cam, float aspect, float time) {
        Vector3f center = new Vector3f(0f, 1.0f, 0f);
        renderAvatar(renderer, avatar, center, 0f, time);

        // in-world billboard attribute list (always faces the camera)
        if (font != null && font.isReady()) {
            Matrix4f proj = cam.getProjectionMatrix(aspect);
            Matrix4f view = cam.getViewMatrix();
            Vector3f camPos = cam.getPosition();
            for (int i = 0; i < attrs.size(); i++) {
                Attr a = attrs.get(i);
                boolean active = i == sel;
                String label = (active ? "> " : "  ") + a.name + ": " + String.format("%.2f", a.value());
                Vector3f p = new Vector3f(center.x - 1.6f, center.y + 2.0f - i * 0.085f, center.z);
                Vector3f col = active ? new Vector3f(1.0f, 0.85f, 0.25f) : new Vector3f(0.65f, 0.78f, 0.95f);
                font.renderBillboard(label, p, 0.045f, col, proj, view, camPos);
            }
        }
    }

    /** Standalone box-avatar renderer (same proportions as renderNPCs, no world deps). */
    private void renderAvatar(Renderer r, AvatarDescriptor a, Vector3f center, float yaw, float time) {
        boolean female = a.sex == AvatarDescriptor.Sex.FEMALE;
        float shoulderW = (female ? 0.30f : 0.44f) * a.getProportion(AvatarDescriptor.BodyPart.SHOULDERS);
        float hipW      = (female ? 0.38f : 0.28f) * a.getProportion(AvatarDescriptor.BodyPart.HIPS);
        float legLen    = (female ? 0.58f : 0.55f) * a.getProportion(AvatarDescriptor.BodyPart.LEGS);
        float armLen    = (female ? 0.44f : 0.42f) * a.getProportion(AvatarDescriptor.BodyPart.ARMS);
        Vector3f holo = new Vector3f(a.skinR, a.skinG, a.skinB);
        float pantsR = a.bottomR, pantsG = a.bottomG, pantsB = a.bottomB;
        float topR = a.topR, topG = a.topG, topB = a.topB;

        float x = center.x, y = center.y, z = center.z;
        float footY = y;
        float hipY = footY + legLen;
        float waistY = hipY + 0.26f;
        float shoulderY = waistY + 0.30f;
        float headY = shoulderY + 0.22f;

        // legs (pants)
        r.drawCubeColorYaw(new Vector3f(x - 0.10f, hipY - legLen * 0.5f, z), new Vector3f(0.10f, legLen, 0.10f), yaw, pantsR, pantsG, pantsB);
        r.drawCubeColorYaw(new Vector3f(x + 0.10f, hipY - legLen * 0.5f, z), new Vector3f(0.10f, legLen, 0.10f), yaw, pantsR, pantsG, pantsB);
        // pelvis / hips (pants)
        r.drawCubeColorYaw(new Vector3f(x, hipY + 0.13f, z), new Vector3f(hipW, 0.26f, 0.20f), yaw, pantsR, pantsG, pantsB);
        // chest (top / bra for female, hologram for male)
        if (female) {
            r.drawCubeColorYaw(new Vector3f(x - 0.09f, shoulderY - 0.06f, z), new Vector3f(0.12f, 0.10f, 0.10f), yaw, topR, topG, topB);
            r.drawCubeColorYaw(new Vector3f(x + 0.09f, shoulderY - 0.06f, z), new Vector3f(0.12f, 0.10f, 0.10f), yaw, topR, topG, topB);
            r.drawCubeColorYaw(new Vector3f(x, shoulderY - 0.10f, z), new Vector3f(shoulderW, 0.22f, 0.20f), yaw, topR, topG, topB);
        } else {
            r.drawHologramCube(new Vector3f(x, shoulderY - 0.10f, z), new Vector3f(shoulderW, 0.30f, 0.24f), yaw, holo, time);
        }
        // arms (hologram)
        r.drawHologramCube(new Vector3f(x - 0.24f, shoulderY - 0.12f, z), new Vector3f(0.09f, armLen, 0.09f), yaw, holo, time);
        r.drawHologramCube(new Vector3f(x + 0.24f, shoulderY - 0.12f, z), new Vector3f(0.09f, armLen, 0.09f), yaw, holo, time);
        // head (hologram)
        r.drawHologramCube(new Vector3f(x, headY, z), new Vector3f(0.24f, 0.24f, 0.24f), yaw, holo, time);
        // hair (solid cap; skipped when NONE)
        if (a.hairStyle != AvatarDescriptor.HairStyle.NONE) {
            r.drawCubeColorYaw(new Vector3f(x, headY + 0.14f, z), new Vector3f(0.26f, 0.10f, 0.26f), yaw, a.hairR, a.hairG, a.hairB);
        }
        // eye visor (eye color)
        r.drawCubeColorYaw(new Vector3f(x, headY, z), new Vector3f(0.16f, 0.04f, 0.02f), yaw, a.eyeR, a.eyeG, a.eyeB);
    }

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
}
