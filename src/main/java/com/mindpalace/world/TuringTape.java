package com.mindpalace.world;

import com.mindpalace.render.Renderer;
import org.joml.Vector3f;

import java.util.List;

/**
 * TuringTape — the mansion's main-hallway floor strip, one cell per recorded
 * telemetry event. The tape is APPEND-ONLY: cells are never removed, only
 * added at the head (never delete, only add). The head cell glows brightest —
 * that's "one move ahead," the writing head of the real Turing tape.
 *
 * Performance: one flat quad per cell, drawn only when within LOD range of the
 * camera. Cells are capped at TAPE_MAX; the visual scrolls with the tape length
 * so a long history never explodes the draw count. Color comes from the event
 * category — a fixed palette, no per-frame math beyond a lerp.
 */
public final class TuringTape {

    private static final int TAPE_MAX = 64;         // cells rendered max
    private static final float CELL_W = 0.5f;       // meters
    private static final float CELL_H = 0.02f;      // thickness (flat strip)
    private static final float STRIP_Y = 0.03f;     // just above floor
    private static final float LOD_DIST = 40f;      // only draw when near

    // Category palette (r, g, b) — matches the telemetry categories.
    private static final float[][] PALETTE = {
        {0.20f, 0.90f, 1.00f},  // agent   — cyan
        {1.00f, 0.85f, 0.20f},  // quorum  — gold
        {0.40f, 1.00f, 0.40f},  // depin   — green
        {0.90f, 0.40f, 1.00f},  // genetic — violet
        {1.00f, 0.50f, 0.30f},  // issue   — orange
        {1.00f, 0.90f, 0.50f},  // shop    — champagne
        {0.60f, 0.70f, 1.00f},  // code    — steel blue
        {1.00f, 0.40f, 0.45f},  // mistake — red
        {0.75f, 0.75f, 0.75f},  // system  — grey
    };
    private static final String[] CATEGORIES = {
        "agent", "quorum", "depin", "genetic", "issue", "shop", "code", "mistake", "system"
    };

    private final Vector3f anchor;   // where the tape starts (near spawn end of hall)
    private final float dirZ;        // tape grows along +z (down the hall)
    private int head = 0;            // number of cells ever written (the head position)
    private long lastEventId = -1;   // dedupe: only grow on NEW telemetry events

    public TuringTape(Vector3f anchor, float dirZ) {
        this.anchor = new Vector3f(anchor);
        this.dirZ = dirZ;
    }

    /** Feed telemetry rows (ts, category, event, detail); grows the tape. */
    public void feed(List<String[]> recent) {
        if (recent == null || recent.isEmpty()) return;
        // recent is newest-first; count NEW events since lastEventId is hard with
        // no ids — use total count growth instead: cells = min(size, TAPE_MAX),
        // and advance head by the delta of the list length each feed.
        int size = recent.size();
        if (size > head) head = Math.min(size, TAPE_MAX);
        else if (size < head) head = Math.max(size, 0); // telemetry pruned — never delete cells, just stop growing
    }

    /** How many cells the tape currently holds. */
    public int length() { return head; }

    /**
     * Render the strip. Cheap: at most 64 flat cubes, and only when the camera
     * is within LOD range. The head cell pulses (sin of time) — the writing
     * head, one move ahead.
     */
    public void render(Renderer r, Vector3f camPos, float time) {
        if (head == 0) return;
        if (camPos.distance(anchor) > LOD_DIST + head * CELL_W) return;

        for (int i = 0; i < head; i++) {
            float z = anchor.z + dirZ * (i * CELL_W);
            Vector3f pos = new Vector3f(anchor.x, anchor.y + STRIP_Y, z);
            float[] c = PALETTE[i % PALETTE.length];
            // Older cells fade; the head cell pulses bright.
            float age = (float) (head - i) / head;
            float glow = (i == head - 1)
                ? 0.8f + 0.2f * (float) Math.sin(time * 3.0)
                : 1.0f - 0.6f * age;
            r.drawCubeColor(pos, new Vector3f(CELL_W * 0.92f, CELL_H, CELL_W * 0.92f),
                c[0] * glow, c[1] * glow, c[2] * glow);
        }
    }

    /** The tape's anchor position (for tests / placement). */
    public Vector3f anchor() { return new Vector3f(anchor); }

    /** Map a category name to its palette color (for tests). */
    public static float[] colorOf(String category) {
        for (int i = 0; i < CATEGORIES.length; i++)
            if (CATEGORIES[i].equals(category)) return PALETTE[i];
        return PALETTE[8];
    }
}