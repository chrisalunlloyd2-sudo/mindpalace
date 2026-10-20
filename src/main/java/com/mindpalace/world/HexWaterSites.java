package com.mindpalace.world;

import java.util.ArrayList;
import java.util.List;

/**
 * QUAL-4 (MP-025) pure GL-free helpers for the hex-water render path: site
 * constants, grid builders and the per-tile bob/shimmer functions — all
 * stateless, no allocation in steady state, no GL, so the integration
 * self-check can prove them headless (same pattern as HexWaterSelfCheck).
 */
public final class HexWaterSites {

    // ── Lake (OutsideWorld.renderLake) ──────────────────────────────────────

    /** Lake centre — must match OutsideWorld.lakeCenter (self-check pins both). */
    public static final float LAKE_CX = 40f, LAKE_CZ = -210f;
    /** Tile scale of the legacy lake loop: cap circumradius = 0.5·HR = 1.2. */
    public static final float LAKE_HR = 2.4f;
    /** Outer sand-ring ellipse (the legacy sand loop's boundary literals). */
    public static final float SAND_RX = 34.2f, SAND_RZ = 24.2f;
    /** Gentle per-tile cosine bob amplitude (flag ON only). */
    public static final float LAKE_BOB_AMP = 0.02f;

    // ── NashFountain pool ───────────────────────────────────────────────────

    /** Pool tile scale: fill disc + corners stay inside the 0.8 stone ring. */
    public static final float POOL_TILE_R = 0.2f;
    /** setRegionDisc fill radius: centres reach 0.42, rim prism corners ≤ 0.63 —
     *  safely inside the stone ring's inner face (~0.6, stones at r 0.8). */
    public static final float POOL_FILL_R = 0.22f;
    /** Prism x/z size for a tile of POOL_TILE_R with the proven 1.06 overlap. */
    public static final float POOL_TILE_WORLD = (float) (2.0 * POOL_TILE_R * 1.06);

    public static final float TWO_PI = (float) (Math.PI * 2.0);

    private HexWaterSites() { }

    /**
     * The full lake basin as ONE grid: every lattice tile whose centre lies in
     * the outer sand ellipse (plus the builder's tile-edge overscan). Draw
     * time splits it by the same ellipse tests the legacy loops use — water
     * cells inside the grown ellipse, sand ring cells outside it — so water
     * and sand share one coherent hex tessellation (no lattice phase seam at
     * the shoreline). Built once, zero-cost after.
     */
    public static HexWaterGrid buildLakeGrid() {
        HexWaterGrid g = new HexWaterGrid();   // R 1.2 → xPitch √3·1.2, zPitch 1.8:
        // exactly the legacy loop's xStep = √3·0.5·HR(2.4) and zStep = 0.75·HR.
        g.setRegionRect(LAKE_CX, LAKE_CZ, 2f * SAND_RX, 2f * SAND_RZ);
        List<HexWaterGrid.Tile> keep = new ArrayList<>();
        for (HexWaterGrid.Tile t : g.getTiles()) {
            float dx = t.cx - LAKE_CX, dz = t.cz - LAKE_CZ;
            if ((dx * dx) / (SAND_RX * SAND_RX) + (dz * dz) / (SAND_RZ * SAND_RZ) < 1f) {
                keep.add(t);
            }
        }
        g.installFiltered(keep);
        return g;
    }

    /**
     * The fountain pool as one small grid built AT THE LATTICE ORIGIN: the
     * canonical 7-tile flower (centre + six ring tiles, all ring centres
     * exactly √3·R from the origin, so the disc fill includes the complete
     * ring — a base-anchored fill could asymmetrize the rosette whenever the
     * fountain base sits off-lattice). Renderers translate by the fountain
     * base at draw time (NashFountain), keeping the rosette perfectly
     * symmetric under any base and the pool water centred on the ring.
     */
    public static HexWaterGrid buildPoolGrid() {
        HexWaterGrid g = new HexWaterGrid(POOL_TILE_R);
        g.setRegionDisc(0f, 0f, POOL_FILL_R);
        return g;
    }

    // ── Per-tile motion / shimmer (pure functions of the stable phase) ──────

    /**
     * The legacy traveling-wave family, verbatim from the lake loop (single
     * source: the legacy path calls this too, so flag OFF is bit-identical).
     */
    public static float lakeWave(float wx, float wz, float time) {
        return 0.055f * (float) Math.cos(wx * 0.22f + time * 1.6f)
                + 0.045f * (float) Math.cos(wz * 0.30f - time * 1.2f)
                + 0.030f * (float) Math.sin((wx + wz) * 0.22f + time * 0.8f);
    }

    /** Gentle per-tile cosine bob: |result| ≤ amp by construction. */
    public static float lakeBob(float phase, float time, float amp) {
        if (amp == 0f) return 0f;
        return amp * (float) Math.cos(TWO_PI * phase + time * 1.4f);
    }

    /**
     * Per-tile shimmer in [0.72, 1]. Multiplicative-only (never brightens
     * above the pre-shimmer colour), so every anti-white budget of the v4–v7
     * surface stays structurally valid.
     */
    public static float lakeShimmer(float phase, float time) {
        return 0.86f + 0.14f * (float) Math.sin(TWO_PI * phase + time * 1.5f);
    }

    /**
     * Pool shimmer: flowing [0.8, 1], frozen [0.9, 1] (the equilibrium pool
     * nearly stills, matching the frozen droplets).
     */
    public static float poolShimmer(float phase, double time, boolean frozen) {
        float s = 0.9f + 0.1f * (float) Math.sin(TWO_PI * phase + time * 2.2);
        return frozen ? 0.5f + 0.5f * s : s;
    }
}