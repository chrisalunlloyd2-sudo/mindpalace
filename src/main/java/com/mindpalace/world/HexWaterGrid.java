package com.mindpalace.world;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * HexWaterGrid — QUAL-3 (t_ddf78c8d): pointy-top hex lattice over a world-space
 * region (rect or disc), with exact integer-node vertex sharing, a flat cap
 * mesh builder, a pond builder with bank skirt, and stable per-tile id/phase.
 * GL-free, no per-frame allocation, no render/Ollama dependency. Nothing here
 * draws — QUAL-4 (t_9bcb8660) consumes it behind render.hexWater. Companion
 * docs: docs/HEX_WATER.md.
 *
 * ORIENTATION — POINTY-TOP, ring at bearing 30+60k degrees (x = cos·R, z =
 * sin·R): the SAME ring convention the live lake lattice uses (OutsideWorld
 * STEP 92b; Renderer hex-prism ring anchored at 30°). One orientation in the
 * codebase keeps shore/dock/water composable. Layout: x pitch √3·R, z pitch
 * 1.5·R, cell area (3√3/2)·R². Default R = 1.2 → cell area 3.7412 u² and xStep
 * √3·1.2 = 2.078 — exactly the live lake lattice (lake height-step 2.4 = 2R),
 * so a pond from this grid sits on the SAME lattice the lake already uses.
 *
 * EXACT SHARED EDGES — every hex vertex is a lattice node on the half-unit
 * grid (x = halfX·m, z = halfZ·n) for INTEGERS m, n, with halfX = √3/2·R and
 * halfZ = R/2. Tile (q, r) has centre node (2q + r, 3r) and corner k at node
 * (2q + r + DM[k], 3r + DN[k]) — verified equal to (cos, sin)(30+60k)·R at all
 * six bearings: corner 0 (30°) (+1,+1), 1 (90°) (0,+2), 2 (150°) (−1,+1),
 * 3 (210°) (−1,−1), 4 (270°) (0,−2), 5 (330°) (+1,−1). Each position is ONE
 * product half·m (exact for |m| < 2^20), never centre + offset, so tiles
 * meeting at a node produce BITWISE-IDENTICAL floats and the mesh builder
 * welds them losslessly by node key. Each VERTEX is shared by up to 3 hexes
 * (corner 0 = E's corner 2 = SE's corner 4; corner 5 = E's corner 3 = NE's
 * corner 1); each interior EDGE by exactly 2 hexes (shore edges by 1).
 *
 * SHORE POLICY — OVERSCAN UNDER THE BANK: a tile is included iff
 * dist²(tile centre, region) ≤ R² (rect: exact box distance; disc: centre
 * within radius + R). Every region point is covered: the hex containing a
 * sample has its centre within R of the sample. Water therefore slips slightly
 * under banks/rims and the BANK GEOMETRY clips the water visually — tiles stay
 * full convex hexes, so node sharing survives the shoreline. Vertex editing /
 * edge snapping rejected: variable clip polygons break welding across the seam
 * and add per-tile branch geometry (docs/HEX_WATER.md §decisions).
 *
 * POND SKIRT — buildPondMesh(depth) adds a downward wall along every boundary
 * edge (incident to exactly ONE built tile) to y = −depth, killing the
 * see-under-the-bank gap. Wall per-edge vertices + winding mirror the proven
 * Renderer hex-prism wall block ("a_bot, a_top, b_top, b_bot → outward"); cap
 * winding mirrors the proven STEP 92b cap flip ((centre, ring_{k+1}, ring_k)
 * faces +Y). Wall normal = outward from the owning tile = (dz, −dx)/len for
 * edge direction (dx, dz) in owner ring order (verified: edge corner0→corner1
 * mid-bearing 60° gives (0.5, 0.866) — points away from the centre).
 *
 * STABLE ID/PHASE — id packs (q, r) into a long; phase mixes it through the
 * splitmix64 finalizer; both pure functions of (q, r): deterministic across
 * restarts/JVMs, no map, no generation order.
 *
 * ALLOCATION MODEL — build-time methods (setRegion*, build*Mesh, install)
 * allocate; the steady-state query path (getTileCount, getTile, getTiles,
 * tileAt, fillNeighbors, phaseOf, idOf) allocates ZERO and never mutates, so
 * the render thread can iterate per frame allocation-free (HexWaterSelfCheck
 * asserts 0 bytes via the HotSpot ThreadMXBean).
 */
