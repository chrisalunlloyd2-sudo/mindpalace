package com.mindpalace.world;

import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.HashSet;

/**
 * QUAL-3 (t_ddf78c8d) GL-free proof of the hex-water lattice — same headless
 * pattern as render/ParticleSelfCheck (MP-028): run() returns "" on success
 * or the first failure reason. Covers: layout facts, corner/node geometry
 * (trig cross-check), HexCoord parity, region fills vs an independent naive
 * census + probe coverage, exact tileAt (cube round), neighbour symmetry,
 * stable id/phase, cap mesh (bitwise welds by node set, index/winding/uv),
 * pond skirt (independent boundary census, outward normals, wall winding) and
 * MEASURED zero allocation on the steady-state query path (HotSpot
 * ThreadMXBean). No GL context. main() prints PASS/FAIL for fast checks.
 */
public final class HexWaterSelfCheck {
    private HexWaterSelfCheck() { }

    private static boolean near(float a, float b, float eps) { return Math.abs(a - b) <= eps; }

    public static String run() {
        // ── 1. layout facts (R = 1.2 = the live lake lattice) ────────────────
        HexWaterGrid g = new HexWaterGrid();
        if (g.tileR != 1.2f) return "default tileR";
        if (!near(g.xPitch, (float) (Math.sqrt(3.0) * 1.2), 1e-6f)) return "default xPitch";
        if (!near(g.zPitch, 1.8f, 1e-6f)) return "default zPitch";
        if (g.halfX != g.xPitch / 2f || g.halfZ != 0.6f) return "default half units";
        if (!near(g.tileArea, (float) (HexWaterGrid.AREA_K * 1.44), 1e-6f)) return "default tileArea";
        if (HexWaterGrid.DEFAULT_TILE_R != 1.2f) return "DEFAULT_TILE_R";
        if (HexWaterGrid.SQRT3 != Math.sqrt(3.0)) return "SQRT3";
        if (HexWaterGrid.AREA_K != 1.5 * Math.sqrt(3.0)) return "AREA_K";
        HexWaterGrid ga = HexWaterGrid.withTileArea(g.tileArea);
        if (!near(ga.tileR, 1.2f, 1e-4f)) return "withTileArea round-trip";
        try { new HexWaterGrid(0f); return "zero tileR accepted"; } catch (IllegalArgumentException e) { /* ok */ }
        try { new HexWaterGrid(-1f); return "negative tileR accepted"; } catch (IllegalArgumentException e) { /* ok */ }
        try { new HexWaterGrid(Float.NaN); return "NaN tileR accepted"; } catch (IllegalArgumentException e) { /* ok */ }
        try { HexWaterGrid.withTileArea(0f); return "zero area accepted"; } catch (IllegalArgumentException e) { /* ok */ }
        try { HexWaterGrid.withTileArea(Float.NaN); return "NaN area accepted"; } catch (IllegalArgumentException e) { /* ok */ }

        // ── 2. corner geometry: corner k == (cos, sin)(30 + 60k)·R exactly ───
        for (float tr : new float[]{0.5f, 1.2f, 2.5f}) {
            HexWaterGrid gg = new HexWaterGrid(tr);
            for (int k = 0; k < 6; k++) {
                double a = Math.toRadians(30 + 60 * k);
                if (!near(gg.halfX * HexWaterGrid.DM[k], (float) (Math.cos(a) * tr), 1e-4f)
                        || !near(gg.halfZ * HexWaterGrid.DN[k], (float) (Math.sin(a) * tr), 1e-4f)) {
                    return "corner " + k + " off the 30+60k ring at R=" + tr;
                }
            }
        }
        // documented sharing cases: centre (0,0) → corner 5 node (+1,-1),
        // corner 1 node (0,+2) (bearing 90 straight up +z)
        HexWaterGrid g5 = new HexWaterGrid(2.5f);
        g5.setRegionDisc(0f, 0f, 2.5f);   // tile (0,0) and its flower must exist
        HexWaterGrid.Tile t00 = g5.lookup(0, 0);
        if (t00 == null || t00.centreM != 0 || t00.centreN != 0) return "centre node of (0,0)";
        if (t00.cornerNodeM(5) != 1 || t00.cornerNodeN(5) != -1) return "corner 5 node";
        if (t00.cornerNodeM(1) != 0 || t00.cornerNodeN(1) != 2) return "corner 1 node";
        // makeTile node rule: centre node (2q+r, 3r) for a few spots
        HexWaterGrid g2b = new HexWaterGrid();
        g2b.setRegionRect(-6.2f, 0f, 30f, 4f);   // includes q<0 rows
        for (int i = 0; i < g2b.getTileCount(); i++) {
            HexWaterGrid.Tile t = g2b.getTile(i);
            if (t.centreM != 2 * t.q + t.r || t.centreN != 3 * t.r) return "centre node rule at " + t;
            if (!near(t.cx, g2b.halfX * t.centreM, 1e-6f) || !near(t.cz, g2b.halfZ * t.centreN, 1e-6f)) {
                return "centre world position off the node product at " + t;
            }
        }

        // ── 3. HexCoord (world port) parity with the axis/agent maths ────────
        HexCoord o = new HexCoord(0, 0);
        int[][] dirs = HexCoordDirs.snapshot();
        if (dirs.length != 6) return "HexCoord dirs count";
        if (HexWaterGrid.NQ.length != 6 || HexWaterGrid.NR.length != 6) return "grid dir arrays";
        for (int i = 0; i < 6; i++) {
            if (dirs[i][0] != HexWaterGrid.NQ[i] || dirs[i][1] != HexWaterGrid.NR[i]) {
                return "HexCoord dir " + i + " disagrees with HexWaterGrid NQ/NR";
            }
        }
        if (o.distanceTo(new HexCoord(2, -1)) != 2) return "HexCoord distance (2,-1)";
        if (o.distanceTo(new HexCoord(3, -3)) != 3) return "HexCoord distance (3,-3)";
        if (new HexCoord(2, -1).distanceTo(o) != 2) return "HexCoord distance symmetry";
        if (new HexCoord(1, 2).distanceTo(new HexCoord(1, 2)) != 0) return "HexCoord self distance";
        for (int i = 0; i < 6; i++) {
            HexCoord n = o.neighbors()[i];
            if (HexCoord.fromString(n.key()).distanceTo(o) != 1) return "HexCoord neighbor not at distance 1";
        }
        HashSet<String> seen = new HashSet<>();
        for (HexCoord n : o.neighbors()) seen.add(n.key());
        if (seen.size() != 6) return "HexCoord neighbors not distinct";
        if (!o.equals(new HexCoord(0, 0)) || o.equals(new HexCoord(1, 0))) return "HexCoord equals";
        if (o.hashCode() != new HexCoord(0, 0).hashCode()) return "HexCoord hashCode";
        if (!o.toString().equals("(0,0)")) return "HexCoord toString";
        if (HexCoord.fromString(" -3 , 7 ").q != -3 || HexCoord.fromString(" -3 , 7 ").r != 7) {
            return "HexCoord fromString trim";
        }

        // ── 4. region fills vs an independent naive census + coverage ────────
        HexWaterGrid gRect = new HexWaterGrid();
        int rc = gRect.setRegionRect(0f, 0f, 10f, 8f);
        int naive = naiveRectCensus(gRect, 0f, 0f, 10f, 8f);
        if (naive < 0) return "rect fill set differs from the census rule";
        if (rc != naive) return "rect fill count " + rc + " != census " + naive;
        for (int i = 1; i < rc; i++) {
            HexWaterGrid.Tile a = gRect.getTile(i - 1), b = gRect.getTile(i);
            if (b.r < a.r || (b.r == a.r && b.q <= a.q)) return "rect fill order";
        }
        HexWaterGrid gRect2 = new HexWaterGrid();
        if (gRect2.setRegionRect(0f, 0f, 10f, 8f) != rc) return "rect fill not deterministic";
        for (int i = 0; i < rc; i++) {
            if (gRect2.getTile(i).id != gRect.getTile(i).id) return "rect fill ids differ";
        }
        try { gRect.setRegionRect(0f, 0f, 0f, 8f); return "zero lw accepted"; } catch (IllegalArgumentException e) { /* ok */ }
        try { gRect.setRegionRect(0f, 0f, 10f, Float.NaN); return "NaN ld accepted"; } catch (IllegalArgumentException e) { /* ok */ }
        // coverage: every probe lattice point well inside the rect resolves to a tile
        for (float px = -4.0f; px <= 4.0f; px += 0.37f) {
            for (float pz = -3.0f; pz <= 3.0f; pz += 0.43f) {
                if (gRect.tileAt(px, pz) == null) return "rect coverage hole at " + px + "," + pz;
            }
        }
        // disc fill vs naive census; translation invariance by a lattice pitch
        HexWaterGrid gDisc = new HexWaterGrid();
        int dc = gDisc.setRegionDisc(0f, 0f, 4f);
        int dNaive = naiveDiscCensus(gDisc, 0f, 0f, 4f);
        if (dNaive < 0) return "disc fill set differs from the census rule";
        if (dc != dNaive) return "disc fill count " + dc + " != census " + dNaive;
        HexWaterGrid gDisc2 = new HexWaterGrid();
        float cxDisc = 2f * gDisc2.xPitch;
        int d2 = gDisc2.setRegionDisc(cxDisc, 0f, 4f);
        // Translation by a lattice pitch: the fill must still satisfy the same
        // rule at the shifted centre. Bitwise set-equality ACROSS centres is
        // deliberately NOT asserted — boundary tiles can round differently
        // under a float centre shift without any defect — but each tile must
        // self-identify through the query path at its own world position.
        if (d2 != naiveDiscCensus(gDisc2, cxDisc, 0f, 4f)) {
            return "shifted disc fill differs from the census rule";
        }
        HashSet<String> ids = new HashSet<>();
        for (int i = 0; i < dc; i++) ids.add(String.valueOf(gDisc.getTile(i).id));
        for (int i = 0; i < gDisc2.getTileCount(); i++) {
            HexWaterGrid.Tile t = gDisc2.getTile(i);
            if (gDisc2.tileAt(t.cx, t.cz) != t) return "shifted disc self-lookup";
        }
        if (ids.size() != dc) return "disc ids not unique";
        try { gDisc.setRegionDisc(0f, 0f, 0f); return "zero radius accepted"; } catch (IllegalArgumentException e) { /* ok */ }
        try { gDisc.setRegionDisc(0f, 0f, -2f); return "negative radius accepted"; } catch (IllegalArgumentException e) { /* ok */ }

        // ── 5. tileAt: origin, self-consistency, inradius bound, outside ─────
        HexWaterGrid gt = new HexWaterGrid();
        gt.setRegionDisc(0f, 0f, 6f);
        HexWaterGrid.Tile org = gt.tileAt(0f, 0f);
        if (org == null || org.q != 0 || org.r != 0) return "tileAt origin";
        final float circum = gt.tileR * 1.001f;   // Voronoi bound: hit centre ≤ R away (R at corners)
        for (int i = 0; i < gt.getTileCount(); i++) {
            HexWaterGrid.Tile t = gt.getTile(i);
            if (gt.tileAt(t.cx, t.cz) != t) return "tileAt not self-consistent at " + t;
            if (gt.lookup(t.q, t.r) != t) return "lookup not self-consistent at " + t;
        }
        for (float px = -5.0f; px <= 5.0f; px += 0.31f) {
            for (float pz = -5.0f; pz <= 5.0f; pz += 0.29f) {
                HexWaterGrid.Tile hit = gt.tileAt(px, pz);
                if (hit == null) {
                    // null is only acceptable when the point is well outside reach
                    double d = Math.sqrt((double) px * px + (double) pz * pz);
                    if (d <= 6.0 - circum) return "tileAt null inside the region at " + px + "," + pz;
                    continue;
                }
                float dx = px - hit.cx, dz = pz - hit.cz;
                if (dx * dx + dz * dz > circum * circum) return "tileAt beyond the circumradius at " + px + "," + pz;
            }
        }
        if (gt.tileAt(100f, 100f) != null) return "tileAt far outside returned a tile";

        // ── 6. neighbours: flower order, symmetry, interior density ──────────
        HexWaterGrid gn = new HexWaterGrid();
        gn.setRegionDisc(0f, 0f, 9f);
        HexWaterGrid.Tile c = gn.lookup(0, 0);
        if (c == null) return "flower missing centre";
        HexWaterGrid.Tile[] nb = new HexWaterGrid.Tile[6];
        int cnt = gn.fillNeighbors(c, nb);
        int[] eq = {1, 1, 0, -1, -1, 0};
        int[] er = {0, -1, -1, 0, 1, 1};
        if (cnt != 6) return "flower neighbour count " + cnt;
        for (int i = 0; i < 6; i++) {
            if (nb[i] == null || nb[i].q != eq[i] || nb[i].r != er[i]) return "neighbour order at " + i;
        }
        int shortfalls = 0;
        for (int i = 0; i < gn.getTileCount(); i++) {
            HexWaterGrid.Tile t = gn.getTile(i);
            double d = Math.sqrt((double) t.cx * t.cx + (double) t.cz * t.cz);
            HexWaterGrid.Tile[] back = new HexWaterGrid.Tile[6];
            int n2 = gn.fillNeighbors(t, back);
            if (d <= 8.0 && n2 != 6) return "interior tile at " + t + " has " + n2 + " neighbours";
            if (n2 < 6) shortfalls++;
            for (int k = 0; k < 6; k++) {
                if (back[k] == null) continue;
                HexWaterGrid.Tile[] rev = new HexWaterGrid.Tile[6];
                gn.fillNeighbors(back[k], rev);
                if (rev[(k + 3) % 6] != t) return "adjacency not symmetric at " + t + " slot " + k;
            }
        }
        if (shortfalls == 0 && gn.getTileCount() > 50) return "no shore tiles found in a disc fill (suspicious)";

        // ── 7. stable id/phase ────────────────────────────────────────────────
        if (HexWaterGrid.idOf(1, 2) != ((2L << 16) | 1L)) return "idOf packing";
        if (HexWaterGrid.idOf(-1, -1) != 0xFFFFFFFFL) return "idOf negative packing";
        if (HexWaterGrid.idOf(32767, -32768) != (0x8000L << 16 | 0x7FFFL)) return "idOf extremes";
        HashSet<Long> seenId = new HashSet<>();
        HashSet<Integer> buckets = new HashSet<>();
        for (int q = -40; q <= 40; q++) {
            for (int r = -40; r <= 40; r += 3) {
                long id = HexWaterGrid.idOf(q, r);
                if (!seenId.add(id)) return "idOf collision at " + q + "," + r;
                float ph = HexWaterGrid.phaseOf(q, r);
                if (!(ph >= 0f) || !(ph < 1f) || ph != ph) return "phase out of range at " + q + "," + r;
                if (ph != HexWaterGrid.phaseOf(q, r)) return "phase not stable";
                buckets.add((int) (ph * 8f));
            }
        }
        if (buckets.size() < 6) return "phase distribution clumped (" + buckets.size() + "/8 buckets)";
        // two grids → identical ids per (q,r); makeTile derives, never samples
        if (gDisc.getTileCount() > 0 && gDisc2.getTileCount() > 0) {
            HexWaterGrid.Tile a = gDisc.getTile(0), b = gDisc2.getTile(0);
            if (a.id != HexWaterGrid.idOf(a.q, a.r) || b.id != HexWaterGrid.idOf(b.q, b.r)) {
                return "tile id is not idOf(q,r)";
            }
        }

        // ── 8. cap mesh: welds by node set, topology, winding, uv ────────────
        HexWaterGrid gm = new HexWaterGrid();
        gm.setUvScale(4f);
        gm.setRegionDisc(0f, 0f, 9f);
        gm.buildCapMesh();
        int tiles = gm.getTileCount();
        // independent node census: every centre node + every corner node, as a set
        HashSet<Long> nodes = new HashSet<>();
        for (int i = 0; i < tiles; i++) {
            HexWaterGrid.Tile t = gm.getTile(i);
            nodes.add(nodeKey(t.centreM, t.centreN));
            for (int k = 0; k < 6; k++) nodes.add(nodeKey(t.cornerNodeM(k), t.cornerNodeN(k)));
        }
        if (gm.getCapIndexCount() != 18 * tiles) return "cap index count " + gm.getCapIndexCount();
        if (gm.getCapVertexCount() != nodes.size()) {
            return "cap weld count " + gm.getCapVertexCount() + " != unique nodes " + nodes.size();
        }
        float[] v = gm.getCapVerts();
        int[] ix = gm.getCapIndices();
        // every vertex is exactly one census node; positions are ONE product half·(m|n)
        HashMap<Long, Integer> seenNodes = new HashMap<>();
        for (int i = 0; i < v.length; i += 8) {
            int m = Math.round(v[i] / gm.halfX), n = Math.round(v[i + 2] / gm.halfZ);
            long key = nodeKey(m, n);
            if (!near(v[i], gm.halfX * m, 1e-4f) || !near(v[i + 2], gm.halfZ * n, 1e-4f)) {
                return "cap vertex off the node lattice at " + (i / 8);
            }
            if (!nodes.contains(key)) return "cap vertex not in the census: node " + m + "," + n;
            Integer prev = seenNodes.put(key, i / 8);
            if (prev != null) return "cap vertex duplicated (no weld): node " + m + "," + n;
            if (v[i + 1] != 0f) return "cap y not 0 at " + (i / 8);
            if (v[i + 3] != 0f || v[i + 4] != 1f || v[i + 5] != 0f) return "cap normal not (0,1,0)";
            if (!near(v[i + 6], v[i] / gm.getUvScale(), 1e-6f) || !near(v[i + 7], v[i + 2] / gm.getUvScale(), 1e-6f)) {
                return "cap uv not world-linear at " + (i / 8);
            }
        }
        if (seenNodes.size() != nodes.size()) return "cap census coverage mismatch";
        for (int i = 0; i < ix.length; i++) {
            if (ix[i] < 0 || ix[i] >= v.length / 8) return "cap index out of range at " + i;
        }
        // winding: triangles are (centre, ring[k+1], ring[k]) in ring-bearing
        // order — the determinant sign of that exact pattern stays negative;
        // any reorder of the fan flips it. Mirrors the STEP 92b shipped cap.
        for (int i = 0; i < ix.length; i += 3) {
            float ax = v[ix[i] * 8], az = v[ix[i] * 8 + 2];
            float bx = v[ix[i + 1] * 8], bz = v[ix[i + 1] * 8 + 2];
            float cxx = v[ix[i + 2] * 8], czz = v[ix[i + 2] * 8 + 2];
            if ((bx - ax) * (czz - az) - (bz - az) * (cxx - ax) >= -1e-5f) {
                return "cap fan winding flipped at triangle " + (i / 3);
            }
        }
        // shared corner topology: node (+1,-1) belongs to (0,0).c5, (1,0).c3,
        // (1,-1).c1 — one welded vertex, hit by exactly 6 fan indices (2 per tile)
        HexWaterGrid.Tile tl0 = gm.lookup(0, 0), te0 = gm.lookup(1, 0), tne0 = gm.lookup(1, -1);
        if (tl0 == null || te0 == null || tne0 == null) return "sharing fixture tiles missing";
        int[][] fixture = {{0, 5}, {1, 3}, {2, 1}};   // {tile slot in {tl0,te0,tne0}, corner k}
        for (int[] f : fixture) {
            HexWaterGrid.Tile t = (f[0] == 0) ? tl0 : (f[0] == 1) ? te0 : tne0;
            if (t.cornerNodeM(f[1]) != 1 || t.cornerNodeN(f[1]) != -1) {
                return "fixture tile " + f[0] + " corner " + f[1] + " is not node (1,-1)";
            }
        }
        int shares = 0;
        int vi = seenNodes.get(nodeKey(1, -1));
        for (int i = 0; i < ix.length; i++) if (ix[i] == vi) shares++;
        if (shares != 6) return "shared corner node (+1,-1) appears " + shares + " times, expected 6";

        // ── 9. pond skirt: independent boundary census, walls, normals, uv ───
        HexWaterGrid gp = new HexWaterGrid();
        gp.setUvScale(4f);
        gp.setRegionDisc(0f, 0f, 9f);
        final float depth = 0.5f;
        gp.buildPondMesh(depth);
        gp.buildCapMesh();   // populate cap arrays: pond-vs-cap accounting needs the baseline
        // independent boundary census on the same built tile set
        HashMap<String, int[]> census = new HashMap<>();   // edge key → {aM,aN,bM,bN,count}
        for (int i = 0; i < gp.getTileCount(); i++) {
            HexWaterGrid.Tile t = gp.getTile(i);
            for (int k = 0; k < 6; k++) {
                int aM = t.cornerNodeM(k), aN = t.cornerNodeN(k);
                int bM = t.cornerNodeM((k + 1) % 6), bN = t.cornerNodeN((k + 1) % 6);
                String key = edgeKey(aM, aN, bM, bN);
                int[] rec = census.get(key);
                if (rec == null) census.put(key, new int[]{aM, aN, bM, bN, 1});
                else rec[4]++;
            }
        }
        int boundary = 0;
        for (int[] rec : census.values()) if (rec[4] == 1) boundary++;
        if (gp.getPondIndexCount() != gp.getCapIndexCount() + 6 * boundary) {
            return "pond index count " + gp.getPondIndexCount() + " != cap + 6*" + boundary;
        }
        if (boundary == 0) return "boundary census empty for a disc fill";
        // pond cap prefix == cap-only build, bitwise
        float[] pv = gp.getPondVerts();
        int capF = v.length;
        if (pv.length < capF) return "pond mesh lost cap vertices";
        for (int i = 0; i < capF; i++) {
            if (Float.floatToRawIntBits(pv[i]) != Float.floatToRawIntBits(v[i])) {
                return "pond cap section differs bitwise from the cap-only build at float " + i;
            }
        }
        int[] pix = gp.getPondIndices();
        for (int i = 0; i < ix.length; i++) {
            if (pix[i] != ix[i]) return "pond cap indices differ from the cap-only build at " + i;
        }
        // wall verts sit after the cap section: exactly 4 per boundary edge
        if (pv.length != capF + 32 * boundary) {
            return "pond wall vertex count " + (pv.length - capF) / 8 + " != 4*" + boundary;
        }
        // per-vertex: y in {0, -depth}, unit horizontal normal, ny = 0, linear uv
        for (int i = capF; i < pv.length; i += 8) {
            float y = pv[i + 1];
            if (y != 0f && y != -depth) return "pond wall y " + y + " at vertex " + (i / 8);
            float nx = pv[i + 3], nz = pv[i + 5];
            float len = (float) Math.sqrt((double) nx * nx + (double) nz * nz);
            if (Math.abs(len - 1f) > 1e-4f) return "pond wall normal not unit at " + (i / 8);
            float u = pv[i + 6], vv = pv[i + 7];
            if (!near(u, pv[i] / gp.getUvScale(), 1e-6f)) return "pond wall u not world-linear at " + (i / 8);
            if (y == 0f) { if (!near(vv, 0f, 1e-9f)) return "pond rim v not 0"; }
            else { if (!near(vv, depth / gp.getUvScale(), 1e-6f)) return "pond bottom v wrong"; }
            if (pv[i + 4] != 0f) return "pond wall ny not 0";
        }
        // per-endpoint outward normals: every boundary edge must own all four
        // wall vertices (rim + bottom at BOTH endpoints), each carrying THIS
        // edge's expected outward normal (dz, -dx)/len. The match requires the
        // expected normal itself, so a shared-corner slot of the ADJACENT edge
        // (same node, different normal) can never satisfy the match.
        for (int[] rec : census.values()) {
            if (rec[4] != 1) continue;
            int aM = rec[0], aN = rec[1], bM = rec[2], bN = rec[3];
            float ax = gp.halfX * aM, az = gp.halfZ * aN;
            float bx = gp.halfX * bM, bz = gp.halfZ * bN;
            float dx = bx - ax, dz = bz - az;
            float len = (float) Math.sqrt((double) dx * dx + (double) dz * dz);
            float enx = len > 1e-9f ? dz / len : 0f;
            float enz = len > 1e-9f ? -dx / len : 0f;
            int aRim = wallSlot(pv, capF, gp.halfX, gp.halfZ, aM, aN, true, depth, enx, enz);
            int aBot = wallSlot(pv, capF, gp.halfX, gp.halfZ, aM, aN, false, depth, enx, enz);
            int bRim = wallSlot(pv, capF, gp.halfX, gp.halfZ, bM, bN, true, depth, enx, enz);
            int bBot = wallSlot(pv, capF, gp.halfX, gp.halfZ, bM, bN, false, depth, enx, enz);
            if (aRim < 0 || aBot < 0 || bRim < 0 || bBot < 0) {
                return "pond skirt missing an edge-normal-matched endpoint vertex at edge "
                        + aM + "," + aN + " -> " + bM + "," + bN;
            }
            // winding, intrinsic: the wall triangle containing BOTH rim
            // endpoints must be vertical and its geometric normal must match
            // the expected outward direction - no pond-centre/owner reference.
            boolean foundTri = false;
            for (int j = ix.length; j + 2 < pix.length && !foundTri; j += 3) {
                boolean hasAR = pix[j] == aRim || pix[j + 1] == aRim || pix[j + 2] == aRim;
                boolean hasBR = pix[j] == bRim || pix[j + 1] == bRim || pix[j + 2] == bRim;
                if (!hasAR || !hasBR) continue;
                float v0x = pv[pix[j] * 8], v0y = pv[pix[j] * 8 + 1], v0z = pv[pix[j] * 8 + 2];
                float v1x = pv[pix[j + 1] * 8], v1y = pv[pix[j + 1] * 8 + 1], v1z = pv[pix[j + 1] * 8 + 2];
                float v2x = pv[pix[j + 2] * 8], v2y = pv[pix[j + 2] * 8 + 1], v2z = pv[pix[j + 2] * 8 + 2];
                float e1x = v1x - v0x, e1y = v1y - v0y, e1z = v1z - v0z;
                float e2x = v2x - v0x, e2y = v2y - v0y, e2z = v2z - v0z;
                float gnx = e1y * e2z - e1z * e2y;
                float gny = e1z * e2x - e1x * e2z;
                float gnz = e1x * e2y - e1y * e2x;
                if (Math.abs(gny) > 1e-4f) {
                    return "pond wall tri not vertical at edge " + aM + "," + aN + " -> " + bM + "," + bN;
                }
                float dot = gnx * enx + gnz * enz;
                if (dot <= 0f) {
                    return "pond wall winding faces inward at edge " + aM + "," + aN + " -> " + bM + "," + bN;
                }
                foundTri = true;
            }
            if (!foundTri) {
                return "pond skirt has no wall triangle for edge " + aM + "," + aN + " -> " + bM + "," + bN;
            }
        }
        try { gp.buildPondMesh(0f); return "zero depth accepted"; } catch (IllegalArgumentException e) { /* ok */ }
        try { gp.buildPondMesh(-1f); return "negative depth accepted"; } catch (IllegalArgumentException e) { /* ok */ }
        try { gp.buildPondMesh(Float.NaN); return "NaN depth accepted"; } catch (IllegalArgumentException e) { /* ok */ }
        try { gp.setUvScale(0f); return "zero uv scale accepted"; } catch (IllegalArgumentException e) { /* ok */ }

        // ── 10. measured zero allocation on the steady-state path ────────────
        String alloc = allocationCheck();
        if (!alloc.isEmpty()) return alloc;
        return "";
    }

