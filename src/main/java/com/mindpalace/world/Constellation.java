package com.mindpalace.world;

import com.mindpalace.render.Renderer;
import org.joml.Vector3f;

/**
 * Constellation — real, recognizable star patterns rendered in the night sky.
 *
 * Each constellation is a data-driven pattern: named stars (bright cubes) plus
 * connecting lines (thin cubes via Renderer.drawLine). Patterns are stored in
 * normalized 2D space (x right, y up, roughly -1..1) and projected onto the
 * sky plane at render time, so the whole field scales to any sky size for free.
 *
 * The user asked for "full start constellations" — replacing the old 40
 * random twinkling dots with actual named shapes (Big Dipper, Orion, etc.)
 * plus a twinkling background field.
 */
public final class Constellation {

    /** A named star at a normalized sky position. */
    private static final class Star {
        final float x, y;       // normalized (-1..1)
        final float brightness; // 0..1, scales the twinkle + size
        Star(float x, float y, float b) { this.x = x; this.y = y; this.brightness = b; }
    }

    /** A pattern: name + stars + connecting line indices (pairs). */
    private static final class Pattern {
        final String name;
        final float scale;              // relative sky footprint
        final Star[] stars;
        final int[][] lines;            // {starA, starB} pairs
        Pattern(String n, float s, Star[] st, int[][] ln) { name = n; scale = s; stars = st; lines = ln; }
    }

    /** The 8 real constellations (recognizable stick-figure shapes). */
    private static final Pattern[] PATTERNS = {
        // Big Dipper (Ursa Major) — bowl + handle
        new Pattern("Big Dipper", 0.55f, new Star[]{
            new Star(-0.85f, 0.35f, 0.9f), new Star(-0.55f, 0.30f, 0.9f),  // bowl top
            new Star(-0.80f, 0.00f, 0.85f), new Star(-0.50f,-0.05f, 0.85f),  // bowl bottom
            new Star(-0.05f, 0.40f, 0.8f), new Star(0.20f, 0.30f, 0.8f),     // handle
            new Star(0.50f, 0.15f, 0.7f),
        }, new int[][]{{0,1},{1,3},{3,2},{2,0},{1,4},{4,5},{5,6}}),

        // Orion — shoulders, belt, feet
        new Pattern("Orion", 0.60f, new Star[]{
            new Star(-0.35f, 0.85f, 1.0f), new Star(0.35f, 0.85f, 1.0f),     // shoulders
            new Star(-0.15f, 0.55f, 0.7f), new Star(0.05f, 0.50f, 0.7f),      // belt L
            new Star(0.25f, 0.45f, 0.7f),                                     // belt R
            new Star(-0.45f,-0.75f, 0.9f), new Star(0.45f,-0.75f, 0.9f),      // feet
        }, new int[][]{{0,1},{0,3},{1,4},{2,3},{3,4},{2,5},{4,6}}),

        // Cassiopeia — the W
        new Pattern("Cassiopeia", 0.45f, new Star[]{
            new Star(-0.90f, 0.10f, 0.85f), new Star(-0.45f,-0.30f, 0.85f),
            new Star(0.00f, 0.25f, 0.85f), new Star(0.45f,-0.35f, 0.85f),
            new Star(0.90f, 0.05f, 0.85f),
        }, new int[][]{{0,1},{1,2},{2,3},{3,4}}),

        // Cygnus — Northern Cross
        new Pattern("Cygnus", 0.50f, new Star[]{
            new Star(0.00f, 0.90f, 1.0f),                                     // Deneb (top)
            new Star(0.00f, 0.30f, 0.7f),                                     // center
            new Star(-0.55f,-0.15f, 0.8f), new Star(0.55f,-0.15f, 0.8f),       // wings
            new Star(0.00f,-0.80f, 0.9f),                                     // Albireo (foot)
        }, new int[][]{{0,1},{1,4},{1,2},{1,3}}),

        // Leo — the sickle + body
        new Pattern("Leo", 0.55f, new Star[]{
            new Star(0.10f, 0.85f, 0.9f),                                     // Regulus
            new Star(-0.20f, 0.55f, 0.8f), new Star(-0.35f, 0.20f, 0.8f),
            new Star(-0.20f,-0.10f, 0.8f), new Star(0.10f,-0.20f, 0.8f),
            new Star(0.60f,-0.05f, 0.8f),                                     // tail (Denebola)
        }, new int[][]{{0,1},{1,2},{2,3},{3,4},{4,5}}),

        // Scorpius — long curved chain
        new Pattern("Scorpius", 0.60f, new Star[]{
            new Star(-0.10f, 0.85f, 0.9f), new Star(-0.25f, 0.55f, 0.85f),
            new Star(-0.40f, 0.25f, 0.8f), new Star(-0.55f,-0.10f, 0.8f),
            new Star(-0.45f,-0.50f, 0.75f), new Star(-0.15f,-0.70f, 0.7f),
            new Star(0.25f,-0.80f, 0.7f),
        }, new int[][]{{0,1},{1,2},{2,3},{3,4},{4,5},{5,6}}),

        // Taurus — V face + two horns
        new Pattern("Taurus", 0.45f, new Star[]{
            new Star(0.40f, 0.20f, 0.9f), new Star(0.55f,-0.05f, 0.8f),        // horns tips
            new Star(0.10f, 0.05f, 0.85f), new Star(-0.05f,-0.10f, 0.85f),      // face V
            new Star(-0.30f, 0.15f, 0.7f),                                     // Aldebaran
        }, new int[][]{{0,2},{2,1},{3,4}}),

        // Lyra — small parallelogram + Vega
        new Pattern("Lyra", 0.35f, new Star[]{
            new Star(0.10f, 0.85f, 1.0f),                                     // Vega
            new Star(-0.15f, 0.35f, 0.7f), new Star(0.35f, 0.35f, 0.7f),
            new Star(0.20f, 0.05f, 0.7f), new Star(-0.30f, 0.05f, 0.7f),
        }, new int[][]{{0,1},{0,2},{1,3},{3,4},{2,4}}),
    };

