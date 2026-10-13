package com.mindpalace.blocks;
/** MP-031 Fireflies: soft glowing points with Lissajous drift and pulse blink,
 * dusk/night only, homes clustered near the groves (forest-camp / courtyard
 * clearings -- phase-1 homes; a later phase may wire real habitat facts).
 *
 * Math (closed-form, deterministic, zero-RNG-at-runtime):
 *   homes: golden-angle phyllotaxis around 2 grove centers
 *   drift: x(t) = hx + A1*sin(w1*t + phi) + A2*sin(w2*PHI*t + phi*1.3)
 *          z(t) = hz + A1*sin(w2*t + phi*0.7) + A2*sin(w1*SQ2*t + phi*0.9)
 *          y(t) = hover + 0.35*sin(0.17*t + phi*2.1)   (0.20..0.90 m)
 *          with w1=0.31, w2=0.23 rad/s (incommensurate pair -> the swarm's
 *          paths never repeat, yet each fly's position at time t is exact)
 *   blink: b(t) = max(0, sin(2pi*((t mod T)/T)))^3  -- sharp pulse, long dark
 *          T = 1.1 + 0.6*frac(i*0.618) per fly (desynchronized chorus)
 *   night envelope from the real clock (pure function, injectable for probes):
 *          hours [0,5]=1, [5,6) fade, (6,18)=0, [18,20) dusk fade in, [20,24)=1
 *
 * Render contract v1.2 rows: (x,y,z,size,blink,KIND=1). Engine owns the GL. */
