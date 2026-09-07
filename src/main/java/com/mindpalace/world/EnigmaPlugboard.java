package com.mindpalace.world;

import com.mindpalace.render.Renderer;
import org.joml.Vector3f;

/**
 * EnigmaPlugboard — the foyer puzzle board: 10 physical plugs (A–J style slots)
 * paired by wiring. Walking near it and pressing E swaps the NEXT two plugs —
 * the swap RE-SEEDS the world's deterministic generator (a new plugboard wiring
 = a new chromosome stream, exactly like rewiring a real Enigma).
 *
 * The board state is a permutation of 10 letters held as pairs; the seed is
 * the permutation hashed with splitmix64. Same wiring -> same seed -> same
 * world. Determinism stays sacred; you just choose the wiring.
 *
 * Performance: 10 small cubes + 5 wire lines + label text handled by the
 * caller. LOD-culled at 30m. Interaction is a single E-key check when near.
 */
public final class EnigmaPlugboard {

    public static final int PLUGS = 10;
    private static final float LOD_DIST = 30f;
    private static final float INTERACT_DIST = 2.5f;

    /** Slot letters (Enigma used A-Z; 10 keeps the board readable). */
    private static final char[] LETTERS = "ABCDEFGHIJ".toCharArray();

    private final Vector3f pos;              // board center
    private final int[] wiring;              // permutation: wiring[i] = partner slot
    private int swapCursor = 0;              // next swap pair: (0,1), (2,3)...
    private long seed;
    private long swaps = 0;

    public EnigmaPlugboard(Vector3f pos, long initialSeed) {
        this.pos = new Vector3f(pos);
        this.wiring = new int[PLUGS];
        for (int i = 0; i < PLUGS; i++) wiring[i] = i;   // identity: unplugged
        this.seed = initialSeed;
    }

    /** Swap the next plug pair; re-derive the seed. Returns the new seed. */
    public long swapNext() {
        int a = swapCursor % PLUGS;
        int b = (swapCursor + 1) % PLUGS;
        int t = wiring[a]; wiring[a] = wiring[b]; wiring[b] = t;
        swapCursor = (swapCursor + 2) % PLUGS;
        swaps++;
        seed = splitmix64(hashWiring());
        return seed;
    }

    /** The current wiring as a string, e.g. "BA CD FE ..." (pairs swapped). */
    public String wiringLabel() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < PLUGS; i += 2) {
            sb.append(LETTERS[wiring[i]]).append(LETTERS[wiring[i + 1]]);
            if (i + 2 < PLUGS) sb.append(' ');
        }
        return sb.toString();
    }

    public long seed() { return seed; }
    public long swaps() { return swaps; }
    public int[] wiring() { return wiring.clone(); }

    private long hashWiring() {
        long h = 0x5EEDB0A2DL;
        for (int i = 0; i < PLUGS; i++) h = splitmix64(h ^ wiring[i]);
        return h;
    }

    private static long splitmix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** True when the player is close enough to interact. */
    public boolean isInteractable(Vector3f playerPos) {
        return playerPos.distance(pos) < INTERACT_DIST;
    }

    /** Render the board: 10 plugs in a row, wired pairs share a color. */
    public void render(Renderer r, Vector3f camPos) {
        if (camPos.distance(pos) > LOD_DIST) return;

        for (int i = 0; i < PLUGS; i++) {
            float x = pos.x + (i - (PLUGS - 1) / 2f) * 0.28f;
            boolean paired = wiring[i] != i;
            // Paired plugs glow BRIGHT gold; unplugged sit dim bronze — both
            // readable on the dark wall.
            float glow = paired ? 1.0f : 0.55f + 0.1f * (float) Math.sin(System.currentTimeMillis() / 300.0 + i);
            float cr = 0.85f * glow, cg = 0.65f * glow, cb = 0.2f * glow;
            r.drawCubeColor(new Vector3f(x, pos.y, pos.z),
                new Vector3f(0.16f, 0.16f, 0.1f), cr, cg, cb);
        }
        // Board backplate
        r.drawCubeColor(new Vector3f(pos.x, pos.y - 0.02f, pos.z - 0.06f),
            new Vector3f(PLUGS * 0.28f + 0.2f, 0.3f, 0.06f), 0.12f, 0.1f, 0.14f);
    }

    public Vector3f position() { return new Vector3f(pos); }
}