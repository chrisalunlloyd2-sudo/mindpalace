package com.mindpalace.blocks;

/** MP-033 ice, phase 1: frost shimmer + sparse glint sparkle as luma terms in
 * the existing bloom chain (winter zones engage via the env aux channel;
 * cool rim TINT and falling ice-crystal particles are phase 2 -- new seams,
 * declared per the block-assembly contract, not built here).
 *
 *   engagement: aux = frost intensity in [0,1] (engine: WINTER ? 1 : 0)
 *   lift:       +FROST_LIFT * aux                        (ice brightens)
 *   shimmer:    sin(phi) * SHIMMER_AMP * aux, phi += (SHIMMER_HZ + jitter)*dt
 *               jitter from a seeded splitmix (deterministic tick stream)
 *   glint:      hash-gated ~1% of ticks fires a decaying spark envelope
 *               (env=1, decay exp toward 0, tau GLINT_TAU) * GLINT_AMP * aux
 *   luma out:   clamp(luma + contribution, 0, 2); contribution in
 *               [-SHIMMER_AMP*aux, (FROST_LIFT+SHIMMER_AMP+GLINT_AMP)*aux]
 *
 * All state seeded/derived -- no wall clock, no Math.random. */
public final class FrostBlock implements Block {

    public static final float FROST_LIFT = 0.040f;
    public static final float SHIMMER_AMP = 0.015f;
    public static final float GLINT_AMP = 0.060f;
    public static final float GLINT_TAU = 0.35f; // seconds
    public static final float SHIMMER_HZ = 2.6f;
    private static final long GLINT_MOD = 97L; // ~1% of ticks

    private float aux, phi, glint;
    private long tick;
    private long smState; // splitmix64 state, seed-pinned
    private boolean cleaned;

    public FrostBlock() { this(0xC0FFEE11L); } // deterministic default

    public FrostBlock(long seed) {
        this.smState = seed;
        this.phi = 0f;
    }

    private long splitmix64() {
        smState += 0x9E3779B97F4A7C15L;
        long z = smState;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    @Override public String id() { return "mp-033-ice"; }

    @Override public void envUpdate(float dt, String weather, float aux) {
        if (cleaned) return;
        if (dt != dt) return; // NaN guard
        this.aux = aux < 0f ? 0f : (aux > 1f ? 1f : aux); // lawful clamp
    }

    @Override public void update(float dt, float px, float pz, float base) {
        if (cleaned || aux <= 0f) { return; }
        if (dt != dt || dt <= 0f) return;
        phi += (SHIMMER_HZ + (float) (Math.floorMod(splitmix64(), 41L) - 20) / 200f) * dt * (float) Math.PI * 2f;
        tick++;
        long h = splitmix64() ^ (tick * 0x9E3779B97F4A7C15L);
        if (Math.floorMod(h, GLINT_MOD) == 0L && glint < 0.05f) glint = 1f;
        glint -= glint * (dt / GLINT_TAU);
        if (glint < 1e-4f) glint = 0f;
    }

    @Override public float applyLuma(float luma) {
        if (cleaned) return luma;
        if (aux <= 0f) return luma;
        float shimmer = (float) Math.sin(phi) * SHIMMER_AMP * aux;
        float out = luma + aux * FROST_LIFT + shimmer + glint * GLINT_AMP * aux;
        if (out < 0f) out = 0f;
        if (out > 2f) out = 2f;
        return out;
    }

    @Override public void cleanup() { cleaned = true; aux = 0f; glint = 0f; phi = 0f; }

    @Override public boolean selfTest() {
        try {
            // P1 aux=0 (summer/off): exact identity, forever
            envUpdate(0.016f, "CLEAR", 0f);
            update(0.016f, 0f, 0f, 0f); update(0.016f, 0f, 0f, 0f);
            if (applyLuma(0.6f) != 0.6f) return false;

            // P2 winter stream: lawful band, positive lift visible
            envUpdate(0.016f, "SNOW", 1f);
            float mn = Float.MAX_VALUE, mx = -Float.MAX_VALUE;
            for (int i = 0; i < 600; i++) {
                update(0.016f, 0f, 0f, 0f);
                float l = applyLuma(0.6f);
                if (l < mn) mn = l; if (l > mx) mx = l;
            }
            // contract: [0.6 + LIFT - AMP, 0.6 + LIFT + AMP + GLINT] (+clamp slack)
            if (mn < 0.6f + FROST_LIFT - SHIMMER_AMP - 1e-4f) return false;
            if (mn > 0.6f + FROST_LIFT + SHIMMER_AMP + 1e-4f) return false;
            if (mx > 0.6f + FROST_LIFT + SHIMMER_AMP + GLINT_AMP + 1e-4f) return false;
            if (mx <= 0.6f + FROST_LIFT) return false; // shimmer must be live

            // P3 glint fired within the stream (sparse ~1% of 600 ~ 6 events)
            // (P2 already ran through it; assert the max rose above pure shimmer)
            if (mx < 0.6f + FROST_LIFT + SHIMMER_AMP + GLINT_AMP * 0.5f) return false;

            // P4 determinism: identical tick stream, bit-identical replay
            FrostBlock t1 = new FrostBlock(0xC0FFEE11L);
            t1.envUpdate(0.016f, "SNOW", 1f);
            FrostBlock t2 = new FrostBlock(0xC0FFEE11L);
            t2.envUpdate(0.016f, "SNOW", 1f);
            for (int i = 0; i < 120; i++) {
                t1.update(0.016f, 0f, 0f, 0f); t2.update(0.016f, 0f, 0f, 0f);
                float a = t1.applyLuma(0.6f), b = t2.applyLuma(0.6f);
                if (Float.floatToIntBits(a) != Float.floatToIntBits(b)) return false;
            }

            // P5 aux clamp laws: negative -> 0 (identity), >1 -> saturated band
            envUpdate(0.016f, "SNOW", -2f);
            if (applyLuma(0.7f) != 0.7f) return false;
            envUpdate(0.016f, "SNOW", 5f);
            if (applyLuma(0.7f) > 0.7f + FROST_LIFT + SHIMMER_AMP + GLINT_AMP + 1e-4f) return false;

            // P6 NaN dt ignored
            float before = applyLuma(0.6f);
            update(Float.NaN, 0f, 0f, 0f);
            if (Float.floatToIntBits(applyLuma(0.6f)) != Float.floatToIntBits(before)) return false;

            // P7 cleanup idempotent + identity
            cleanup(); cleanup();
            if (applyLuma(0.75f) != 0.75f) return false;
            return true;
        } finally {
            cleaned = false; aux = 0f; glint = 0f; phi = 0f; // revive after test
        }
    }
}
