package com.mindpalace.world;

import com.mindpalace.render.Renderer;
import org.joml.Vector3f;

/**
 * RotorRoom — three concentric hex rings outside the mansion, visualizing the
 * deterministic rotor state that drives chromosome assignment. The turing_
 * foundry doctrine: the same clock always produces the same chromosome. Here
 * you SEE it — the three rotor markers advance like an Enigma odometer, one
 * step per second (rotor III steps every tick, II every 8th, I every 64th —
 * real Enigma carry). The active marker on each ring pulses.
 *
 * Performance: 3 rings × 8 hex cells = 24 flat cubes + 3 marker cubes, drawn
 * only within LOD range. No per-frame allocation beyond a handful of Vector3f.
 */
public final class RotorRoom {

    private static final float RING_R = 2.2f;    // inner ring radius
    private static final float RING_STEP = 1.4f; // radius step per ring
    private static final float CELL = 0.45f;     // cell cube size
    private static final float LOD_DIST = 55f;

    // Ring palette: rotor III (fast, inner) cyan, II (mid) gold, I (slow, outer) violet
    private static final float[][] RING_COLOR = {
        {0.20f, 0.90f, 1.00f}, {1.00f, 0.85f, 0.20f}, {0.90f, 0.40f, 1.00f}
    };

    private final Vector3f center;
    private final long anchorTime;   // time basis — same second ⇒ same rotor state
    private int[] rotorPos = {0, 0, 0};  // III, II, I — visible state for tests

    public RotorRoom(Vector3f center) {
        this.center = new Vector3f(center);
        this.anchorTime = 0L;
    }

    /**
     * Enigma odometer: rotor III (inner) steps every tick; II carries when III
     * wraps (8 steps); I carries when II wraps (8×8=64). Same elapsed second ⇒
     * same rotor state — the determinism is the feature.
     */
    public int[] rotorState(double time) {
        long t = (long) Math.floor(time);
        rotorPos[0] = (int) (t % 8);                 // III — fast
        rotorPos[1] = (int) ((t / 8) % 8);           // II — mid
        rotorPos[2] = (int) ((t / 64) % 8);          // I — slow
        return rotorPos;
    }

    /** Render the three rings + markers. Culls beyond LOD_DIST. */
    public void render(Renderer r, Vector3f camPos, double time) {
        if (camPos.distance(center) > LOD_DIST) return;
        int[] pos = rotorState(time);

        for (int ring = 0; ring < 3; ring++) {
            float radius = RING_R + ring * RING_STEP;
            float[] c = RING_COLOR[ring];
            for (int cell = 0; cell < 8; cell++) {
                double ang = Math.PI * 2.0 * cell / 8.0;
                float x = center.x + (float) Math.cos(ang) * radius;
                float z = center.z + (float) Math.sin(ang) * radius;
                Vector3f p = new Vector3f(x, center.y, z);
                boolean active = pos[ring] == cell;
                float glow = active
                    ? 0.9f + 0.1f * (float) Math.sin(time * 6.0)
                    : 0.25f;
                float size = active ? CELL * 1.6f : CELL;
                r.drawCubeColor(p, new Vector3f(size, 0.15f, size),
                    c[0] * glow, c[1] * glow, c[2] * glow);
            }
            // A thin line from ring center to the active marker — the rotor arm
            double aAng = Math.PI * 2.0 * pos[ring] / 8.0;
            Vector3f tip = new Vector3f(center.x + (float) Math.cos(aAng) * radius,
                center.y + 0.1f, center.z + (float) Math.sin(aAng) * radius);
            r.drawLine(new Vector3f(center.x, center.y + 0.1f, center.z), tip,
                0.03f, c[0] * 0.6f, c[1] * 0.6f, c[2] * 0.6f);
        }
    }

    public Vector3f center() { return new Vector3f(center); }
}