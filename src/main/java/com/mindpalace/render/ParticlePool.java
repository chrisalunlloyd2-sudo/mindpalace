package com.mindpalace.render;

/**
 * MP-028 particle simulation core: a fixed-capacity pool in struct-of-arrays form.
 *
 * - No allocation after construction: spawn() and update() only write into the pre-allocated arrays.
 * - Live particles are always the dense prefix [0, count): spawn appends, a dead particle is removed by
 *   copying the last live one over it (O(1), no free list, no scan for a free slot).
 * - Deterministic: one splitmix64 stream seeded at construction; no Math.random, no wall clock.
 * - Pure Java (no GL), so the whole simulation is unit-testable headless; ParticleBatch does the drawing.
 * - Render-thread bound by contract (not thread-safe): create, update and read it from the render thread.
 */
public final class ParticlePool {
    final float[] x, y, z, vx, vy, vz, life, maxLife, size, r, g, b;
    private final int cap;
    private int count;
    private long rng;

    /** Acceleration applied to every particle (units/s^2). Default: a gentle fall. */
    public float gravityY = -2.0f;
    /** Constant wind acceleration on x and z (units/s^2). */
    public float windX = 0f, windZ = 0f;
    /** Linear drag per second (0 = none, 1 = stops in one second). Applied as a stable per-step factor. */
    public float drag = 0.0f;

    private static final float MAX_STEP = 0.1f;     // a hitch must not fling particles through the world

    public ParticlePool(int capacity, long seed) {
        this.cap = Math.max(0, capacity);
        x = new float[cap]; y = new float[cap]; z = new float[cap];
        vx = new float[cap]; vy = new float[cap]; vz = new float[cap];
        life = new float[cap]; maxLife = new float[cap]; size = new float[cap];
        r = new float[cap]; g = new float[cap]; b = new float[cap];
        rng = seed;
    }

    public int capacity() { return cap; }
    public int count() { return count; }
    public void clear() { count = 0; }

    /** Normalised remaining life in [0,1] (1 = just born, 0 = about to die): drives fade/colour ramps. */
    public float lifeFraction(int i) { return maxLife[i] > 0f ? Math.min(1f, Math.max(0f, life[i] / maxLife[i])) : 0f; }

    public float x(int i) { return x[i]; }
    public float y(int i) { return y[i]; }
    public float z(int i) { return z[i]; }
    public float size(int i) { return size[i]; }
    public float r(int i) { return r[i]; }
    public float g(int i) { return g[i]; }
    public float b(int i) { return b[i]; }

    /** @return false (and changes nothing) when the pool is full or the input is not a usable number. */
    public boolean spawn(float px, float py, float pz, float pvx, float pvy, float pvz,
                         float lifeSeconds, float sz, float cr, float cg, float cb) {
        if (count >= cap || !(lifeSeconds > 0f)
            || !(Float.isFinite(px) && Float.isFinite(py) && Float.isFinite(pz)
                && Float.isFinite(pvx) && Float.isFinite(pvy) && Float.isFinite(pvz) && Float.isFinite(sz))) return false;
        int i = count++;
        x[i] = px; y[i] = py; z[i] = pz;
        vx[i] = pvx; vy[i] = pvy; vz[i] = pvz;
        life[i] = lifeSeconds; maxLife[i] = lifeSeconds;
        size[i] = sz; r[i] = cr; g[i] = cg; b[i] = cb;
        return true;
    }

    /** Advance every live particle by dt seconds; expired ones are removed. NaN/negative dt is ignored. */
    public void update(float dt) {
        if (!(dt > 0f)) return;
        if (dt > MAX_STEP) dt = MAX_STEP;
        float damp = Math.max(0f, 1f - drag * dt);
        float ax = windX * dt, ay = gravityY * dt, az = windZ * dt;
        // Walk downward so swap-removal only ever pulls in an element that has ALREADY been advanced.
        for (int i = count - 1; i >= 0; i--) {
            life[i] -= dt;
            if (life[i] <= 0f) {
                int last = --count;
                if (i != last) copy(last, i);
                continue;
            }
            vx[i] = (vx[i] + ax) * damp;
            vy[i] = (vy[i] + ay) * damp;
            vz[i] = (vz[i] + az) * damp;
            x[i] += vx[i] * dt;
            y[i] += vy[i] * dt;
            z[i] += vz[i] * dt;
        }
    }

    private void copy(int from, int to) {
        x[to] = x[from]; y[to] = y[from]; z[to] = z[from];
        vx[to] = vx[from]; vy[to] = vy[from]; vz[to] = vz[from];
        life[to] = life[from]; maxLife[to] = maxLife[from]; size[to] = size[from];
        r[to] = r[from]; g[to] = g[from]; b[to] = b[from];
    }

    // ---- deterministic randomness (splitmix64) ----
    private long nextLong() {
        long z0 = (rng += 0x9E3779B97F4A7C15L);
        z0 = (z0 ^ (z0 >>> 30)) * 0xBF58476D1CE4E5B9L;
        z0 = (z0 ^ (z0 >>> 27)) * 0x94D049BB133111EBL;
        return z0 ^ (z0 >>> 31);
    }

    /** Uniform in [0,1). */
    public float nextFloat() { return (nextLong() >>> 40) / (float) (1L << 24); }

    public float range(float lo, float hi) { return lo + (hi - lo) * nextFloat(); }

    /** Omnidirectional burst on the unit sphere (uniform), speed in [speed*0.5, speed*1.5]. Returns spawned. */
    public int burst(float px, float py, float pz, int n, float speed, float lifeSeconds, float sz,
                     float cr, float cg, float cb) {
        int spawned = 0;
        for (int k = 0; k < n; k++) {
            float zc = 2f * nextFloat() - 1f;
            float phi = 6.2831855f * nextFloat();
            float s = (float) Math.sqrt(Math.max(0f, 1f - zc * zc));
            float sp = range(speed * 0.5f, speed * 1.5f);
            if (spawn(px, py, pz, s * (float) Math.cos(phi) * sp, zc * sp, s * (float) Math.sin(phi) * sp,
                    lifeSeconds * range(0.6f, 1.0f), sz, cr, cg, cb)) spawned++;
        }
        return spawned;
    }
}
