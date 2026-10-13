package com.mindpalace.ui;

import org.joml.Vector3f;

/**
 * HudLayout — single source of truth for WHERE every HUD panel is anchored and
 * HOW BIG it is (task t_0c1374b0: HUD de-overlap).
 *
 * Before this class each panel picked its own camera-relative anchor
 * (telemetry -1.4 right, gist wall +1.4, bot roster +1.35/+0.95, chat +0.9 up,
 * all at 2.2-2.5m out) and none of them knew about the others, so on any
 * wide-framed shot (E2E waypoint 15, forest path) five panels rendered as one
 * giant text pile center-right. Fix: one fixed SLOT TABLE that assigns every
 * panel an anchored, non-overlapping screen region, expressed in
 * meters-at-anchor-distance the existing billboard pipeline already uses.
 *
 * GEOMETRY CONVENTION (t_0c1374b0, measured against the live renderers): at
 * HudLayout.DIST = 2.5m with the default 70-degree VERTICAL FOV, the frustum
 * half-spans are y = tan(35deg)*2.5 = 1.75m (vertical) and x = 1.75*16/9 =
 * 3.11m at 16:9. Every slot below is placed inside X in [-1.75, +3.15] and
 * Y in [-1.30, +1.62] so panels survive both 16:9 ratios and the 1536x864
 * dev display. Every billboard glyph-quad in FontRenderer extends UP from
 * its position.y by ~1.6*charSize, so a slot's anchor (its first line's
 * position) sits at the BOTTOM of the first glyph band; rect heights below
 * include that headroom.
 *
 * The five non-modal primary slots are pairwise-disjoint (asserted by
 * selftest test 34 + checked every e2e run); TELEPORT is modal — every
 * other slot is suppressed while it is open, so the gate checks it alone
 * against the frame bounds. Statics are recorded for reference and
 * anchored through the same table but excluded from the strict pairwise
 * gate (transient toasts can co-occur; verified to not collide visually
 * with the primary slots).
 *
 * Two consumers:
 *  1. GameEngine / AgentChat render passes — ask {@link #anchor} for each
 *     slot and draw the panel there (each slot's javadoc names its renderer).
 *  2. The selftest/e2e overlap gate — for slots VISIBLE in a given game
 *     state assert no two rects intersect ({@link #rect}, {@link #intersects}).
 *     A new panel without a slot here is the bug this card exists to kill.
 *
 * PERF: pure math on precomputed constants; one Vector3f allocation per
 * anchor call. No GL state — safe to call every frame from every panel
 * renderer (Intel HD 510 budget).
 */
public final class HudLayout {

    private HudLayout() {} // constants + pure functions only

    /** The anchor distance shared by all slots (meters in front of camera). */
    public static final float DIST = 2.5f;

    // ── The six primary slots (pairwise-disjoint; each rendered via an
    //    HudLayout.anchor(...) call in GameEngine / AgentChat). ──

    /** Fleet gist wall (FLEET / LOGITS / WORD LIB headers + lines, capped 9)
     *  — upper-left column (GameEngine.renderGistWall). */
    public static final Slot GIST_WALL = new Slot("gist_wall", -1.70f, +1.50f, 1.70f, 0.72f);
    /** Clock / KG stats / model telemetry — left column, below the gist wall
     *  (GameEngine.renderTelemetryPanel). */
    public static final Slot TELEMETRY = new Slot("telemetry", -1.70f, +0.24f, 1.90f, 0.36f);
    /** Chat feed (agent chatter + typing cursor, newest at bottom) — left
     *  column, bottom (AgentChat.render). */
    public static final Slot CHAT      = new Slot("chat",      -1.70f, -0.30f, 2.25f, 0.77f);
    /** BOT ROSTER — upper-right column (GameEngine.renderBotBoard, capped
     *  header + 7 bots). Anchor +1.30: at DIST 2.5 the 16:9 frame ends at
     *  X +3.11, and the 1.80 slot needs its right edge (3.10) on-frame —
     *  the pre-rescale +1.35 summed to 3.15, past the edge. */
    public static final Slot BOT_ROSTER = new Slot("bot_roster", +1.30f, +1.05f, 1.80f, 0.59f);
    /** Minimap (F-floor label + clamped room-dot scatter + @) — right
     *  column below the roster (GameEngine.renderMinimap). */
    public static final Slot MINIMAP   = new Slot("minimap",   +1.60f, -0.18f, 1.40f, 0.42f);
    /** Teleport destination picker — CENTER. When open it is MODAL: the dim
     *  slab renders behind it and every other slot is suppressed
     *  (GameEngine.renderTeleportMenu + GameEngine.hudSuppressed). */
    public static final Slot TELEPORT  = new Slot("teleport",  -1.75f, +0.75f, 3.30f, 1.47f);