    /** Precomputed sky-plane anchors so the field is stable across frames. */
    private static final float[][] ANCHORS = {
        {-28f, 12f}, {8f, 20f}, {34f, 6f}, {-14f, 34f},
        {24f, -10f}, {-34f, -6f}, {0f, -20f}, {40f, -18f},
    };

    private Constellation() {}

    /**
     * Render all constellations + a twinkling background field against the
     * night sky, billboarded in front of the camera.
     */
    public static void render(Renderer r, float camX, float camY, float camZ, float time) {
        float backZ = camZ - 40f;
        // Background star field (twinkle).
        for (int si = 0; si < 120; si++) {
            float ang = si * 2.399963f;
            float rad = 8f + (si % 7) * 6f;
            float sx = camX + (float) Math.cos(ang) * rad;
            float sy = camY + 10f + (float) Math.sin(ang * 1.7f) * 14f;
            float tw = 0.35f + 0.35f * (float) Math.cos(time * 1.3f + si * 1.7f);
            r.drawCubeColor(new Vector3f(sx, sy, backZ + 0.02f),
                new Vector3f(0.08f, 0.08f, 0.04f), tw, tw, tw * 0.95f);
        }
        // Named constellations.
        for (int p = 0; p < PATTERNS.length; p++) {
            Pattern pat = PATTERNS[p];
            float[] a = ANCHORS[p % ANCHORS.length];
            float cx = camX + a[0];
            float cy = camY + a[1];
            float half = 14f * pat.scale;
            // Stars first (bright cubes, sized by brightness + twinkle).
            Vector3f[] pts = new Vector3f[pat.stars.length];
            for (int i = 0; i < pat.stars.length; i++) {
                Star s = pat.stars[i];
                pts[i] = new Vector3f(cx + s.x * half, cy + s.y * half, backZ + 0.03f);
                float tw = 0.7f + 0.3f * (float) Math.sin(time * 1.1f + p * 2f + i);
                float sz = 0.10f + s.brightness * 0.10f;
                r.drawCubeColor(pts[i], new Vector3f(sz, sz, 0.04f),
                    0.85f * tw, 0.88f * tw, 1.0f * tw);
            }
            // Connecting lines (dim blue-white).
            for (int[] ln : pat.lines) {
                r.drawLine(pts[ln[0]], pts[ln[1]], 0.025f, 0.45f, 0.55f, 0.75f);
            }
        }
    }
}