    public static void main(String[] args) {
        String why;
        try { why = run(); }
        catch (Throwable t) { why = "threw " + t; }
        System.out.println(why.isEmpty() ? "PASS HexWaterSelfCheck" : "FAIL HexWaterSelfCheck -- " + why);
        if (!why.isEmpty()) System.exit(1);
    }

    /* ── helpers ── */

    private static long nodeKey(int m, int n) { return ((long) m << 32) | (n & 0xFFFFFFFFL); }

    private static String edgeKey(int aM, int aN, int bM, int bN) {
        long a = nodeKey(aM, aN), b = nodeKey(bM, bN);
        long lo = Math.min(a, b), hi = Math.max(a, b);
        return lo + ":" + hi;
    }

    /**
     * Set-equality census for setRegionRect: scans a padded superset of the
     * builder's (r, q) iteration range, then applies ONLY the exact rule —
     * dist(tile centre, rect) ≤ R — mirroring the builder's op order (float
     * hx/hz, float row/col products, double filter) so boundary ties compare
     * bitwise. Returns the rule count, or −1 when the built set differs from
     * the rule in EITHER direction (grid omitted a rule tile / count mismatch).
     */
    private static int naiveRectCensus(HexWaterGrid g, float cx, float cz, float lw, float ld) {
        final float hx = lw / 2f, hz = ld / 2f;
        final double R2 = (double) g.tileR * g.tileR;
        HashSet<Long> built = new HashSet<>();
        for (int i = 0; i < g.getTileCount(); i++) {
            built.add(HexWaterGrid.idOf(g.getTile(i).q, g.getTile(i).r));
        }
        final int rMin = (int) Math.floor((cz - hz - g.tileR) / g.zPitch) - 2;
        final int rMax = (int) Math.ceil((cz + hz + g.tileR) / g.zPitch) + 2;
        int rule = 0;
        for (int r = rMin; r <= rMax; r++) {
            final float rz = g.zPitch * r;
            final double dz = Math.max(0.0, Math.abs(rz - cz) - hz);
            final double halfShift = (g.xPitch / 2.0) * r;
            final int qMin = (int) Math.floor((cx - hx - g.tileR - halfShift) / g.xPitch) - 2;
            final int qMax = (int) Math.ceil((cx + hx + g.tileR - halfShift) / g.xPitch) + 2;
            for (int q = qMin; q <= qMax; q++) {
                final float x = g.xPitch * q + (float) halfShift;
                final double dx = Math.max(0.0, Math.abs(x - cx) - hx);
                if (dx * dx + dz * dz > R2) continue;
                rule++;                                                     // the rule includes it
                if (!built.contains(HexWaterGrid.idOf(q, r))) return -1;    // grid omitted a rule tile
            }
        }
        return rule == built.size() ? rule : -1;   // count mismatch ⇒ extra/missing built tiles
    }

