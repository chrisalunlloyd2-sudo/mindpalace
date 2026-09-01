package com.mindpalace.world;

import com.mindpalace.render.Renderer;
import org.joml.Vector3f;

/**
 * NashFountain — equilibrium made physical. Droplets arc and flow while the
 * genetic search is ADVANCING; the moment no player can improve (best score
 * stagnant across samples — the Nash equilibrium of the game-against-nature),
 * the droplets FREEZE mid-air. Frozen droplets turn gold; flowing ones are
 * cyan. A new best score thaws the fountain instantly.
 *
 * Performance: fixed pool of 24 particles, deterministic parabolic paths (no
 * physics integration), LOD-culled. Zero allocation beyond a few vectors.
 */
public final class NashFountain {

    private static final int PARTICLES = 24;
    private static final float LOD_DIST = 45f;
    private static final float POOL_R = 0.8f;   // fountain pool radius
    private static final float JET_H = 2.2f;    // arc apex height
    private static final int STAGNANT = 8;    // unchanged samples = equilibrium

    private final Vector3f base;              // pool center (courtyard)
    private final float[] bestHistory = new float[STAGNANT];
    private int histLen = 0;
    private float lastBest = -1f;
    private int unchanged = 0;
    private boolean equilibrium = false;

    private float phase = 0f;                 // fountain time (frozen in equilibrium)
    private double frozenSince = -1;

    public NashFountain(Vector3f base) {
        this.base = new Vector3f(base);
    }

    /** Feed the current best fitness (0..1). Advances/decides equilibrium. */
    public void update(float best) {
        if (best > lastBest + 0.001f) {
            unchanged = 0;                      // ADVANCEMENT — a better move exists
            frozenSince = -1;
        } else {
            unchanged++;                        // no improvement this sample
        }
        lastBest = best;
        if (histLen < STAGNANT) bestHistory[histLen++] = best;
        equilibrium = unchanged >= STAGNANT;
    }

    public boolean isEquilibrium() { return equilibrium; }

    /**
     * Render the pool + droplets. When flowing, phase advances and droplets
     * ride parabolic arcs. When equilibrium, phase freezes — droplets hang
     * exactly where they were, gold and still.
     */
    public void render(Renderer r, Vector3f camPos, double time, float dt) {
        if (camPos.distance(base) > LOD_DIST) return;

        if (!equilibrium) {
            phase += dt * 0.9f;                 // fountain time flows
        } else if (frozenSince < 0) {
            frozenSince = time;                 // mark the moment of stillness
        }

        // Pool — stone ring
        for (int i = 0; i < 8; i++) {
            double ang = Math.PI * 2.0 * i / 8.0;
            Vector3f p = new Vector3f(
                base.x + (float) Math.cos(ang) * POOL_R,
                base.y + 0.15f,
                base.z + (float) Math.sin(ang) * POOL_R);
            r.drawCubeColor(p, new Vector3f(0.3f, 0.25f, 0.3f), 0.4f, 0.4f, 0.45f);
        }
        // Pool water — flat disc, cyan shimmer flowing / gold still
        float shim = equilibrium ? 0.6f : 0.5f + 0.15f * (float) Math.sin(time * 3.0);
        float wr = equilibrium ? 1.0f : 0.2f, wg = 0.7f, wb = equilibrium ? 0.3f : 1.0f;
        r.drawCubeColor(new Vector3f(base.x, base.y + 0.05f, base.z),
            new Vector3f(POOL_R * 1.7f, 0.04f, POOL_R * 1.7f), wr * shim, wg * shim, wb * shim);

        // Droplets — deterministic arcs, phase-frozen in equilibrium
        for (int i = 0; i < PARTICLES; i++) {
            float ph = (phase + (float) i / PARTICLES) % 1f;   // each droplet offset
            double ang = Math.PI * 2.0 * i * 2.399963f;        // golden-angle spiral
            float spread = (float) Math.sqrt(ph) * POOL_R * 1.3f;
            float y = base.y + 4f * ph * (1f - ph) * JET_H;    // parabola: up, then down
            float glow = equilibrium ? 1.0f : 0.7f + 0.3f * (float) Math.sin(time * 6f + i);
            Vector3f pos = new Vector3f(
                base.x + (float) Math.cos(ang) * spread,
                y + 0.3f,
                base.z + (float) Math.sin(ang) * spread);
            if (equilibrium) {
                r.drawCubeColor(pos, new Vector3f(0.09f, 0.09f, 0.09f), 1.0f * glow, 0.85f * glow, 0.25f * glow);
            } else {
                r.drawCubeColor(pos, new Vector3f(0.08f, 0.08f, 0.08f), 0.25f * glow, 0.85f * glow, 1.0f * glow);
            }
        }
    }

    public Vector3f position() { return new Vector3f(base); }
}