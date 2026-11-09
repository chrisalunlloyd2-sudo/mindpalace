package com.mindpalace.render;

/**
 * MP-036 (#182): particle-budget watchdog — lowers the effects tier when the
 * rolling frame time stays above the threshold, and logs every change.
 *
 * <p>Downgrade-only (never upgrades on its own — a stable box stays at the
 * tier the player/config chose) and rate-limited by a cooldown so one hitch or
 * a GC pause cannot thrash the tier. Decisions are pure (clock + frame times
 * in, decision out), so the selftest covers them with synthetic inputs; the
 * engine only supplies real frame times and applies the tier.</p>
 */
public final class PerfGuard {

    /** Sustained slower than ~30 fps is the default "too slow" line. */
    public static final double DEFAULT_THRESHOLD_MS = 33.0;
    /** Rolling window: ~2 s at 60 fps. */
    public static final int DEFAULT_SAMPLES = 120;
    /** At most one tier change per 15 s. */
    public static final long DEFAULT_COOLDOWN_MS = 15_000L;

    public enum Decision { NONE, DOWNGRADE }

    private final double thresholdMs;
    private final int samples;
    private final long cooldownMs;
    private final double[] window;      // primitive ring — no per-frame allocation
    private int head;                   // next write index
    private int count;                  // filled slots
    private long lastChangeMs = Long.MIN_VALUE / 2;

    public PerfGuard() {
        this(DEFAULT_THRESHOLD_MS, DEFAULT_SAMPLES, DEFAULT_COOLDOWN_MS);
    }

    public PerfGuard(double thresholdMs, int samples, long cooldownMs) {
        this.thresholdMs = thresholdMs;
        this.samples = Math.max(1, samples);
        this.cooldownMs = cooldownMs;
        this.window = new double[this.samples];
    }

    /** Record one frame; returns true when the window is full enough to judge. */
    public boolean sample(double frameMs) {
        window[head] = frameMs;
        head = (head + 1) % samples;
        if (count < samples) count++;
        return count >= samples;
    }

    public int sampleCount() { return count; }

    /** Mean of the rolling window in ms (0 when empty). */
    public double averageMs() {
        if (count == 0) return 0.0;
        double sum = 0.0;
        for (int i = 0; i < count; i++) sum += window[i];
        return sum / count;
    }

    /** Judge the current tier; DOWNGRADE also consumes the cooldown. */
    public Decision evaluate(ParticleQuality current, long nowMs) {
        Decision d = decide(current, averageMs(), nowMs - lastChangeMs, thresholdMs, cooldownMs,
            count >= samples);
        if (d == Decision.DOWNGRADE) lastChangeMs = nowMs;
        return d;
    }

    /** One tier down the ladder; OFF stays OFF. */
    public static ParticleQuality nextTier(ParticleQuality current) {
        switch (current) {
            case HIGH: return ParticleQuality.MEDIUM;
            case MEDIUM: return ParticleQuality.LOW;
            case LOW: return ParticleQuality.OFF;
            default: return ParticleQuality.OFF;
        }
    }

    /** Pure decision: downgrade only on a full window, above threshold, outside cooldown. */
    public static Decision decide(ParticleQuality current, double avgMs, long sinceLastChangeMs,
                                  double thresholdMs, long cooldownMs, boolean windowFull) {
        if (current == null || current == ParticleQuality.OFF) return Decision.NONE;
        if (!windowFull) return Decision.NONE;
        if (avgMs <= thresholdMs) return Decision.NONE;
        if (sinceLastChangeMs < cooldownMs) return Decision.NONE;
        return Decision.DOWNGRADE;
    }

    /** The log line the engine prints when the tier moves. */
    public static String describe(ParticleQuality from, ParticleQuality to, double avgMs, int samples) {
        return "[Perf] tier " + from + " -> " + to + " (avg "
            + String.format("%.1f", avgMs) + " ms over " + samples + " frames, threshold "
            + String.format("%.1f", DEFAULT_THRESHOLD_MS) + " ms)";
    }
}
