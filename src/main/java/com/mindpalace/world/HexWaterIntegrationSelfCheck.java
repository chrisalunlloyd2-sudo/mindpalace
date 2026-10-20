package com.mindpalace.world;

/**
 * QUAL-4 (t_9bcb8660) headless (no-GL) integration checks for the hex-water
 * render path behind {@link HexWaterFlags#HEX_WATER}: the flag gate wiring,
 * the site constants/grids (Nash pool rosette, lake basin), order/round-trip
 * on the installed lake grid, bit-exact legacy wave equivalence, and the
 * bob/shimmer range contracts. Same discipline as HexWaterSelfCheck: returns
 * "" when everything holds, or the first failing description.
 */
public final class HexWaterIntegrationSelfCheck {

    private HexWaterIntegrationSelfCheck() { }

    public static String run() {
        // 1. Flag gate wiring — the static must literally mirror the system
        // property. In the default selftest JVM this is OFF (property absent);
        // in a -Dmindpalace.render.hexWater run this verifies the ON path too.
        if (HexWaterFlags.HEX_WATER
                != (System.getProperty("mindpalace.render.hexWater") != null)) {
            return "flag gate desync";
        }

        // 2. Site constants pinned to the legacy lake literals.
        if (HexWaterSites.LAKE_HR != 2.4f) return "lake hr drift";
        if (HexWaterSites.SAND_RX != 34.2f || HexWaterSites.SAND_RZ != 24.2f) {
            return "sand ellipse drift";
        }
        if (HexWaterSites.LAKE_CX != 40f || HexWaterSites.LAKE_CZ != -210f) {
            return "lake centre drift";
        }
        if (HexWaterSites.LAKE_BOB_AMP < 0f || HexWaterSites.LAKE_BOB_AMP > 0.05f) {
            return "bob amplitude not gentle";
        }

        // 3. Nash pool rosette: 7-tile flower, centre included, every hex
        // corner safely inside the stone ring's inner face (~0.6).
        HexWaterGrid pool = HexWaterSites.buildPoolGrid();
        if (pool.getTileCount() != 7) {
            return "pool rosette expected 7 tiles: " + pool.getTileCount();
        }
        float maxCentre = 0f;
        boolean centreTile = false;
        HexWaterGrid.Tile[] pt = pool.getTiles();
        for (int i = 0; i < pool.getTileCount(); i++) {
            HexWaterGrid.Tile t = pt[i];
            maxCentre = Math.max(maxCentre,
                (float) Math.sqrt(t.cx * t.cx + t.cz * t.cz));
            if (t.cx == 0f && t.cz == 0f) centreTile = true;
        }
        if (!centreTile) return "pool rosette missing centre tile";
        float cornerReach = maxCentre + 0.5f * HexWaterSites.POOL_TILE_WORLD;
        if (cornerReach >= 0.58f) {
            return "pool hex corners approach stone ring: " + cornerReach;
        }

        // 4. Lake basin: order, coverage, round-trip identity, water/sand split.
        HexWaterGrid lake = HexWaterSites.buildLakeGrid();
        int n = lake.getTileCount();
        if (n == 0) return "lake basin empty";
        final float xStep = (float) (Math.sqrt(3.0) * 0.5 * HexWaterSites.LAKE_HR);
        final float zStep = 0.75f * HexWaterSites.LAKE_HR;
        final float rxGrown = 30f + xStep, rzGrown = 20f + zStep;
        int waterCells = 0, sandCells = 0;
        float prevZ = -Float.MAX_VALUE, prevX = -Float.MAX_VALUE;
        HexWaterGrid.Tile[] lt = lake.getTiles();
        for (int i = 0; i < n; i++) {
            HexWaterGrid.Tile t = lt[i];
            // Row-ascending, then x-ascending order (install contract kept by
            // the ellipse filter) — the render loop relies on stable order.
            if (t.cz < prevZ - 1e-6f || (t.cz == prevZ && t.cx < prevX)) {
                return "lake basin order broken at i=" + i;
            }
            prevZ = t.cz; prevX = t.cx;
            float dx = t.cx - HexWaterSites.LAKE_CX;
            float dz = t.cz - HexWaterSites.LAKE_CZ;
            float sand = (dx * dx) / (HexWaterSites.SAND_RX * HexWaterSites.SAND_RX)
                       + (dz * dz) / (HexWaterSites.SAND_RZ * HexWaterSites.SAND_RZ);
            if (sand >= 1f) return "tile outside sand ellipse at i=" + i;
            float grown = (dx * dx) / (rxGrown * rxGrown)
                        + (dz * dz) / (rzGrown * rzGrown);
            if (grown < 1f) waterCells++; else sandCells++;
            // Exact-lattice round trip: querying the centre must return the
            // same stable id (the draw loop's wx/wz are these centres).
            if (lake.tileAt(t.cx, t.cz).id != t.id) {
                return "lake round-trip mismatch at i=" + i;
            }
        }
        if (waterCells == 0 || sandCells == 0) {
            return "lake split degenerate (water=" + waterCells
                 + ", sand=" + sandCells + ")";
        }

        // 5. Bit-exact legacy wave equivalence — HexWaterSites.lakeWave must
        // reproduce the verbatim legacy lake formula (single source for both
        // render paths; floatToIntBits catches any constant/op reordering).
        float[] sxs = {0f, 12.3f, -33.7f, 71.2f, -68.4f};
        float[] timeS = {0f, 3.1f, 17.45f, 61.7f, 1213.25f};
        for (int a = 0; a < sxs.length; a++) {
            for (int b = 0; b < timeS.length; b++) {
                float wx = sxs[a], wz = sxs[(a + 2) % sxs.length];
                float time = timeS[b];
                float legacy =
                      0.055f * (float) Math.cos(wx * 0.22f + time * 1.6f)
                    + 0.045f * (float) Math.cos(wz * 0.30f - time * 1.2f)
                    + 0.030f * (float) Math.sin((wx + wz) * 0.22f + time * 0.8f);
                float site = HexWaterSites.lakeWave(wx, wz, time);
                if (Float.floatToIntBits(legacy) != Float.floatToIntBits(site)) {
                    return "wave formula drift at " + a + "/" + b;
                }
            }
        }

        // 6. Motion bounds: |bob| ≤ amp; shimmer ranges (multiplicative ≤1).
        for (int i = 0; i < 64; i++) {
            float ph = i / 64f;
            float time = i * 0.37f;
            float bob = HexWaterSites.lakeBob(ph, time, HexWaterSites.LAKE_BOB_AMP);
            if (Math.abs(bob) > HexWaterSites.LAKE_BOB_AMP + 1e-6f) {
                return "bob out of range: " + bob;
            }
            float sh = HexWaterSites.lakeShimmer(ph, time);
            if (sh < 0.71f || sh > 1.0001f) return "lake shimmer out of range: " + sh;
            float pf = HexWaterSites.poolShimmer(ph, 11.3 + i * 0.13, false);
            if (pf < 0.79f || pf > 1.0001f) {
                return "pool shimmer (flowing) out of range: " + pf;
            }
            float pz = HexWaterSites.poolShimmer(ph, 11.3 + i * 0.13, true);
            if (pz < 0.89f || pz > 1.0001f) {
                return "pool shimmer (frozen) out of range: " + pz;
            }
        }
        return "";
    }
}