public final class HexWaterGrid {

    // ── Layout constants ─────────────────────────────────────────────────────

    /** √3 — x-pitch multiplier (flat-to-flat side). */
    public static final double SQRT3 = Math.sqrt(3.0);
    /** Hex area constant: cell area = (3√3/2)·R². */
    public static final double AREA_K = 3.0 * SQRT3 / 2.0;
    /** Default tile circumradius: cell area 3.7412 u², pitch = live lake lattice. */
    public static final float DEFAULT_TILE_R = 1.2f;

    /** Corner k node offsets from the centre node, in half-units (see header). */
    public static final int[] DM = {1, 0, -1, -1, 0, 1};
    public static final int[] DN = {1, 2, 1, -1, -2, -1};
    /** Neighbour axial deltas in order (E, NE, NW, W, SW, SE). */
    public static final int[] NQ = {1, 1, 0, -1, -1, 0};
    public static final int[] NR = {0, -1, -1, 0, 1, 1};

    // ── Grid parameters (final) ──────────────────────────────────────────────

    public final float tileR;
    public final float xPitch;   // √3·R
    public final float zPitch;   // 1.5·R
    public final float halfX;    // √3/2·R — one half-x unit
    public final float halfZ;    // R/2   — one half-z unit
    public final float tileArea; // (3√3/2)·R²
    /** World units per full uv unit (default 2R = one tile diameter). */
    private float uvScale;

    // ── Built state ──────────────────────────────────────────────────────────

    private Tile[] tiles = new Tile[0];
    private int tileCount = 0;
    /** Dense row index for alloc-free tileAt/neighbor lookups (rebuilt per region). */
    private Row[] rows = new Row[0];
    /** Top-cap mesh outputs: [x, y, z, 0, 1, 0, u, v] ×N + fan indices. */
    private float[] capVerts = new float[0];
    private int[] capIndices = new int[0];
    /** Pond mesh outputs (cap + boundary skirt). */
    private float[] pondVerts = new float[0];
    private int[] pondIndices = new int[0];

    private static final class Row {
        final int r;
        final int qBase;
        final Tile[] byQ;
        Row(int r, int qBase, Tile[] byQ) { this.r = r; this.qBase = qBase; this.byQ = byQ; }
    }

    /** Undirected edge between two node keys, canonicalised a &lt; b (build-time). */
    private static final class Edge {
        final long a, b;
        Edge(long a, long b) {
            this.a = Math.min(a, b);
            this.b = Math.max(a, b);
        }
        @Override public boolean equals(Object o) {
            return (o instanceof Edge e) && e.a == a && e.b == b;
        }
        @Override public int hashCode() {
            return (int) (a * 0x9E3779B97F4A7C15L >>> 32) ^ (int) (b * 0xC2B2AE3D27D4EB4FL >>> 32);
        }
    }

    // ── Tile ─────────────────────────────────────────────────────────────────

    /** One immutable water tile. Positions derive from integer nodes (exact). */
    public static final class Tile {
        public final int q, r;
        /** Centre node (m, n) — the exact-lattice anchor. */
        public final int centreM, centreN;
        /** World centre (single product from the node; canonical). */
        public final float cx, cz;
        /** Stable, pure (q, r)-derived identity; shimmer phase in [0, 1). */
        public final long id;
        public final float phase;
        // This grid's half-units, copied so corner math is self-contained.
        private final float gHalfX, gHalfZ;

        Tile(int q, int r, int centreM, int centreN, float cx, float cz,
             long id, float phase, float gHalfX, float gHalfZ) {
            this.q = q; this.r = r;
            this.centreM = centreM; this.centreN = centreN;
            this.cx = cx; this.cz = cz;
            this.id = id; this.phase = phase;
            this.gHalfX = gHalfX; this.gHalfZ = gHalfZ;
        }

        /** Corner k node m (centre node + DM[k]). */
        public int cornerNodeM(int k) { return centreM + DM[k]; }
        /** Corner k node n (centre node + DN[k]). */
        public int cornerNodeN(int k) { return centreN + DN[k]; }
        /** Corner k world x — ONE product: halfX·m. Bitwise-shared with neighbours. */
        public float cornerX(int k) { return gHalfX * cornerNodeM(k); }
        /** Corner k world z — ONE product: halfZ·n. */
        public float cornerZ(int k) { return gHalfZ * cornerNodeN(k); }
        @Override public String toString() { return "hex(" + q + "," + r + ")"; }
    }