public final class FireflyBlock implements Block {
    public static final int FLY_COUNT = 14;
    public static final float KIND_FIREFLY = 1f;
    private static final float GA = 2.39996323f; // golden angle (rad)
    public static final float[] GROVE_X = {-6f, 2f};   // forest-camp, courtyard clearing
    public static final float[] GROVE_Z = {-22f, -14f};
    private final float[] hx, hz, phi, period, w1, w2;
    private float t;
    private boolean cleaned;
    public FireflyBlock() { this(20261004L); }
    public FireflyBlock(long seed) {
        hx = new float[FLY_COUNT]; hz = new float[FLY_COUNT];
        phi = new float[FLY_COUNT]; period = new float[FLY_COUNT];
        w1 = new float[FLY_COUNT]; w2 = new float[FLY_COUNT];
        for (int i = 0; i < FLY_COUNT; i++) {
            int g = i & 1;                                    // alternate groves
            float rr = 1.2f * (float) Math.sqrt((i >> 1) + 0.5);
            float ang = i * GA;
            hx[i] = GROVE_X[g] + rr * (float) Math.cos(ang);
            hz[i] = GROVE_Z[g] + rr * (float) Math.sin(ang);
            float fr = splitmix01(seed + i * 7919L);
            phi[i] = i * 0.618f * 2f * (float) Math.PI;
            period[i] = 1.1f + 0.6f * ((i * 0.618f) % 1f);
            w1[i] = 0.31f + 0.02f * splitmix01(seed + i * 104729L);
            w2[i] = 0.23f + 0.02f * splitmix01(seed + i * 1299709L);
        }
    }
    private static float splitmix01(long x) {
        x += 0x9E3779B97F4A7C15L;
        x = (x ^ (x >>> 30)) * 0xBF58476D1CE4E5B9L;
        x = (x ^ (x >>> 27)) * 0x94D049BB133111EBL;
        return ((x ^ (x >>> 31)) & 0xFFFFFFL) / (float) 0x1000000L;
    }
    /** Night envelope, pure function of fractional hour (injectable for probes). */
    static float envelopeFor(float hourF) {
        float h = hourF % 24f; if (h < 0f) h += 24f;
        if (h < 5f) return 1f;
        if (h < 6f) return 6f - h;          // dawn fade 1 -> 0
        if (h < 18f) return 0f;
        if (h < 20f) return (h - 18f) / 2f; // dusk fade 0 -> 1
        return 1f;
    }
    float blinkFor(int i, float time) {
        float ph = 2f * (float) Math.PI * ((time % period[i]) / period[i]);
        float s = (float) Math.sin(ph);
        return s > 0f ? s * s * s : 0f;
    }
    void positionAt(int i, float time, float[] xyz) {
        float A1 = 1.2f, A2 = 0.5f, PHI = 1.6180339f, SQ2 = 1.4142135f;
        xyz[0] = hx[i] + A1 * (float) Math.sin(w1[i] * time + phi[i])
                      + A2 * (float) Math.sin(w2[i] * PHI * time + phi[i] * 1.3f);
        xyz[1] = 0.55f + 0.35f * (float) Math.sin(0.17f * time + phi[i] * 2.1f);
        xyz[2] = hz[i] + A1 * (float) Math.sin(w2[i] * time + phi[i] * 0.7f)
                      + A2 * (float) Math.sin(w1[i] * SQ2 * time + phi[i] * 0.9f);
    }
    /** Real emission, with hour injected (probes synthesize; runtime reads the clock). */
    int emitRowsAt(float hourF, float[] out, int offsetRows, int maxRows) {
        if (cleaned || out == null) return 0;
        float env = envelopeFor(hourF);
        if (env <= 0.02f) return 0;
        int rows = 0;
        for (int i = 0; i < FLY_COUNT && rows < maxRows; i++) {
            float blink = blinkFor(i, t) * env;
            if (blink <= 0.02f) continue;
            float[] xyz = new float[3];
            positionAt(i, t, xyz);
            int w = (offsetRows + rows) * 6;
            out[w + 0] = xyz[0]; out[w + 1] = xyz[1]; out[w + 2] = xyz[2];
            out[w + 3] = 0.030f + 0.025f * blink;
            out[w + 4] = blink;
            out[w + 5] = KIND_FIREFLY;
            rows++;
        }
        return rows;
    }
    @Override public String id() { return "mp-031-fireflies"; }
    @Override public void update(float dt, float playerX, float playerZ, float base) {
        if (cleaned) return;
        if (dt != dt || dt <= 0f) return;
        t += dt;
    }
    @Override public float applyLuma(float luma) { return luma; } // glow, not luma -- engine draws the rows
    @Override public int emitRender(float[] out, int offsetRows, int maxRows) {
        java.time.LocalTime now = java.time.LocalTime.now();
        float hourF = now.getHour() + now.getMinute() / 60f;
        return emitRowsAt(hourF, out, offsetRows, maxRows);
    }
    @Override public void cleanup() { cleaned = true; }
    @Override public boolean selfTest() {
        try {
            // FF1 envelope law: night 1, day 0, dusk mid-fade, exact edges
            if (envelopeFor(2.0f) != 1f) return false;
            if (envelopeFor(22.5f) != 1f) return false;
            if (envelopeFor(12.0f) != 0f) return false;
            if (envelopeFor(19.0f) != 0.5f) return false;
            if (envelopeFor(18.0f) != 0f) return false;
            if (envelopeFor(6.0f) != 0f) return false;
            if (Math.abs(envelopeFor(5.5f) - 0.5f) > 1e-6f) return false;
            // FF2 home bounds: drift amplitude never exceeds 1.2+0.5 per axis
            float[] xyz = new float[3];
            for (int i = 0; i < FLY_COUNT; i++) {
                for (float probe = 0f; probe < 4000f; probe += 13.7f) {
                    positionAt(i, probe, xyz);
                    if (Math.abs(xyz[0] - hx[i]) > 1.75f) return false;
                    if (Math.abs(xyz[2] - hz[i]) > 1.75f) return false;
                    if (xyz[1] < 0.15f || xyz[1] > 0.95f) return false;
                }
            }
            // FF3 blink chorus: dark phase exists, bright pulse exists, bounded
            boolean dark = false, bright = false;
            for (float probe = 0f; probe < 30f; probe += 0.05f) {
                float b = blinkFor(0, probe);
                if (b == 0f) dark = true;
                if (b > 0.5f) bright = true;
                if (b < 0f || b > 1f + 1e-6f) return false;
            }
            if (!dark || !bright) return false;
            // FF4 synthetic-night emission: rows appear, bands lawful
            float[] out = new float[64 * 6];
            // (no t=0 assertion: blink phase starts dark by construct -- the
            // chorus proof is the SWEEP over t below, not a single instant)
            int w2 = 0;
            for (int i = 0; i < 600; i++) { update(0.016f, 0f, 0f, 1f); w2 = Math.max(emitRowsAt(22.0f, out, 0, 64), w2); }
            if (w2 == 0) return false;
            for (int r = 0; r < w2; r++) {
                float blink = out[r * 6 + 4];
                if (blink < 0f || blink > 1f + 1e-6f) return false;
                if (out[r * 6 + 5] != KIND_FIREFLY) return false;
                float s = out[r * 6 + 3];
                if (s < 0.030f - 1e-6f || s > 0.055f + 1e-6f) return false;
            }
            // FF5 cleanup: emits nothing after
            cleanup();
            if (emitRowsAt(22.0f, out, 0, 64) != 0) return false;
            return true;
        } finally {
            cleaned = false; // revive after test (bool-false law: never leave hidden by a test)
        }
    }
}
