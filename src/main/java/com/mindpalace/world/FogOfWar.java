package com.mindpalace.world;

import org.joml.Vector3f;
import java.util.*;

/**
 * Hex-grid fog of war. The palace is overlaid with a hex grid; each hex is
 * either hidden (fogged) or revealed. Walking near a hex reveals it and any
 * fogged rooms inside it. Private/remote repos stay hidden until their hex
 * is explored — the "GitHub fog of war".
 *
 * Hex layout: pointy-top, axial coordinates (q, r). World X/Z maps to hex
 * via standard axial math. Reveal radius in hexes around the player.
 */
public class FogOfWar {
    private final float hexSize;          // world units per hex (circumradius)
    private final float revealRadius;     // world units reveal distance
    private final Set<Long> revealed = new HashSet<>();

    public FogOfWar(float hexSize, float revealRadius) {
        this.hexSize = hexSize;
        this.revealRadius = revealRadius;
    }

    /** Axial (q,r) for a world position. */
    public int[] worldToHex(float x, float z) {
        float q = (float) ((Math.sqrt(3.0) / 3.0 * x - 1.0 / 3.0 * z) / hexSize);
        float r = (float) ((2.0 / 3.0 * z) / hexSize);
        return hexRound(q, r);
    }

    /** World center of a hex (q,r). */
    public Vector3f hexToWorld(int q, int r, float y) {
        float x = (float) (hexSize * (Math.sqrt(3.0) * q + Math.sqrt(3.0) / 2.0 * r));
        float z = (float) (hexSize * (3.0 / 2.0 * r));
        return new Vector3f(x, y, z);
    }

    /** Reveal all hexes within revealRadius of a world position. */
    public void reveal(Vector3f pos) {
        int[] center = worldToHex(pos.x, pos.z);
        int radius = Math.max(1, (int) Math.ceil(revealRadius / hexSize));
        for (int dq = -radius; dq <= radius; dq++) {
            for (int dr = Math.max(-radius, -dq - radius); dr <= Math.min(radius, -dq + radius); dr++) {
                revealed.add(key(center[0] + dq, center[1] + dr));
            }
        }
    }

    /** Is a world position currently revealed? */
    public boolean isRevealed(float x, float z) {
        int[] h = worldToHex(x, z);
        return revealed.contains(key(h[0], h[1]));
    }

    /** Is a room revealed? Uses its door position. */
    public boolean isRoomRevealed(Room room) {
        Vector3f dp = room.getDoorPosition();
        if (dp == null) return true;
        return isRevealed(dp.x, dp.z);
    }

    public int revealedCount() { return revealed.size(); }

    private long key(int q, int r) {
        return ((long) q << 32) ^ (r & 0xffffffffL);
    }

    // Axial hex rounding (cube coordinate round)
    private int[] hexRound(float q, float r) {
        float s = -q - r;
        int rq = Math.round(q), rr = Math.round(r), rs = Math.round(s);
        float dq = Math.abs(rq - q), dr = Math.abs(rr - r), ds = Math.abs(rs - s);
        if (dq > dr && dq > ds) rq = -rr - rs;
        else if (dr > ds) rr = -rq - rs;
        return new int[]{rq, rr};
    }
}
