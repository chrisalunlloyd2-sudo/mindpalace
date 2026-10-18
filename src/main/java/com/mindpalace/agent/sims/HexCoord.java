package com.mindpalace.agent.sims;

/**
 * HexCoord — axial hex coordinate (Q, R) with distance + neighbor math.
 * Ported from SIMS1337 (hex-fow gist). Used by FOWGate + WeightedQuorumVote.
 */
public class HexCoord {
    public final int q, r;

    public HexCoord(int q, int r) { this.q = q; this.r = r; }

    public static HexCoord fromString(String s) {
        // HARDEN-x100: public API parse must reject garbage without an uncaught
        // NumberFormatException/IndexOutOfBounds (FOWGate.isVisible calls this).
        if (s != null) {
            String t = s.trim();
            int comma = t.indexOf(',');
            if (comma >= 0) {
                try {
                    return new HexCoord(
                        Integer.parseInt(t.substring(0, comma).trim()),
                        Integer.parseInt(t.substring(comma + 1).trim()));
                } catch (RuntimeException ignored) {
                    // fall through to the zero-hex fallback below
                }
            }
        }
        return new HexCoord(0, 0);
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
    @Override public String toString() { return "⬡(" + q + "," + r + ")"; }
}
