package com.mindpalace.genetics;

/**
 * DeterministicSeed — time-based, reproducible seeding (the rotor idea).
 *
 * The game's genetic systems must be reproducible: the same clock + the same
 * purpose must always yield the same seed, so a run can be replayed exactly.
 * This mirrors turing_foundry's RotorEnigma — no entropy, just a pure function
 * of time — but for the JVM's {@link java.util.Random}.
 *
 * The seed is derived from the epoch-second bucketed to a fixed window, mixed
 * with a per-purpose salt via a splitmix64 step. Same second + same purpose →
 * same seed; different purpose → different seed. Deterministic, reversible,
 * zero entropy.
 */
public final class DeterministicSeed {

    private DeterministicSeed() {}

    /** Default window: one seed per 30s (matches the GA evolve tick). */
    public static final long WINDOW_MS = 30_000L;

    /**
     * Derive a deterministic seed for a purpose at the current time.
     * Same (purpose, time-window) → same seed.
     */
    public static long seed(String purpose) {
        return seed(purpose, System.currentTimeMillis(), WINDOW_MS);
    }

    /** Derive a seed for a purpose at an explicit time + window. */
    public static long seed(String purpose, long epochMillis, long windowMs) {
        long bucket = Math.floorDiv(epochMillis, windowMs);
        long salt = splitmix64(purpose.hashCode());
        return splitmix64(bucket ^ salt);
    }

    /** A seeded Random for a purpose (deterministic, reproducible). */
    public static java.util.Random random(String purpose) {
        return new java.util.Random(seed(purpose));
    }

    /** splitmix64 — a fast, high-quality integer mixer (no deps). */
    private static long splitmix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
