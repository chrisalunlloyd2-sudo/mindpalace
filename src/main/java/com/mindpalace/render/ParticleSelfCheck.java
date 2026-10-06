package com.mindpalace.render;

import java.lang.management.ManagementFactory;
import java.util.Arrays;

/**
 * MP-028 (#174) headless proof of the particle core: quality tiers, pool cap, swap-remove compaction, physics,
 * determinism, quad geometry, emitter behaviour and "no allocation per frame" (measured, not asserted).
 * No GL context needed. run() returns "" on success, otherwise the first failure.
 */
public final class ParticleSelfCheck {
    private ParticleSelfCheck() { }

    private static boolean near(float a, float b, float eps) { return Math.abs(a - b) <= eps; }

    public static String run() {
        // 1. quality tiers
        if (ParticleQuality.OFF.cap != 0 || ParticleQuality.LOW.cap != 300
                || ParticleQuality.MEDIUM.cap != 800 || ParticleQuality.HIGH.cap != 2000) return "tier caps are not 0/300/800/2000";
        if (ParticleQuality.parse("med", ParticleQuality.OFF) != ParticleQuality.MEDIUM
                || ParticleQuality.parse("garbage", ParticleQuality.LOW) != ParticleQuality.LOW
                || ParticleQuality.parse(null, ParticleQuality.HIGH) != ParticleQuality.HIGH) return "quality parsing";

        // 2. fixed cap and input validation
        ParticlePool cap = new ParticlePool(10, 1L);
        for (int i = 0; i < 10; i++) if (!cap.spawn(0, 0, 0, 0, 0, 0, 1f, 1f, 1, 1, 1)) return "spawn below the cap failed";
        if (cap.spawn(0, 0, 0, 0, 0, 0, 1f, 1f, 1, 1, 1) || cap.count() != 10) return "the 11th spawn was not rejected";
        ParticlePool bad = new ParticlePool(4, 1L);
        if (bad.spawn(Float.NaN, 0, 0, 0, 0, 0, 1f, 1f, 1, 1, 1) || bad.spawn(0, 0, 0, 0, 0, 0, 0f, 1f, 1, 1, 1)
                || bad.spawn(0, 0, 0, Float.POSITIVE_INFINITY, 0, 0, 1f, 1f, 1, 1, 1) || bad.count() != 0) return "bad input was accepted";
        if (new ParticlePool(0, 1L).spawn(0, 0, 0, 0, 0, 0, 1f, 1f, 1, 1, 1)) return "a zero-capacity pool accepted a spawn";

        // 3. expiry compaction: survivors stay intact and unique after swap-removal
        ParticlePool comp = new ParticlePool(100, 7L);
        for (int i = 0; i < 100; i++) comp.spawn(i, 0, 0, 0, 0, 0, (i % 2 == 0) ? 0.05f : 5f, i, 1, 1, 1);
        comp.update(0.1f);
        if (comp.count() != 50) return "compaction left " + comp.count() + " live (expected 50)";
        boolean[] seen = new boolean[100];
        for (int i = 0; i < comp.count(); i++) {
            int id = Math.round(comp.size(i));
            if (id % 2 == 0 || seen[id] || !near(comp.x(i), id, 0.001f)) return "a survivor was corrupted or duplicated";
            seen[id] = true;
        }

        // 4. physics: gravity, wind, drag, dt clamp, bad dt
        ParticlePool ph = new ParticlePool(1, 1L);
        ph.gravityY = -2f;
        ph.spawn(0, 0, 0, 1f, 0, 0, 10f, 1f, 1, 1, 1);
        ph.update(0.1f);
        if (!near(ph.x(0), 0.1f, 1e-5f) || !near(ph.y(0), -0.02f, 1e-5f)) return "gravity integration";
        ParticlePool wd = new ParticlePool(1, 1L);
        wd.gravityY = 0f; wd.windX = 10f; wd.drag = 1f;
        wd.spawn(0, 0, 0, 1f, 0, 0, 10f, 1f, 1, 1, 1);
        wd.update(0.1f);                                    // vx = (1 + 10*0.1) * (1 - 0.1) = 1.8
        if (!near(wd.x(0), 0.18f, 1e-5f)) return "wind/drag integration";
        ParticlePool big = new ParticlePool(1, 1L), small = new ParticlePool(1, 1L);
        big.spawn(0, 0, 0, 1, 1, 1, 10f, 1f, 1, 1, 1); small.spawn(0, 0, 0, 1, 1, 1, 10f, 1f, 1, 1, 1);
        big.update(5f); small.update(0.1f);
        if (!near(big.x(0), small.x(0), 1e-6f) || !near(big.y(0), small.y(0), 1e-6f)) return "a long frame was not clamped";
        float bx = small.x(0);
        small.update(Float.NaN); small.update(-1f); small.update(0f);
        if (small.x(0) != bx) return "NaN/negative dt moved a particle";

        // 5. determinism: same seed => identical state; different seed => different state
        ParticlePool d1 = new ParticlePool(500, 42L), d2 = new ParticlePool(500, 42L), d3 = new ParticlePool(500, 43L);
        ParticleEmitter e1 = new ParticleEmitter().at(1, 2, 3), e2 = new ParticleEmitter().at(1, 2, 3), e3 = new ParticleEmitter().at(1, 2, 3);
        for (int i = 0; i < 120; i++) {
            e1.update(0.016f, d1); d1.update(0.016f);
            e2.update(0.016f, d2); d2.update(0.016f);
            e3.update(0.016f, d3); d3.update(0.016f);
            if (i == 60) { d1.burst(0, 0, 0, 30, 3f, 1f, 0.1f, 1, 0, 0); d2.burst(0, 0, 0, 30, 3f, 1f, 0.1f, 1, 0, 0); d3.burst(0, 0, 0, 30, 3f, 1f, 0.1f, 1, 0, 0); }
        }
        if (d1.count() != d2.count() || d1.count() == 0
                || !Arrays.equals(Arrays.copyOf(d1.x, d1.count()), Arrays.copyOf(d2.x, d2.count()))
                || !Arrays.equals(Arrays.copyOf(d1.vy, d1.count()), Arrays.copyOf(d2.vy, d2.count()))) return "same seed did not reproduce";
        if (Arrays.equals(Arrays.copyOf(d1.x, Math.min(d1.count(), d3.count())), Arrays.copyOf(d3.x, Math.min(d1.count(), d3.count())))) return "different seeds gave the same state";

        // 6. quad geometry: camera-facing corners, uv, colour, alpha, overflow
        ParticleQuadBuffer qb = new ParticleQuadBuffer(4);
        qb.begin(1, 0, 0, 0, 1, 0);
        if (!qb.add(10, 20, 30, 2f, 0.1f, 0.2f, 0.3f, 0.4f) || qb.quadCount() != 1) return "quad add";
        float[] v = qb.data();
        float[][] corners = {{9, 19, 30, 0, 0}, {11, 19, 30, 1, 0}, {11, 21, 30, 1, 1}, {9, 21, 30, 0, 1}};
        for (int c = 0; c < 4; c++) {
            int o = c * ParticleQuadBuffer.FLOATS_PER_VERTEX;
            for (int k = 0; k < 5; k++) if (!near(v[o + k], corners[c][k], 1e-5f)) return "quad corner " + c + " component " + k;
            if (!near(v[o + 5], 0.1f, 1e-6f) || !near(v[o + 8], 0.4f, 1e-6f)) return "quad colour/alpha";
        }
        qb.add(0, 0, 0, 1, 1, 1, 1, 1); qb.add(0, 0, 0, 1, 1, 1, 1, 1); qb.add(0, 0, 0, 1, 1, 1, 1, 1);
        if (qb.add(0, 0, 0, 1, 1, 1, 1, 1) || qb.quadCount() != 4) return "quad buffer overflow was not refused";
        ParticlePool fade = new ParticlePool(3, 1L);
        fade.gravityY = 0;
        fade.spawn(0, 0, 0, 0, 0, 0, 2f, 1f, 1, 1, 1);
        ParticleQuadBuffer qf = new ParticleQuadBuffer(3);
        qf.begin(1, 0, 0, 0, 1, 0);
        for (int i = 0; i < 10; i++) fade.update(0.1f);        // 1 s in clamp-sized steps: half the life gone
        if (qf.addPool(fade, 1f) != 1 || !near(qf.data()[8], 0.5f, 1e-5f)) return "alpha does not follow remaining life";

        // 7. emitter: rate, cone axis, full pool
        ParticlePool ep = new ParticlePool(1000, 5L);
        ParticleEmitter em = new ParticleEmitter().at(0, 0, 0);
        em.rate = 100f; em.spread = 0f; em.dirX = 0; em.dirY = 1; em.dirZ = 0; em.lifeMin = 5f; em.lifeMax = 6f;
        int total = 0;
        for (int i = 0; i < 10; i++) total += em.update(0.1f, ep);
        if (total < 99 || total > 100) return "emitter rate: " + total + " in 1 s at 100/s";
        for (int i = 0; i < ep.count(); i++) if (!near(ep.vx[i], 0f, 1e-4f) || !near(ep.vz[i], 0f, 1e-4f) || ep.vy[i] <= 0f) return "zero-spread emitter left its axis";
        ParticlePool full = new ParticlePool(5, 5L);
        ParticleEmitter e5 = new ParticleEmitter();
        e5.rate = 1000f;
        e5.update(0.1f, full);
        if (full.count() != 5 || e5.update(0.1f, full) != 0) return "emitter overflowed a full pool";
        ParticleEmitter side = new ParticleEmitter();
        side.rate = 200f; side.dirX = 1; side.dirY = 0; side.dirZ = 0; side.spread = 0.2f;
        ParticlePool sp = new ParticlePool(400, 9L);
        side.update(0.1f, sp);
        for (int i = 0; i < sp.count(); i++) if (sp.vx[i] <= 0f || Math.abs(sp.vy[i]) > sp.vx[i] || Math.abs(sp.vz[i]) > sp.vx[i]) return "a sideways cone left its axis";

        // 8. no allocation per frame (measured with the JVM's own per-thread allocation counter)
        String alloc = allocationCheck();
        if (!alloc.isEmpty()) return alloc;
        return "";
    }