    // ── Construction ─────────────────────────────────────────────────────────

    public HexWaterGrid() { this(DEFAULT_TILE_R); }

    public HexWaterGrid(float tileR) {
        if (!(tileR > 0f) || !Float.isFinite(tileR)) {
            throw new IllegalArgumentException("tileR must be positive finite, got " + tileR);
        }
        this.tileR = tileR;
        this.xPitch = (float) (SQRT3 * tileR);
        this.zPitch = 1.5f * tileR;
        this.halfX = this.xPitch / 2f;
        this.halfZ = tileR / 2f;
        this.tileArea = (float) (AREA_K * (double) tileR * tileR);
        this.uvScale = 2f * tileR;
    }

    /** Grid sized for a target per-cell area: R = sqrt(2A / (3√3)). */
    public static HexWaterGrid withTileArea(float area) {
        if (!(area > 0f) || !Float.isFinite(area)) {
            throw new IllegalArgumentException("area must be positive finite, got " + area);
        }
        return new HexWaterGrid((float) Math.sqrt(area / AREA_K));
    }

    // ── Region fill (build-time; allocates) ──────────────────────────────────

    /**
     * Fill the axis-aligned rect [cx ± lw/2] × [cz ± ld/2] with tiles whose
     * centre lies within R of the rect (overscan-under-the-bank, exact box
     * distance). Returns the tile count. Ordered r-ascending, q-ascending;
     * included q's per row are contiguous (interval test + exact filter).
     */
    public int setRegionRect(float cx, float cz, float lw, float ld) {
        if (!(lw > 0f) || !(ld > 0f) || !Float.isFinite(lw) || !Float.isFinite(ld)) {
            throw new IllegalArgumentException("lw/ld must be positive finite");
        }
        final float hx = lw / 2f, hz = ld / 2f;
        final int rMin = (int) Math.ceil((cz - hz - tileR) / zPitch);
        final int rMax = (int) Math.floor((cz + hz + tileR) / zPitch);
        final double R2 = (double) tileR * tileR;
        List<Tile> out = new ArrayList<>();
        for (int r = rMin; r <= rMax; r++) {
            final float rz = zPitch * r;
            final double dz = Math.max(0.0, Math.abs(rz - cz) - hz);
            final double halfShift = (xPitch / 2.0) * r;
            // Need dx ≤ √(R² − dz²) where dx = max(0, |x−cx| − hx) — an
            // interval in x ⇒ contiguous q interval.
            final double slack = Math.sqrt(Math.max(0.0, R2 - dz * dz));
            final int qMin = (int) Math.ceil((cx - hx - slack - halfShift) / xPitch);
            final int qMax = (int) Math.floor((cx + hx + slack - halfShift) / xPitch);
            for (int q = qMin; q <= qMax; q++) {
                final float x = xPitch * q + (float) halfShift;
                final double dx = Math.max(0.0, Math.abs(x - cx) - hx);
                if (dx * dx + dz * dz <= R2) out.add(makeTile(q, r));
            }
        }
        install(out);
        return tileCount;
    }

    /**
     * Fill the disc (cx, cz, radius) with tiles whose centre lies within
     * radius + R of the disc centre (the same overscan rule as the rect).
     * Returns the tile count.
     */
    public int setRegionDisc(float cx, float cz, float radius) {
        if (!(radius > 0f) || !Float.isFinite(radius)) {
            throw new IllegalArgumentException("radius must be positive finite");
        }
        final double reach = (double) radius + tileR;
        final double reach2 = reach * reach;
        final int rMin = (int) Math.ceil((cz - reach) / zPitch);
        final int rMax = (int) Math.floor((cz + reach) / zPitch);
        List<Tile> out = new ArrayList<>();
        for (int r = rMin; r <= rMax; r++) {
            final float rz = zPitch * r;
            final double dz = rz - cz;
            if (dz * dz > reach2) continue;
            final double halfW = Math.sqrt(Math.max(0.0, reach2 - dz * dz));
            final double halfShift = (xPitch / 2.0) * r;
            final int qMin = (int) Math.ceil((cx - halfW - halfShift) / xPitch);
            final int qMax = (int) Math.floor((cx + halfW - halfShift) / xPitch);
            for (int q = qMin; q <= qMax; q++) {
                final float x = xPitch * q + (float) halfShift;
                final double dx = x - cx;
                if (dx * dx + dz * dz <= reach2) out.add(makeTile(q, r));
            }
        }
        install(out);
        return tileCount;
    }

