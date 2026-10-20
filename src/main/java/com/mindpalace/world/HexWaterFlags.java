package com.mindpalace.world;

/**
 * Render-quality flags (MP-010: visuals ship behind flags, OFF by default).
 * The hex-water flag stays OFF until the Architect approves screenshots; when
 * OFF every water site renders byte-identical to the legacy path.
 */
public final class HexWaterFlags {

    /**
     * QUAL-4 (MP-025): hex-water integration. ON when
     * {@code -Dmindpalace.render.hexWater} is present on the java launch line
     * (the h37 precedent: presence-based system property, toggled via -D, the
     * same mechanism e2e.sh already uses for -Dprism.* switches).
     */
    public static final boolean HEX_WATER =
        System.getProperty("mindpalace.render.hexWater") != null;

    private HexWaterFlags() { }
}