    private static String allocationCheck() {
        com.sun.management.ThreadMXBean mx;
        try {
            mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
            if (!mx.isThreadAllocatedMemorySupported()) return "";      // cannot measure here: not a failure
            mx.setThreadAllocatedMemoryEnabled(true);
        } catch (Throwable t) {
            return "";
        }
        ParticlePool pool = new ParticlePool(2000, 3L);
        ParticleEmitter em = new ParticleEmitter().at(0, 0, 0);
        em.rate = 3000f; em.lifeMin = 0.4f; em.lifeMax = 0.9f;
        ParticleQuadBuffer qb = new ParticleQuadBuffer(2000);
        for (int i = 0; i < 300; i++) frame(em, pool, qb);              // warm up the JIT and fill the pool
        long id = Thread.currentThread().getId();
        long before = mx.getThreadAllocatedBytes(id);
        for (int i = 0; i < 3000; i++) frame(em, pool, qb);
        long delta = mx.getThreadAllocatedBytes(id) - before;
        // One boxed object per particle per frame would be megabytes; allow a little for the measurement itself.
        return delta > 262_144L ? "allocated " + delta + " bytes over 3000 frames (expected ~0)" : "";
    }

    private static void frame(ParticleEmitter em, ParticlePool pool, ParticleQuadBuffer qb) {
        em.update(0.016f, pool);
        pool.update(0.016f);
        qb.begin(1, 0, 0, 0, 1, 0);
        qb.addPool(pool, 1f);
    }
}
