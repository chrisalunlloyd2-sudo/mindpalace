package com.mindpalace.world;

import org.joml.Vector3f;

/**
 * Paired complementary color themes for the teleporter pads — roadmap §3
 * "portal polish" slice (CROSS_CORRELATED_ROADMAP.md: paired complementary
 * color themes, Cerulean &lt;-&gt; Amber).
 *
 * Every pad gets a stable two-color identity from a small fixed palette,
 * assigned at world-build time by pad index (pads are in floor order, so
 * index i == floor i — see WorldBuilder.getTeleporterPads). The pad's outer
 * ring + orbit bars + sparkles glow colorA (the accent) and its inner pad +
 * rising beam glow colorB (the complement), and the destination picker
 * shows the same pair as a two-block swatch next to each entry — so a
 * player can visually match a menu entry to the physical pad it routes to
 * before stepping through.
 *
 * Calibration constraints (why these exact values):
 *  - Luminance (0.2126R + 0.7152G + 0.0722B) >= ~0.717 for EVERY color, so
 *    each behaves like the original neon cyan (0.716) / amber (0.720) pads
 *    under the bloom bright-pass (threshold 0.6, BloomEffect). This rules
 *    out deep magenta/pink/rose rings — they cannot reach the threshold.
 *  - Hue distance within a pair >= ~120 degrees (true complements).
 *  - Adjacent pad indices never share a hue family on either channel, so
 *    side-by-side menu entries are always distinguishable.
 *
 * Pure material/tint colors (Renderer.drawCubeColor, cached 1x1 textures):
 * no new geometry, no new particle systems, no extra draw calls. Cheap on
 * the OpenGL 3.3 / Intel HD 510 target (BLACKBOARD.md constraint).
 */
public class PortalTheme {

    /** Theme name (shown in the picker swatch column + teleport logs). */
    public final String name;
    /** Outer ring / orbit bars / sparkles — the accent color. */
    public final Vector3f colorA;
    /** Inner pad / rising beam — the complement of colorA. */
    public final Vector3f colorB;

    private PortalTheme(String name, float ar, float ag, float ab,
                        float br, float bg, float bb) {
        this.name = name;
        this.colorA = new Vector3f(ar, ag, ab);
        this.colorB = new Vector3f(br, bg, bb);
    }

    /**
     * The fixed palette of complementary pairs, rotated by pad index.
     * Pair 0 is the roadmap's Cerulean/Amber; its values match the original
     * neon amber/cyan pad so floor 0 keeps its familiar look.
     */
    private static final PortalTheme[] PALETTE = {
        // 0 — the roadmap pair: amber ring (41°), cerulean beam (185°)
        new PortalTheme("Amber/Cerulean", 1.00f, 0.72f, 0.10f, 0.00f, 0.92f, 1.00f),
        // 1 — lime ring (78°), iris-violet beam (281°)
        new PortalTheme("Lime/Iris",      0.75f, 1.00f, 0.15f, 0.90f, 0.68f, 1.00f),
        // 2 — teal ring (173°), salmon beam (8°)
        new PortalTheme("Teal/Salmon",     0.12f, 0.95f, 0.85f, 1.00f, 0.65f, 0.60f),
        // 3 — iris-violet ring (281°), emerald beam (135°)
        new PortalTheme("Iris/Emerald",    0.90f, 0.68f, 1.00f, 0.20f, 1.00f, 0.40f),
        // 4 — azure ring (200°), copper beam (23°)
        new PortalTheme("Azure/Copper",    0.40f, 0.80f, 1.00f, 1.00f, 0.66f, 0.45f),
        // 5 — emerald ring (135°), amethyst beam (281°)
        new PortalTheme("Emerald/Amethyst", 0.20f, 1.00f, 0.40f, 0.90f, 0.68f, 1.00f),
    };

    /**
     * The planet's return pad gets its own pair (not in the pad rotation) so
     * the "Planet" picker entry is matchable to the physical +Y-pole pad.
     */
    public static final PortalTheme PLANET =
        new PortalTheme("Copper/Mint", 1.00f, 0.66f, 0.45f, 0.35f, 1.00f, 0.60f);

    /** Theme for pad {@code padIndex} — stable, rotates through the palette. */
    public static PortalTheme forPad(int padIndex) {
        return PALETTE[Math.floorMod(padIndex, PALETTE.length)];
    }

    /** Number of distinct pairs in the fixed palette. */
    public static int paletteSize() { return PALETTE.length; }

    /**
     * Minimum hue distance (degrees, [0, 180]) between two colors — used to
     * prove adjacent pads never share a hue family on either channel.
     */
    public static float minHueDist(Vector3f a, Vector3f b) {
        float d = Math.abs(hue(a.x, a.y, a.z) - hue(b.x, b.y, b.z));
        return Math.min(d, 360f - d);
    }

    /** Reciprocal luminance used by the bloom bright-pass (BloomEffect). */
    public static float luminance(float r, float g, float b) {
        return 0.2126f * r + 0.7152f * g + 0.0722f * b;
    }

    /** Hue of an RGB triple in degrees [0, 360). */
    public static float hue(float r, float g, float b) {
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float d = max - min;
        if (d < 1e-6f) return 0f;
        float h;
        if (max == r)      h = ((g - b) / d) % 6f;
        else if (max == g) h = (b - r) / d + 2f;
        else               h = (r - g) / d + 4f;
        h *= 60f;
        return h < 0f ? h + 360f : h;
    }

    /**
     * True when the pair is complementary: the two hues sit at least ~110
     * degrees apart on the color wheel (the honest metric for "opposite"
     * colors at full brightness).
     */
    public boolean isComplementary() {
        float d = Math.abs(hue(colorA.x, colorA.y, colorA.z)
                         - hue(colorB.x, colorB.y, colorB.z));
        d = Math.min(d, 360f - d);
        return d >= 110f;
    }

    @Override
    public String toString() { return name; }
}