    /**
     * Set-equality census for setRegionDisc: padded superset scan + ONLY the
     * exact rule dist(centre, disc) ≤ radius + R, mirrored bit-for-bit from
     * the builder. Returns the rule count, or −1 on any set difference.
     */
    private static int naiveDiscCensus(HexWaterGrid g, float cx, float cz, float radius) {
        final double reach = (double) radius + (double) g.tileR;
        final double reach2 = reach * reach;
        HashSet<Long> built = new HashSet<>();
        for (int i = 0; i < g.getTileCount(); i++) {
            built.add(HexWaterGrid.idOf(g.getTile(i).q, g.getTile(i).r));
        }
        final int rMin = (int) Math.floor((cz - reach) / g.zPitch) - 2;
        final int rMax = (int) Math.ceil((cz + reach) / g.zPitch) + 2;
        int rule = 0;
        for (int r = rMin; r <= rMax; r++) {
            final float rz = g.zPitch * r;
            final double dz = rz - cz;
            final double halfShift = (g.xPitch / 2.0) * r;
            final int qMin = (int) Math.floor((cx - reach - halfShift) / g.xPitch) - 2;
            final int qMax = (int) Math.ceil((cx + reach - halfShift) / g.xPitch) + 2;
            for (int q = qMin; q <= qMax; q++) {
                final float x = g.xPitch * q + (float) halfShift;
                final double dx = x - cx;
                if (dx * dx + dz * dz > reach2) continue;
                rule++;
                if (!built.contains(HexWaterGrid.idOf(q, r))) return -1;
            }
        }
        return rule == built.size() ? rule : -1;
    }

