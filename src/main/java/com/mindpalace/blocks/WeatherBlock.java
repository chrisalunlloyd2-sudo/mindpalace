package com.mindpalace.blocks;

/** MP-028 weather, phase 1: atmospheric light response in the bloom chain
 * (rain dims the world glow, snow lifts it; clear is neutral). Pure sim —
 * deterministic exponential approach, no jitter, no world-package coupling:
 * the engine passes the weather NAME as env (blocks render pkg stays pure).
 *
 *   target(w):  CLEAR -> 0, RAIN -> -0.06, SNOW -> +0.05
 *   current(t): exp lerp toward target, tau = 1.5s  (|current| <= 0.06)
 *   luma out:   clamp(luma + current, 0, 2)
 *
 * Phase 2 (tracked under the weather issue): rain/snow particle quads
 * rendered as a block render() pass — this facet is the light response. */
public final class WeatherBlock implements Block {

    public static final float RAIN_DELTA = -0.06f;
    public static final float SNOW_DELTA = 0.05f;
    private static final float TAU = 1.5f; // seconds (response time constant)

    private float target = 0f, current = 0f;
    private boolean cleaned;
    private long ticks;

    @Override public String id() { return "mp-028-weather"; }

    @Override public void update(float dt, float px, float pz, float base) {
        // weather is global, not positional: fire owns the positional logic
    }

    @Override public void envUpdate(float dt, String weather, float aux) {
        if (cleaned) return;
        if (dt != dt || dt < 0f) return; // NaN/negative: ignore
        target = "RAIN".equals(weather) ? RAIN_DELTA
               : "SNOW".equals(weather) ? SNOW_DELTA : 0f;
        float k = 1f - (float) Math.exp(-dt / TAU);
        current += (target - current) * k;
        if (current > SNOW_DELTA) current = SNOW_DELTA;
        if (current < RAIN_DELTA) current = RAIN_DELTA;
        ticks++;
    }

    @Override public float applyLuma(float luma) {
        if (cleaned) return luma;
        float out = luma + current;
        if (out < 0f) out = 0f;
        if (out > 2f) out = 2f;
        return out;
    }

    @Override public void cleanup() { cleaned = true; current = 0f; }

    @Override public boolean selfTest() {
        try {
            // W.p1 CLEAR neutral: env stream of CLEAR keeps current ~0
            for (int i = 0; i < 100; i++) envUpdate(0.016f, "CLEAR", 0f);
            if (Math.abs(current) > 1e-3f) return false;
            if (applyLuma(0.6f) != 0.6f) return false; // exactly-identity on CLEAR

            // W.p2 RAIN converges toward RAIN_DELTA (400 ticks = 6.4s = 4.3 taus,
            // residual 0.06*e^-4.3 ~ 0.0009 -- inside the 0.005 band)
            for (int i = 0; i < 400; i++) envUpdate(0.016f, "RAIN", 0f);
            if (Math.abs(current - RAIN_DELTA) > 0.005f) return false;

            // W.p3 switch RAIN -> CLEAR reverses back toward neutrality
            for (int i = 0; i < 400; i++) envUpdate(0.016f, "CLEAR", 0f);
            if (Math.abs(current) > 0.005f) return false;

            // W.p4 SNOW converges up, and |current| never exceeds the band
            for (int i = 0; i < 400; i++) envUpdate(0.016f, "SNOW", 0f);
            if (Math.abs(current - SNOW_DELTA) > 0.005f) return false;

            // W.p5 unknown weather string = CLEAR behavior (never guesses):
            // target snaps to 0 and |current| decays downward from snow-high
            // (a decrease IS the correct CLEAR response -- gate asserts driftTowardZero)
            float before = current; // ~ +0.0495 after the SNOW stream
            envUpdate(0.016f, "HAIL", 0f);
            if (Math.abs(target) > 1e-9f) return false;
            if (current >= before) return false; // must decay toward 0
            if (current <= 0f) return false;     // one tick of tau=1.5s cannot flip sign

            // W.p6 NaN dt ignored (no drift burst)
            float c = current;
            envUpdate(Float.NaN, "RAIN", 0f);
            envUpdate(-1f, "RAIN", 0f);
            if (Float.floatToIntBits(c) != Float.floatToIntBits(current)) return false;

            // W.p7 cleanup idempotent + identity
            cleanup(); cleanup();
            if (applyLuma(0.75f) != 0.75f) return false;
            return true;
        } finally {
            cleaned = false; // revive after test (never leave a block dead)
            current = 0f; target = 0f;
        }
    }
}
