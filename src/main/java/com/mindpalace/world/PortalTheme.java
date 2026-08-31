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
 * ring + orbiting sparkles glow colorA (the accent) and its inner pad +
 * rising beam glow colorB (the complement), and the destination picker shows
 * the same pair as a two-block swatch next to each entry — so a player can
 * visually match a menu entry to the physical pad it routes to before
 * stepping through.
 *
 * Pure material/tint colors (Renderer.drawCubeColor, cached 1x1 textures):
 * no new geometry, no new particle systems, no extra draw calls. Every
 * color is chosen with luminance >= ~0.61 so the bloom bright-pass
 * (threshold 0.6, BloomEffect) treats each pad like the original neon
 * cyan/amber. Cheap on the OpenGL 3.3 / Intel HD 510 target (BLACKBOARD.md).
 */
public class PortalTheme {

    /** Theme name (shown in the picker + teleport logs, colorblind-safe). */
    public final String name;
    /** Outer ring / sparkles / orbit bars — the accent color. */
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
     * Pair 0 is the classic Cerulean/Amber from the roadmap; its values match
     * the original neon amber/cyan pad so floor 0 keeps its familiar look.
     */
    private static final PortalTheme[] PALETTE = {
        // Cerulean/Amber — the roadmap pair (amber ring, cerulean beam);
        // values match the original neon amber/cyan pad so floor 0 looks familiar
        new PortalTheme("Cerulean/Amber", 1.00f, 0.72f, 0.10f, 0.00f, 0.92f, 1.00f),
        // Lime/Violet — bright lime ring, periwinkle beam
        new PortalTheme("Lime/Violet",   0.75f, 1.00f, 0.15f, 0.85f, 0.62f, 1.00f),
        // Coral/Teal — warm coral ring, turquoise beam
        new PortalTheme("Coral/Teal",    1.00f, 0.60f, 0.42f, 0.12f, 0.95f, 0.85f),
        // Orchid/Mint — orchid ring, mint beam
        new PortalTheme("Orchid/Mint",    1.00f, 0.55f, 0.90f, 0.35f, 1.00f, 0.60f),
        // Tangerine/Azure — orange ring, azure beam
        new PortalTheme("Tangerine/Azure", 1.00f, 0.64f, 0.12f, 0.45f, 0.72f, 1.00f),
        // Rose/Spring — rose ring, spring-green beam
        new PortalTheme("Rose/Spring",   1.00f, 0.58f, 0.74f, 0.40f, 1.00f, 0.55f),
    };

    /**
     * The planet's return pad gets its own pair (not in the pad rotation) so
     * the "Planet" picker entry is matchable to the physical +Y-pole pad.
     */
    public static final PortalTheme PLANET =
        new PortalTheme("Copper/Cobalt", 1.00f, 0.62f, 0.34f, 0.50f, 0.68f, 1.00f);

    /** Theme for pad {@code padIndex} — stable, rotates through the palette. */
    public static PortalTheme forPad(int padIndex) {
        return PALETTE[Math.floorMod(padIndex, PALETTE.length)];
    }

    /** Hue of an RGB triple in degrees [0, 360). */
    private static float hue(float r, float g, float b) {
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