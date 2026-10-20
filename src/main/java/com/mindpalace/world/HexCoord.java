package com.mindpalace.world;

/**
 * HexCoord — axial hex coordinate (Q, R) with distance + neighbor math.
 * Ported from SIMS1337 (hex-fow gist) via agent/sims/HexCoord.java (QUAL-3,
 * t_ddf78c8d) into the world package: the hex water lattice (HexWaterGrid) and
 * the agent-side FOW/quorum hexes keep the same axial maths but stay two
 * independent classes — the agent copy evolves with the agent domain, the
 * world copy with render/world, and neither drags the other's package in.
 */
public class HexCoord {
    public final int q, r;

    public HexCoord(int q, int r) { this.q = q; this.r = r; }

    public static HexCoord fromString(String s) {
        String[] parts = s.split(",");
        return new HexCoord(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()));
    }

    /** Axial hex distance: max(|dq|, |dr|, |dq+dr|). */
    public int distanceTo(HexCoord other) {
        int dq = this.q - other.q;
        int dr = this.r - other.r;
        return Math.max(Math.max(Math.abs(dq), Math.abs(dr)), Math.abs(dq + dr));
    }

    /** All 6 neighbors in axial coordinates. */
    public HexCoord[] neighbors() {
        int[][] dirs = {{1,0},{1,-1},{0,-1},{-1,0},{-1,1},{0,1}};
        HexCoord[] n = new HexCoord[6];
        for (int i = 0; i < 6; i++) n[i] = new HexCoord(q + dirs[i][0], r + dirs[i][1]);
        return n;
    }

    public String key() { return q + "," + r; }

    @Override public boolean equals(Object o) {
        if (!(o instanceof HexCoord h)) return false;
        return q == h.q && r == h.r;
    }
    @Override public int hashCode() { return 31 * q + r; }
    @Override public String toString() { return "(" + q + "," + r + ")"; }
}