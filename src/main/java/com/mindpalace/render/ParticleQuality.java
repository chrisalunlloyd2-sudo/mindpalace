package com.mindpalace.render;

/**
 * MP-028 particle quality tiers (issue #174): the cap is the whole budget, so quality is one number.
 * Select with -Dmindpalace.particles=off|low|med|high or env MP_PARTICLES. Default OFF: the engine keeps
 * its legacy per-row cube drawing until the Architect approves the soft-quad look and flips the default.
 */
public enum ParticleQuality {
    OFF(0), LOW(300), MEDIUM(800), HIGH(2000);

    public final int cap;

    ParticleQuality(int cap) {
        this.cap = cap;
    }

    public static ParticleQuality parse(String s, ParticleQuality fallback) {
        if (s == null) return fallback;
        switch (s.trim().toLowerCase()) {
            case "off": case "0": case "none": return OFF;
            case "low": case "l": return LOW;
            case "med": case "medium": case "m": return MEDIUM;
            case "high": case "h": return HIGH;
            default: return fallback;
        }
    }

    public static ParticleQuality fromConfig() {
        String v = System.getProperty("mindpalace.particles", System.getenv("MP_PARTICLES"));
        return parse(v, OFF);
    }
}
