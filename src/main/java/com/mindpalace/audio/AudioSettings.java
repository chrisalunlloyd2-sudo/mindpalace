package com.mindpalace.audio;

/**
 * #32 (low): best-effort persistence for the Options panel volume knobs,
 * mirroring the PortalTheme (#139) pattern: a properties file under
 * {user.home}/.mindpalace loaded on boot and load-merge-stored on knob
 * changes. Missing/corrupt file => defaults (never blocks boot or the
 * audio thread — every failure path returns the default and moves on).
 */
public final class AudioSettings {
    private AudioSettings() {}

    static java.nio.file.Path file() {
        return java.nio.file.Path.of(
            System.getProperty("user.home"), ".mindpalace", "settings.properties");
    }

    /** Load {@code key} as a float, or {@code dflt} when missing/corrupt. */
    public static float load(String key, float dflt) {
        try {
            java.util.Properties p = new java.util.Properties();
            if (java.nio.file.Files.exists(file())) {
                try (var in = java.nio.file.Files.newInputStream(file())) { p.load(in); }
                return Float.parseFloat(p.getProperty(key, Float.toString(dflt)));
            }
        } catch (Exception ignored) { /* default — never blocks boot */ }
        return dflt;
    }

    /** Store {@code key} = value (load-merge-store so keys don't clobber each other). */
    public static void save(String key, float value) {
        try {
            java.util.Properties p = new java.util.Properties();
            if (java.nio.file.Files.exists(file())) {
                try (var in = java.nio.file.Files.newInputStream(file())) { p.load(in); }
            }
            p.setProperty(key, Float.toString(value));
            java.nio.file.Files.createDirectories(file().getParent());
            try (var out = java.nio.file.Files.newOutputStream(file())) {
                p.store(out, "MindPalace options knobs (#32)");
            }
        } catch (Exception ignored) { /* best-effort */ }
    }
}