    /** Build one tile structurally — every field derived, nothing random. */
    private Tile makeTile(int q, int r) {
        final int centreM = 2 * q + r;
        final int centreN = 3 * r;
        final float cx = halfX * centreM;
        final float cz = halfZ * centreN;
        return new Tile(q, r, centreM, centreN, cx, cz, idOf(q, r), phaseOf(q, r), halfX, halfZ);
    }

    /** Install a built tile list + dense row index (r-ascending, q-ascending). */
    private void install(List<Tile> list) {
        tileCount = list.size();
        tiles = list.toArray(new Tile[0]);
        if (tileCount == 0) { rows = new Row[0]; return; }
        ArrayList<Row> rowList = new ArrayList<>();
        int i = 0;
        while (i < tileCount) {
            final int r = tiles[i].r;
            int j = i;
            while (j < tileCount && tiles[j].r == r) j++;
            final int qBase = tiles[i].q;
            Tile[] byQ = new Tile[tiles[j - 1].q - qBase + 1];
            for (int k = i; k < j; k++) byQ[tiles[k].q - qBase] = tiles[k];
            rowList.add(new Row(r, qBase, byQ));
            i = j;
        }
        rows = rowList.toArray(new Row[0]);
    }

    // ── Stable identity / phase ──────────────────────────────────────────────

    /** Stable id: (q & 0xFFFF) | ((r & 0xFFFF) &lt;&lt; 16), injective for |q|,|r| ≤ 32767. */
    public static long idOf(int q, int r) {
        return (q & 0xFFFFL) | ((r & 0xFFFFL) << 16);
    }

    /** Stable phase in [0,1): splitmix64 finalizer on the id, top 24 bits. */
    public static float phaseOf(int q, int r) {
        long h = idOf(q, r) * 0x9E3779B97F4A7C15L;
        h ^= (h >>> 30); h *= 0xBF58476D1CE4E5B9L;
        h ^= (h >>> 27); h *= 0x94D049BB133111EBL;
        h ^= (h >>> 31);
        return (float) ((h >>> 40) * (1.0 / 16777216.0)); // 2^24 buckets → [0,1)
    }

    // ── Steady-state query path (alloc-free) ─────────────────────────────────

    public int getTileCount() { return tileCount; }
    /** The built tiles, ordered r-ascending then q-ascending. Treat as read-only. */
    public Tile[] getTiles() { return tiles; }
    public Tile getTile(int i) { return tiles[i]; }
    /** World units per full uv span (set before building meshes). */
    public float getUvScale() { return uvScale; }

    /**
     * The tile whose hex contains world point (wx, wz): inverse lattice
     * transform (r = z/zPitch, q = x/xPitch − r/2) then exact axial cube-round,
     * then dense-row lookup. Returns null when that tile is outside the built
     * region. Zero allocation.
     */
    public Tile tileAt(float wx, float wz) {
        final double rF = wz / zPitch;
        final double qF = wx / xPitch - rF / 2.0;
        final double sF = -qF - rF;
        long rq = Math.round(qF), rr = Math.round(rF), rs = Math.round(sF);
        final double dQ = Math.abs(rq - qF), dR = Math.abs(rr - rF), dS = Math.abs(rs - sF);
        if (dQ > dR && dQ > dS) {
            rq = -rr - rs;
        } else if (dR > dQ && dR > dS) {
            rr = -rq - rs;
        } else {
            rs = -rq - rr; // q, r already consistent; keep s aligned
        }
        return lookup((int) rq, (int) rr);
    }

