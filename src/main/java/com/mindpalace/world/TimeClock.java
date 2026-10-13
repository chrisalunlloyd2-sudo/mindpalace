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

    private TimeClock() {}

    /** Effective hour: CLI override when set, else the real clock hour. */
    public static int hour() {
        return override != null ? override : LocalTime.now().getHour();
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