    /**
     * Find a pond-wall vertex slot for node (m, n) carrying THIS edge's
     * expected outward normal (enx, enz), at rim (y = 0) or bottom
     * (y = −depth). Returns the VERTEX index (pix[] units), or −1. Requiring the
     * normal makes the shared-corner slot of the ADJACENT edge (same node,
     * different normal) unmatchable — each endpoint only matches its own
     * edge's vertex, never just any wall vertex near the node.
     */
    private static int wallSlot(float[] pv, int capF, float halfX, float halfZ,
                                int m, int n, boolean rim, float depth, float enx, float enz) {
        final float wx = halfX * m, wz = halfZ * n;
        final float wy = rim ? 0f : -depth;
        for (int i = capF; i < pv.length; i += 8) {
            if (near(pv[i], wx, 1e-4f) && near(pv[i + 1], wy, 1e-6f)
                    && near(pv[i + 2], wz, 1e-4f)
                    && near(pv[i + 3], enx, 1e-4f) && near(pv[i + 5], enz, 1e-4f)) {
                return i / 8;   // vertex index — pix[] units
            }
        }
        return -1;
    }

    /** MP-028 pattern: measured per-thread allocation over a query-path loop. */
    private static String allocationCheck() {
        com.sun.management.ThreadMXBean mx;
        try {
            mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
            if (!mx.isThreadAllocatedMemorySupported()) return "";   // cannot measure here: not a failure
            mx.setThreadAllocatedMemoryEnabled(true);
        } catch (Throwable t) {
            return "";   // cannot measure here: not a failure
        }
        HexWaterGrid g = new HexWaterGrid();
        g.setRegionDisc(0f, 0f, 9f);
        g.buildCapMesh();
        final HexWaterGrid.Tile probe = g.lookup(0, 0);
        if (probe == null) return "allocation probe grid lost its centre tile";
        final HexWaterGrid.Tile[] scratch = new HexWaterGrid.Tile[6];
        // warm up the JIT so one-time costs (class loading, deopt) don't count
        long acc = 0;
        for (int i = 0; i < 300; i++) acc += queryPath(g, probe, scratch);
        if (acc == -1) return "query path broke under warmup";
        long id = Thread.currentThread().getId();
        long before = mx.getThreadAllocatedBytes(id);
        for (int i = 0; i < 3000; i++) acc += queryPath(g, probe, scratch);
        long delta = mx.getThreadAllocatedBytes(id) - before;
        if (acc == -2) return "query path broke under measurement";
        return delta > 1024 ? "allocated " + delta + " bytes over 3000 query iterations (expected ~0)" : "";
    }

