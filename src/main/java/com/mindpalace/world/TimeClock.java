package com.mindpalace.world;

import java.time.LocalTime;

/**
 * STEP 94v7: single choke point for the in-game hour. Everything visible
 * (sky phase, lake tint, fountain fnight, sun/moon arc, stars, fireflies)
 * reads through {@link #hour()} so a CLI {@code --time N} override can pin
 * the whole WORLD to one hour for reproducible night/dusk QA captures.
 * Default (no override) is the real wall clock — behaviour unchanged.
 */
public final class TimeClock {
    private static Integer override = null;

    // STABILITY (2026-10-19 freeze): hour() is read many times PER FRAME from
    // the render loop (lake tint x4, world render, HUD). Each call hits
    // LocalTime.now() -> VM.getNanoTimeAdjustment — a native UTC-offset
    // reload that spin-stalls (100%+ CPU, render loop frozen, jstack pinned
    // here in both dumps) when the Windows time provider wedges. The hour
    // changes at most once per wall hour, so a cached read refreshed at most
    // once per second is observationally identical and keeps the
    // nano-time-adjustment native OUT of the per-frame render path entirely.
    private static volatile int cachedHour = -1;
    private static volatile long cachedAtMs = 0L;

    private TimeClock() {}

    /** Effective hour: CLI override when set, else the real clock hour. */
    public static int hour() {
        if (override != null) return override;
        // 1s cache: identical to a fresh read except within 1s of an hour
        // roll — invisible for sky/lake/HUD tinting.
        long nowMs = System.nanoTime() / 1000000L;
        if (cachedHour < 0 || nowMs - cachedAtMs >= 1000L) {
            cachedHour = LocalTime.now().getHour();
            cachedAtMs = nowMs;
        }
        return cachedHour;
    }

    /** Parse {@code --time N} (0..23). Invalid values are ignored. */
    public static void parseArg(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if ("--time".equals(args[i])) {
                try {
                    int v = Integer.parseInt(args[i + 1].trim());
                    if (v >= 0 && v <= 23) override = v;
                } catch (NumberFormatException ignored) {
                }
                return; // first occurrence wins
            }
        }
    }
}