    /** Dense-row binary lookup (no boxing, no allocation). Package-visible for checks. */
    Tile lookup(int q, int r) {
        int lo = 0, hi = rows.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            Row row = rows[mid];
            if (row.r < r) lo = mid + 1;
            else if (row.r > r) hi = mid - 1;
            else {
                int idx = q - row.qBase;
                return (idx >= 0 && idx < row.byQ.length) ? row.byQ[idx] : null;
            }
        }
        return null;
    }

    /**
     * Fill {@code out} (length ≥ 6) with the built tiles neighbouring
     * {@code t} in axial order (E, NE, NW, W, SW, SE); null entries where the
     * neighbour is outside the region. Returns the non-null count. Zero alloc.
     */
    public int fillNeighbors(Tile t, Tile[] out) {
        int n = 0;
        for (int i = 0; i < 6; i++) {
            Tile nb = lookup(t.q + NQ[i], t.r + NR[i]);
            out[i] = nb;
            if (nb != null) n++;
        }
        return n;
    }

    // ── Mesh building (build-time; allocates) ────────────────────────────────

    /** Set world units per full uv unit BEFORE building meshes. */
    public void setUvScale(float worldUnitsPerUv) {
        if (!(worldUnitsPerUv > 0f) || !Float.isFinite(worldUnitsPerUv)) {
            throw new IllegalArgumentException("uv scale must be positive finite");
        }
        this.uvScale = worldUnitsPerUv;
    }

    /**
     * Build the flat top-cap surface: one up-facing fan per tile (centre + 6
     * corner nodes), uv world-linear, normal (0,1,0), y = 0. Vertices dedupe
     * BY NODE KEY so neighbours weld bitwise. Result via getCapVerts()/
     * getCapIndices() — hand to new Mesh(float[], int[]) at integration time.
     */
    public void buildCapMesh() {
        final HashMap<Long, Integer> nodeIndex = new HashMap<>();
        final List<Float> v = new ArrayList<>();
        final List<Integer> idx = new ArrayList<>();
        for (int t = 0; t < tileCount; t++) {
            Tile tile = tiles[t];
            int centreVi = capVertex(nodeIndex, v, tile.centreM, tile.centreN);
            int[] ring = new int[6];
            for (int k = 0; k < 6; k++) {
                ring[k] = capVertex(nodeIndex, v, tile.cornerNodeM(k), tile.cornerNodeN(k));
            }
            for (int k = 0; k < 6; k++) {
                // Cap winding from the proven STEP 92b flip: faces +Y.
                idx.add(centreVi);
                idx.add(ring[(k + 1) % 6]);
                idx.add(ring[k]);
            }
        }
        capVerts = toFloats(v);
        capIndices = toInts(idx);
    }

    /**
     * Build the pond mesh: the cap surface PLUS a skirt down the shoreline
     * (edges incident to exactly ONE built tile) to y = −depth, killing the
     * see-under-the-bank gap. Skirt normals point OUT of the pond; winding and
     * per-edge vertices mirror the proven hex-prism wall block.
     */
    public void buildPondMesh(float depth) {
        if (!(depth > 0f) || !Float.isFinite(depth)) {
            throw new IllegalArgumentException("depth must be positive finite");
        }
        final HashMap<Long, Integer> nodeIndex = new HashMap<>();
        final List<Float> v = new ArrayList<>();
        final List<Integer> idx = new ArrayList<>();

        // Pass 1: boundary edge census. Each hex edge is incident to exactly 1
        // (shore) or 2 (interior) built tiles. Record stores the OWNER ring
        // order (corner k → k+1) for count==1 edges; value = {aM, aN, bM, bN, count}.
        final HashMap<Edge, int[]> edges = new HashMap<>();
        for (int t = 0; t < tileCount; t++) {
            Tile tile = tiles[t];
            for (int k = 0; k < 6; k++) {
                int aM = tile.cornerNodeM(k),           aN = tile.cornerNodeN(k);
                int bM = tile.cornerNodeM((k + 1) % 6), bN = tile.cornerNodeN((k + 1) % 6);
                Edge e = new Edge(nodeKey(aM, aN), nodeKey(bM, bN));
                int[] rec = edges.get(e);
                if (rec == null) edges.put(e, new int[]{aM, aN, bM, bN, 1});
                else rec[4]++;
            }
        }
        final List<int[]> boundary = new ArrayList<>();
        for (Map.Entry<Edge, int[]> e : edges.entrySet()) {
            if (e.getValue()[4] == 1) boundary.add(e.getValue());
        }

        // Pass 2: cap fan (identical structure to buildCapMesh).
        for (int t = 0; t < tileCount; t++) {
            Tile tile = tiles[t];
            int centreVi = capVertex(nodeIndex, v, tile.centreM, tile.centreN);
            int[] ring = new int[6];
            for (int k = 0; k < 6; k++) {
                ring[k] = capVertex(nodeIndex, v, tile.cornerNodeM(k), tile.cornerNodeN(k));
            }
            for (int k = 0; k < 6; k++) {
                idx.add(centreVi);
                idx.add(ring[(k + 1) % 6]);
                idx.add(ring[k]);
            }
        }

        // Pass 3: skirt walls. For a boundary edge in OWNER ring order
        // (a = corner k, b = corner k+1), outward normal = (dz, −dx)/len.
        // Per-edge verts (no sharing with the cap, no wall dedupe) in the
        // proven pattern: (a_bot, a_top, b_top) + (a_bot, b_top, b_bot).
        // Wall uv: u world-linear in x; v runs 0 (rim) → depth/uvScale (bottom).
        final float vBot = depth / uvScale;
        for (int[] rec : boundary) {
            int aM = rec[0], aN = rec[1], bM = rec[2], bN = rec[3];
            float ax = halfX * aM, az = halfZ * aN;
            float bx = halfX * bM, bz = halfZ * bN;
            float dx = bx - ax, dz = bz - az;
            float len = (float) Math.sqrt((double) dx * dx + (double) dz * dz);
            float nx = len > 1e-9f ? dz / len : 0f;
            float nz = len > 1e-9f ? -dx / len : 0f;
            float uA = ax / uvScale, uB = bx / uvScale;
            int aTop = wallVert(v, aM, aN, 0f, nx, nz, uA, 0f);
            int bTop = wallVert(v, bM, bN, 0f, nx, nz, uB, 0f);
            int aBot = wallVert(v, aM, aN, -depth, nx, nz, uA, vBot);
            int bBot = wallVert(v, bM, bN, -depth, nx, nz, uB, vBot);
            idx.add(aBot); idx.add(aTop); idx.add(bTop);
            idx.add(aBot); idx.add(bTop); idx.add(bBot);
        }
        pondVerts = toFloats(v);
        pondIndices = toInts(idx);
    }

    /* ── mesh plumbing ── */

    private static long nodeKey(int m, int n) {
        return ((long) m << 32) | (n & 0xFFFFFFFFL);
    }

    /**
     * Cap vertex (deduped by node key): position = ONE product half·(m or n),
     * normal (0,1,0), uv world-linear, y = 0. Welding is EXACT — whichever
     * neighbour reaches the node second reuses the first one's vertex index.
     */
    private int capVertex(HashMap<Long, Integer> nodeIndex, List<Float> v, int m, int n) {
        long key = nodeKey(m, n);
        Integer existing = nodeIndex.get(key);
        if (existing != null) return existing;
        float x = halfX * m, z = halfZ * n;
        int vi = v.size() / 8;
        v.add(x); v.add(0f); v.add(z);
        v.add(0f); v.add(1f); v.add(0f);
        v.add(x / uvScale); v.add(z / uvScale);
        nodeIndex.put(key, vi);
        return vi;
    }

    /** Wall vertex — own slot per edge corner (like the proven prism walls). */
    private int wallVert(List<Float> v, int m, int n, float y,
                         float nx, float nz, float u, float vv) {
        float x = halfX * m, z = halfZ * n;
        int vi = v.size() / 8;
        v.add(x); v.add(y); v.add(z);
        v.add(nx); v.add(0f); v.add(nz);
        v.add(u);  v.add(vv);
        return vi;
    }

    private static float[] toFloats(List<Float> list) {
        float[] out = new float[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = list.get(i);
        return out;
    }

    private static int[] toInts(List<Integer> list) {
        int[] out = new int[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = list.get(i);
        return out;
    }

    // ── Mesh accessors ───────────────────────────────────────────────────────

    /** Top-cap mesh vertices, Mesh layout: 8 floats per vertex. Read-only. */
    public float[] getCapVerts() { return capVerts; }
    public int[] getCapIndices() { return capIndices; }
    public int getCapIndexCount() { return capIndices.length; }
    public int getCapVertexCount() { return capVerts.length / 8; }
    /** Pond mesh vertices (cap + skirt), Mesh layout: 8 floats per vertex. Read-only. */
    public float[] getPondVerts() { return pondVerts; }
    public int[] getPondIndices() { return pondIndices; }
    public int getPondIndexCount() { return pondIndices.length; }
    public int getPondVertexCount() { return pondVerts.length / 8; }
}