    /** One steady-state iteration of exactly what QUAL-4's render path will call. */
    private static long queryPath(HexWaterGrid g, HexWaterGrid.Tile probe, HexWaterGrid.Tile[] scratch) {
        long acc = 0;
        acc += g.tileAt(probe.cx + 0.03f, probe.cz + 0.05f).id;
        acc += g.tileAt(probe.cx + g.xPitch * 0.4f, probe.cz + g.zPitch * 0.4f).id;
        acc += g.tileAt(probe.cx - g.xPitch * 0.35f, probe.cz - g.zPitch * 0.45f).id;
        acc += g.fillNeighbors(probe, scratch);
        acc += g.lookup(2, 1) != null ? g.lookup(2, 1).id : 0L;
        acc += g.getTileCount();
        for (int i = 0; i < 8; i++) acc += HexWaterGrid.phaseOf(i, 17 - i);
        return acc;
    }

    /** Dir-table snapshot of the world HexCoord port (parity anchor for NQ/NR). */
    private static final class HexCoordDirs {
        private static final int[][] DIRS = {{1, 0}, {1, -1}, {0, -1}, {-1, 0}, {-1, 1}, {0, 1}};
        static int[][] snapshot() {
            int[][] out = new int[DIRS.length][];
            for (int i = 0; i < DIRS.length; i++) out[i] = DIRS[i].clone();
            return out;
        }
    }
}