    // ── Statics — recorded in the same 2.5m slot space and now anchored
    //    through HudLayout.anchor(...) too (their old 3m/2.2m ad-hoc anchors
    //    left them half off-screen or clipping the roster). Excluded from
    //    the strict pairwise gate; see class javadoc. ──
    /** Room info line — top center (GameEngine.renderScreenHUD). */
    public static final Slot ROOM_INFO  = new Slot("room_info",   0.00f, +0.42f, 1.70f, 0.11f);
    /** Hotkeys strip — bottom center; the 78-char line is 4.68m wide at the
     *  0.06 glyph size, so its anchor is the LEFT end of the centered strip
     *  (GameEngine.renderScreenHUD). */
    public static final Slot HOTKEYS    = new Slot("hotkeys",     0.00f, -0.50f, 4.70f, 0.10f);
    /** Wallet (Credits: N) — top center, just left of the room label's start
     *  (GameEngine.renderScreenHUD). */
    public static final Slot WALLET     = new Slot("wallet",     -0.42f, +0.71f, 0.65f, 0.10f);
    /** Region-name fade line — between room label and prompt
     *  (GameEngine.renderScreenHUD). */
    public static final Slot REGION     = new Slot("region",      0.00f, +0.17f, 0.60f, 0.10f);
    /** Fact toast (potted plant) — lower center (GameEngine.renderFactToast). */
    public static final Slot FACT_TOAST = new Slot("fact_toast",  0.00f, -0.42f, 1.45f, 0.10f);
    /** TOC toast — bottom center (GameEngine.renderTocToast). */
    public static final Slot TOC_TOAST  = new Slot("toc_toast",   0.00f, -1.20f, 2.26f, 0.10f);
    /** Unified interaction prompt — just above view center. RECORD-ONLY:
     *  InteractionPromptSystem keeps its own 3m anchor (untouched by this
     *  card); the slot documents where it draws so future panels avoid it. */
    public static final Slot PROMPT     = new Slot("prompt",     -0.55f, +0.12f, 1.10f, 0.16f);

    /**
     * A named panel slot: anchor (first line position, bottom of its glyph
     * band) + the maximum rectangle the panel may occupy. The rect is what
     * the overlap gate checks; keep it padded above the real content so text
     * growth does not sneak past the gate.
     */
    public static final class Slot {
        public final float x, y;       // anchor (first line position) in slot space
        public final float w, h;       // max extent (padding included)
        public final String name;

        Slot(String name, float x, float y, float w, float h) {
            this.name = name; this.x = x; this.y = y; this.w = w; this.h = h;
        }

        /** Same slot re-anchored (same name + size) — for sub-layouts. */
        public Slot at(float nx, float ny) { return new Slot(name, nx, ny, w, h); }
    }

    /** Axis-aligned rect intersection in slot space (x right, y up). */
    public static boolean intersects(float[] a, float[] b) {
        return a[0] < b[0] + b[2] && b[0] < a[0] + a[2]
            && a[1] < b[1] + b[3] && b[1] < a[1] + a[3];
    }

    /** The slot's gate rect as (x, y_bottom, w, h) in up-positive space —
     *  bottom = anchor minus the max drop, and 0.12m of extra height covers
     *  the first glyph band's upward headroom. */
    public static float[] rect(Slot s) {
        return new float[] { s.x, s.y - s.h, s.w, s.h + 0.12f };
    }

    /**
     * Compute the world anchor for a slot given the camera basis. The anchor
     * is DIST along the full look direction, then x meters along the
     * camera's screen-right, then y meters along the camera's TRUE up
     * (view-matrix derived) so pitch never drifts the panel — the
     * InteractionPromptSystem lesson (F4/CARD-Q2).
     *
     * @param camPos    camera world position
     * @param camFront  camera look direction (normalized)
     * @param camRight  camera screen-right (horizontal cross product)
     * @param camUp     camera up from Camera.getUp()
     * @param s         which slot to anchor
     */
    public static Vector3f anchor(Vector3f camPos, Vector3f camFront,
                                  Vector3f camRight, Vector3f camUp, Slot s) {
        return new Vector3f(camPos)
            .add(new Vector3f(camFront).mul(DIST))
            .add(new Vector3f(camRight).mul(s.x))
            .add(new Vector3f(camUp).mul(s.y));
    }

    /**
     * Modal suppression: while the teleport picker or the Tab map overlay is
     * open, every HUD slot is suppressed (including the prompt — the open
     * menu is the action affordance) and the teleport menu draws its dim
     * slab behind its own labels. GameEngine.hudSuppressed() carries the
     * flags; renderers ask this once per frame.
     *
     * @param teleportMenu teleport picker open
     * @param mapOpen      Tab map overlay open
     * @return true when all HUD slots must stay hidden this frame
     */
    public static boolean suppressed(boolean teleportMenu, boolean mapOpen) {
        return mapOpen || teleportMenu;
    }
}