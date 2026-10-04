package com.mindpalace.blocks;
/** MP-032 Fire, phase 2 (HELIX EDITION): light-FICKER term + ember particles.
 *
 * Flicker (phase 1, unchanged math) drives bloom luma; embers (phase 2) are
 * CLOSED-FORM helical sparks: position is a pure function of age, never
 * integrated. Each ember stores only its spawn constants (x0,y0,z0,vx,vy,vz,
 * theta0,omega,rg); emit computes:
 *
 *   r(age)  = R0 + RG * age                  (helix widens as the spark rises)
 *   th(age) = theta0 + omega * age           (SLOW spiral, omega in 1.2..2.2 rad/s)
 *   x = x0 + vx*age + r*cos(th)
 *   z = z0 + vz*age + r*sin(th)
 *   y = y0 + vy*age                          (gravity-free rise 0.90..1.14 m/s)
 *   shimmer: size *= 0.85 + 0.15*sin(2pi*FSHIM*age + theta0), FSHIM = 7.3 Hz
 *
 * Deterministic: no Math.random, no wall clock in particle state; splitmix64
 * streams seed every constant. Ring is drop-oldest (nothing lives forever);
 * slots free at age > EMBER_LIFE (sentinel: age = SLOT_EMPTY = -1 -- the old
 * 0.0 sentinel double-booked "empty" with "just born", which left newborn
 * embers invisible; root-caused and fixed).
 *
 * Render contract v1.2: emitRender() emits flat rows (x,y,z,size,warmth,KIND)
 * with KIND=0 (ember heat ramp, engine-side). The blocks package stays
 * JOML-free and provable under any toolchain; the ENGINE owns the GL. */
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
    // -- phase 2: helical ember ring --
    public static final int EMBERS_PER_ANCHOR = 24;
    static final int STRIDE = 10; // x0,y0,z0,vx,vy,vz,theta0,omega,rg,age
    public static final float EMBER_LIFE = 2.0f;
    public static final float R0 = 0.06f, RG = 0.10f;
    public static final float FSHIM = 7.3f;
    static final float SLOT_EMPTY = -1f;
    public static final float KIND_EMBER = 0f;
    private final float[] embers;
    private int emberHead;
    public FireBlock(long seed, Iterable<Anchor> anchors) {
        this.seed = seed;
        java.util.ArrayList<Anchor> list = new java.util.ArrayList<Anchor>();
        for (Anchor a : anchors) if (a != null) list.add(a);
        this.anchors = list.toArray(new Anchor[0]);
        this.phiSeed = splitmix64(seed);
        this.embers = new float[EMBERS_PER_ANCHOR * this.anchors.length * STRIDE];
        java.util.Arrays.fill(this.embers, SLOT_EMPTY);
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
        if (anchors.length == 0) return 0f;
        float p = 0f;
        for (Anchor an : anchors) {
            float dx = px - an.x;
            float dz = pz - an.z;
            float d2 = dx * dx + dz * dz;
            float v = (float) Math.exp(-d2 / (ANCHOR_RADIUS * ANCHOR_RADIUS));
            if (v > p) p = v;
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
        // ember spawn: ambient tail + heat surge from the REAL flicker term.
        // Spawn probability is EXPLICIT and readable (lesson: design gates on
        // distributions you have read): roll is uniform [0,1) from jitter,
        // ambient 0.0015/tick, surge up to ~0.6 at flicker peak (~0.30).
        if (embers.length > 0) {
            float spawnP = 0.0015f + 2.0f * Math.max(0f, flicker);
            float roll = (jitter(tick) + 1f) / 2f;
            if (roll < spawnP) {
                int slot = (int) Math.floorMod(tick, (long) anchors.length);
                Anchor an = anchors[slot];
                int o = emberHead * STRIDE;
                embers[o + 0] = an.x + (Math.floorMod(splitmix64(tick * 31L + slot), 71) - 35) / 100f;
                embers[o + 1] = 1.05f;
                embers[o + 2] = an.z + (Math.floorMod(splitmix64(tick * 17L + slot * 977L), 71) - 35) / 100f;
                embers[o + 3] = (Math.floorMod(splitmix64(tick * 13L + 1L), 21) - 10) / 60f;   // vx
                embers[o + 4] = 0.9f + (Math.floorMod(splitmix64(tick * 29L + 2L), 25)) / 100f; // vy
                embers[o + 5] = (Math.floorMod(splitmix64(tick * 23L + 3L), 21) - 10) / 60f;   // vz
                embers[o + 6] = (Math.floorMod(splitmix64(tick * 61L + 5L), 629L)) / 100f;      // theta0 0..6.28
                embers[o + 7] = 1.2f + (Math.floorMod(splitmix64(tick * 71L + 7L), 101L)) / 100f; // omega 1.2..2.21 rad/s
                embers[o + 8] = 0.08f + (Math.floorMod(splitmix64(tick * 83L + 9L), 61L)) / 100f; // rg 0.08..0.14
                embers[o + 9] = 0f;             // age: born now
                emberHead = (emberHead + 1) % (embers.length / STRIDE); // drop-oldest when full
            }
        }
        // age advance ONLY (positions are closed-form at emit time)
        int slots = embers.length / STRIDE;
        for (int i = 0; i < slots; i++) {
            int o = i * STRIDE;
            float age = embers[o + 9];
            if (age < 0f) continue;         // SLOT_EMPTY
            age += dt;
            if (age > EMBER_LIFE) embers[o + 9] = SLOT_EMPTY; // nothing lives forever
            else embers[o + 9] = age;
        }
    }
    @Override public float applyLuma(float luma) {
        if (cleaned) return luma;
        float out = luma + flicker;
        if (out < 0f) out = 0f;
        if (out > 2f) out = 2f;
        return out;
    }
    /** v1.2 render contract: closed-form helical rows (x,y,z,size,warmth,KIND=0). */
    @Override public int emitRender(float[] out, int offsetRows, int maxRows) {
        if (cleaned || out == null) return 0;
        int rows = 0;
        int slots = embers.length / STRIDE;
        for (int i = 0; i < slots && rows < maxRows; i++) {
            int o = i * STRIDE;
            float age = embers[o + 9];
            if (age < 0f) continue;
            float life = age / EMBER_LIFE;
            float r = R0 + embers[o + 8] * age;
            float th = embers[o + 6] + embers[o + 7] * age;
            float shimmer = 0.85f + 0.15f * (float) Math.sin((float) (Math.PI * 2.0) * FSHIM * age + embers[o + 6]);
            int w = (offsetRows + rows) * 6;
            out[w + 0] = embers[o + 0] + embers[o + 3] * age + r * (float) Math.cos(th);
            out[w + 1] = embers[o + 1] + embers[o + 4] * age;
            out[w + 2] = embers[o + 2] + embers[o + 5] * age + r * (float) Math.sin(th);
            out[w + 3] = (0.045f + 0.020f * life) * shimmer;
            out[w + 4] = life;
            out[w + 5] = KIND_EMBER;
            rows++;
        }
        return rows;
    }
    /** Spawned-ember rise band (public for proofs): vy in [VY_MIN, VY_MIN+0.24]. */
    public static final float VY_MIN = 0.90f;
    @Override public void cleanup() {
        cleaned = true; flicker = 0f; // phase-1 state
        java.util.Arrays.fill(embers, SLOT_EMPTY); emberHead = 0; // phase-2: free the ring
    }
    /** 5 deterministic probes (F.p1..F.p5), unchanged phase-1 semantics. */
    @Override public boolean selfTest() {
        try {
            update(0.016f, 500f, 500f, 1f);
            if (lastProx != 0f) return false;
            if (flicker != 0f) return false;
            update(0.016f, anchors[0].x, anchors[0].z, 1f);
            float onF = flicker;
            if (Math.abs(onF) > DEPTH * lastBase + 1e-4f) return false;
            long savedTick = tick;
            update(0.016f, anchors[0].x, anchors[0].z, 1f);
            float a = flicker;
            tick = savedTick; // replay must reproduce -- advance-then-rewind contract
            update(0.016f, anchors[0].x, anchors[0].z, 1f);
            if (flicker != a) return false;
            boolean dipped = false, flared = false;
            for (int i = 0; i < 60; i++) {
                update(0.016f, anchors[0].x, anchors[0].z, 1f);
                if (flicker > 1e-3f) flared = true;
                if (flicker < -1e-3f) dipped = true;
            }
            if (!dipped || !flared) return false;
            cleaned = false;
            cleanup();
            cleanup();
            if (applyLuma(0.7f) != 0.7f) return false;
            return phase2Probes();
        } finally {
            cleaned = false; // never leave the block permanently cleaned after a test
            if (anchors.length > 0) update(0.016f, anchors[0].x, anchors[0].z, 1f);
        }
    }
    // ---- MP-032 phase 2 probes (F.p6..F.p9, helix edition) ----
    boolean phase2Probes() {
        java.util.ArrayList<Anchor> one = new java.util.ArrayList<Anchor>();
        one.add(new Anchor(0f, 0f, "p6"));
        FireBlock f = new FireBlock(1337L, one);
        f.envUpdate(0.016f, "CLEAR", 0f);
        float[] out = new float[64 * 6];
        int seen = 0;
        for (int i = 0; i < 200; i++) {
            f.update(0.016f, 0f, 0f, 1f);
            int w = f.emitRender(out, 0, 64);
            if (w > EMBERS_PER_ANCHOR) return false; // ring cap
            seen = Math.max(seen, w);
        }
        if (seen == 0) return false;
        // P7 helix bands: rise floor/apex, shimmer size window, warmth, kind
        for (int i = 0; i < 120; i++) {
            f.update(0.016f, 0f, 0f, 1f);
            int w = f.emitRender(out, 0, 64);
            for (int r = 0; r < w; r++) {
                float y = out[r * 6 + 1]; if (y < 1.0f || y > 3.4f) return false;
                float warm = out[r * 6 + 4]; if (warm < 0f || warm > 1.0f + 1e-6f) return false;
                float s = out[r * 6 + 3];
                if (s < 0.045f * 0.70f || s > 0.065f * 1.01f) return false; // 0.85..1.00 shimmer on both ends
                if (out[r * 6 + 5] != KIND_EMBER) return false;
            }
        }
        // P8 nothing-lives-forever: 6s of ticks -> every old slot freed (warmth<=1 exact)
        FireBlock g = new FireBlock(1337L, one);
        g.envUpdate(0.016f, "CLEAR", 0f);
        for (int i = 0; i < 400; i++) g.update(0.016f, 0f, 0f, 1f);
        int w = g.emitRender(out, 0, 64);
        for (int r = 0; r < w; r++) if (out[r * 6 + 4] > 1.0f + 1e-6f) return false;
        // P9 bit-exact replay: same seed, same stream -> identical rows
        float[] a1 = new float[64 * 6], a2 = new float[64 * 6];
        FireBlock f1 = new FireBlock(1337L, one), f2 = new FireBlock(1337L, one);
        f1.envUpdate(0.016f, "CLEAR", 0f); f2.envUpdate(0.016f, "CLEAR", 0f);
        for (int i = 0; i < 300; i++) { f1.update(0.016f,0f,0f,1f); f2.update(0.016f,0f,0f,1f); }
        int c1 = f1.emitRender(a1, 0, 64), c2 = f2.emitRender(a2, 0, 64);
        if (c1 != c2) return false;
        for (int i = 0; i < c1 * 6; i++)
            if (Float.floatToIntBits(a1[i]) != Float.floatToIntBits(a2[i])) return false;
        return true;
    }
}
