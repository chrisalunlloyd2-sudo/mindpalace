package com.mindpalace.render;

/**
 * MP-028 point emitter: spawns into a ParticlePool at a steady rate inside a cone. Allocation-free and
 * deterministic (all randomness comes from the pool's own seeded stream, so replays are identical).
 */
public final class ParticleEmitter {
    public float x, y, z;
    /** Particles per second. */
    public float rate = 50f;
    /** Cone axis (need not be normalised) and full spread angle in radians (PI = a hemisphere-ish cone). */
    public float dirX = 0f, dirY = 1f, dirZ = 0f;
    public float spread = 0.6f;
    public float speedMin = 1f, speedMax = 3f;
    public float lifeMin = 0.5f, lifeMax = 1.5f;
    public float size = 0.08f;
    public float r = 1f, g = 0.8f, b = 0.3f;

    private float carry;                       // fractional particles carried to the next frame
    private static final int MAX_PER_STEP = 64; // a long frame must not burst the whole pool at once

    public ParticleEmitter at(float px, float py, float pz) { x = px; y = py; z = pz; return this; }

    /** @return how many particles were actually spawned this step (0 when the pool is full). */
    public int update(float dt, ParticlePool pool) {
        if (!(dt > 0f) || !(rate > 0f)) return 0;
        carry += rate * Math.min(dt, 0.1f);
        int n = Math.min((int) carry, MAX_PER_STEP);
        carry -= n;
        if (carry > 1f) carry = 1f;            // never accumulate a debt while the pool is full

        float len = (float) Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
        float ax = 0f, ay = 1f, az = 0f;
        if (len > 1e-6f) { ax = dirX / len; ay = dirY / len; az = dirZ / len; }
        // Orthonormal basis (u, w) perpendicular to the axis: u = normalise(axis x helper), w = axis x u.
        // The helper is (0,1,0) unless the axis is nearly vertical, then (1,0,0). Plain locals, no allocation.
        float ux, uy, uz;
        if (Math.abs(ay) < 0.9f) { ux = -az; uy = 0f; uz = ax; }       // axis x (0,1,0)
        else { ux = 0f; uy = az; uz = -ay; }                           // axis x (1,0,0)
        float ul = (float) Math.sqrt(ux * ux + uy * uy + uz * uz);
        ux /= ul; uy /= ul; uz /= ul;                                   // ul > 0 by construction of the two branches
        float wx = ay * uz - az * uy, wy = az * ux - ax * uz, wz = ax * uy - ay * ux;

        int spawned = 0;
        for (int i = 0; i < n; i++) {
            float cosT = 1f - nextCone(pool);                                  // within the cone around the axis
            float sinT = (float) Math.sqrt(Math.max(0f, 1f - cosT * cosT));
            float phi = 6.2831855f * pool.nextFloat();
            float cp = (float) Math.cos(phi), sp = (float) Math.sin(phi);
            float dx = ax * cosT + (ux * cp + wx * sp) * sinT;
            float dy = ay * cosT + (uy * cp + wy * sp) * sinT;
            float dz = az * cosT + (uz * cp + wz * sp) * sinT;
            float speed = pool.range(speedMin, speedMax);
            if (pool.spawn(x, y, z, dx * speed, dy * speed, dz * speed, pool.range(lifeMin, lifeMax), size, r, g, b)) {
                spawned++;
            } else {
                break;                                                         // pool full: stop, do not churn the RNG
            }
        }
        return spawned;
    }

    /** Fraction of (1 - cos(spread/2)) in [0, that]: uniform over the cone's solid angle. */
    private float nextCone(ParticlePool pool) {
        float half = Math.max(0f, Math.min(3.1415927f, spread)) * 0.5f;
        return pool.nextFloat() * (1f - (float) Math.cos(half));
    }
}
