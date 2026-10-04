package com.mindpalace.blocks;

/** MP-032 Fire, phase 1 of 2: the light-FICKER term into bloom (the spec's
 * "flicker term into the existing bloom, not real dynamic lights"), driven by
 * proximity to fire anchors. Phase 2 (tracked under issue #178) adds the
 * flame billboards + embers once world props (torch/brazier meshes) exist.
 *
 * Math (all deterministic, seedable, zero-RNG-at-runtime):
 *   proximity  p(x,z) = max_i exp(-d_i^2 / r^2),  r = ANCHOR_RADIUS
 *   flick t:    n(t)  = 0.5 + 0.30*sin(2pi*f1*t) + 0.20*sin(2pi*f2*t + phi)
 *                       + 0.10*jitter(tick)
 *               jitter(tick) = splitmix64(tick*7907 + seed) mapped to [-1,1]
 *   luma delta  L    = p * baseLuma * (n(t) - 0.5) * DEPTH      (|L| <= p*base*DEPTH)
 *   f1 = 11.3 Hz (flame flutter), f2 = 3.7 Hz (breathing swell)
 *   DEPTH = 0.5  ->  |L| <= 0.25 * p * base (clamped [0,2] at the seam)
 *
 * Both directions: real flame light dips AND spikes around base (that is what
 * a fire does), so the flicker sign varies -- gate F.p4 asserts it. */
public final class FireBlock implements Block {

    public static final float ANCHOR_RADIUS = 6f;
    public static final float DEPTH = 0.5f;
    private static final float F1 = 11.3f, F2 = 3.7f;

    public static final class Anchor {
        public final float x, z;
        public final String name;
        public Anchor(float x, float z, String name) { this.x = x; this.z = z; this.name = name; }
    }

    private final long seed;
    private final Anchor[] anchors;
    private float flicker;
    private float lastBase, lastProx;
    private boolean tested, cleaned;
    private long tick;

    public FireBlock(long seed, Iterable<Anchor> anchors) {
        this.seed = seed;
        java.util.ArrayList<Anchor> list = new java.util.ArrayList<Anchor>();
        for (Anchor a : anchors) if (a != null) list.add(a);
        this.anchors = list.toArray(new Anchor[0]);
        this.phiSeed = splitmix64(seed);
    }

    private final float phiSeed; // phase offset: derived once in the ctor (deterministic)

    public static FireBlock seedDefault() {
        java.util.ArrayList<Anchor> a = new java.util.ArrayList<Anchor>();
        a.add(new Anchor(2f, -14f, "courtyard-forge"));
        a.add(new Anchor(-6f, -22f, "forest-camp"));
        return new FireBlock(1337L, a);
    }

    @Override public String id() { return "mp-032-fire"; }

    private static long splitmix64(long x) {
        x += 0x9E3779B97F4A7C15L;
        x = (x ^ (x >>> 30)) * 0xBF58476D1CE4E5B9L;
        x = (x ^ (x >>> 27)) * 0x94D049BB133111EBL;
        return x ^ (x >>> 31);
    }

    /** splitmix64 -> [-1,1]. Deterministic per tick. */
    private float jitter(long t) {
        return ((splitmix64(t * 7907L + seed) & 0xFFFFFFL) - 0x800000L) / (float) 0x800000L;
    }

    private float noiseAt(float t) {
        float twoPi = (float) (Math.PI * 2.0);
        float n = 0.5f
            + 0.30f * (float) Math.sin(twoPi * F1 * t)
            + 0.20f * (float) Math.sin(twoPi * F2 * t + phiSeed);
        n += 0.10f * jitter(tick);
        return n;
    }

    private float proximity(float px, float pz) {
        float p = 0f;
        for (Anchor a : anchors) {
            float dx = a.x - px, dz = a.z - pz;
            float d2 = dx * dx + dz * dz;
            p = Math.max(p, (float) Math.exp(-d2 / (ANCHOR_RADIUS * ANCHOR_RADIUS)));
        }
        return p;
    }

    @Override public void update(float dt, float px, float pz, float base) {
        if (cleaned) return;
        if (dt != dt || dt < 0f) { return; } // NaN/negative: ignore, never blow up
        lastBase = base;
        lastProx = proximity(px, pz);
        tick++;
        float n = noiseAt(tick / 60f); // 60Hz tick clock (frame budget)
        flicker = lastProx * lastBase * (n - 0.5f) * DEPTH;
    }

    @Override public float applyLuma(float luma) {
        if (cleaned) return luma;
        float out = luma + flicker;
        if (out < 0f) out = 0f;
        if (out > 2f) out = 2f;
        return out;
    }

    @Override public void cleanup() { cleaned = true; flicker = 0f; }

    /** 5 deterministic probes (F.p1..F.p5). Uses its own internal state. */
    @Override public boolean selfTest() {
        try {
            // F.p1 player far from anchors -> zero flicker (p == 0)
            update(0.016f, 500f, 500f, 1f);
            if (lastProx != 0f) return false;
            if (flicker != 0f) return false;

            // F.p2 player ON anchor -> bounded flicker |L| <= DEPTH*base
            update(0.016f, anchors[0].x, anchors[0].z, 1f);
            float onF = flicker;
            if (Math.abs(onF) > DEPTH * lastBase + 1e-4f) return false;

            // F.p3 determinism: same tick/args -> same value (splitmix is exact)
            long savedTick = tick;
            update(0.016f, anchors[0].x, anchors[0].z, 1f);
            float a = flicker;
            tick = savedTick; // replay must reproduce -- advance-then-rewind contract
            update(0.016f, anchors[0].x, anchors[0].z, 1f);
            if (flicker != a) return false;

            // F.p4 both directions over a 60-tick window (dips AND flares)
            boolean dipped = false, flared = false;
            for (int i = 0; i < 60; i++) {
                update(0.016f, anchors[0].x, anchors[0].z, 1f);
                if (flicker > 1e-3f) flared = true;
                if (flicker < -1e-3f) dipped = true;
            }
            if (!dipped || !flared) return false;
            // cleanup invariants asserted in p5 below

            cleaned = false;

            // F.p5 cleanup idempotent + identity after cleanup
            cleanup();
            cleanup();
            if (applyLuma(0.7f) != 0.7f) return false;
            return true;
        } finally {
            cleaned = false; // never leave the block permanently cleaned after a test
            if (anchors.length > 0) update(0.016f, anchors[0].x, anchors[0].z, 1f);
        }
    }
}
