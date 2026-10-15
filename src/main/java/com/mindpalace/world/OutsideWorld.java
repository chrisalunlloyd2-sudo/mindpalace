package com.mindpalace.world;

import com.mindpalace.render.Renderer;
import org.joml.Vector3f;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * OutsideWorld — the expanded open world in front of the palace (Phase I).
 *
 * ~300×250m of procedurally-generated terrain: a Fibonacci forest (deciduous
 * + evergreen), a large traveling-wave lake, a mansion (player home), a
 * hospital, a program factory (8 language wings), a massive TOC tree of
 * knowledge, towns with teleporters, model shops, and 15 AI-themed
 * inventions. Seasons follow the real calendar; weather is a deterministic
 * seasonal clock (live-weather hook reserved).
 *
 * Everything is chunked (reuse WorldBuilder.chunkVisibleAt) so the Intel HD
 * 510 only draws what's near the camera — a 100× bigger world costs nothing.
 */
public class OutsideWorld {

    // ── World bounds (100× the old 30×25m patch) ──
    public static final float MIN_Z = -300f;   // far edge (deepest)
    public static final float MAX_Z = -30f;    // near edge (palace side)
    public static final float HALF_W = 150f;   // ±150m across

    private static final float GOLDEN = 2.399963f;  // golden angle (rad) = 137.5°

    // ── Season / weather (deterministic, real calendar) ──
    public enum Season { WINTER, SPRING, SUMMER, AUTUMN }
    public enum Weather { CLEAR, RAIN, SNOW }

    public static Season season() {
        int m = LocalDate.now().getMonthValue();
        if (m == 12 || m <= 2) return Season.WINTER;
        if (m <= 5) return Season.SPRING;
        if (m <= 8) return Season.SUMMER;
        return Season.AUTUMN;
    }

    public static Weather weather() {
        // Deterministic pseudo-weather from day-of-year (no network). A live
        // weather API can replace this later without touching the renderer.
        int doy = LocalDate.now().getDayOfYear();
        int bucket = doy % 7;
        Season s = season();
        if (s == Season.WINTER && bucket == 0) return Weather.SNOW;
        if (s != Season.WINTER && (bucket == 1 || bucket == 2)) return Weather.RAIN;
        return Weather.CLEAR;
    }

    // ── Feature anchor positions (fixed, so towns/teleporters are stable) ──
    private final Vector3f mansionPos = new Vector3f(20f, 0f, -180f);
    // Mansion shell dims (renderMansion + Player interior clamp must agree).
    public static final float MANSION_W = 22f;
    public static final float MANSION_D = 16f;
    private final Vector3f hospitalPos = new Vector3f(-60f, 0f, -120f);
    private final Vector3f factoryPos = new Vector3f(80f, 0f, -150f);
    private final Vector3f tocTreePos = new Vector3f(0f, 0f, -250f);
    private final Vector3f lakeCenter = new Vector3f(40f, 0f, -210f);

    // ── Solid-world colliders (H13b, step 60) ─────────────────────────────
    // Every building is an axis-aligned box the player cannot walk through.
    // Houses keep a doorway gap on their -Z face (the door faces -Z).
    public static final class Box {
        public final float minX, maxX, minZ, maxZ;
        public Box(float cx, float cz, float w, float d) {
            this.minX = cx - w / 2f; this.maxX = cx + w / 2f;
            this.minZ = cz - d / 2f; this.maxZ = cz + d / 2f;
        }
    }
    private final java.util.List<Box> colliders = new java.util.ArrayList<>();
    private final java.util.List<Box> houses = new java.util.ArrayList<>(); // doorway boxes

    /**
     * Register the three static building boxes (mansion, hospital, factory).
     * Idempotent. Previously these were registered lazily during their first
     * CULLED render pass (colliders.size()==N guards in the render methods),
     * so a spawn validation at init time saw an empty collider list and the
     * mansion spawn (t_b2e9bc3f: camera landed on the mansion's +Z face) went
     * unvalidated. Call before any gameplay or validation reads colliders;
     * the render paths also call it, so registration order no longer matters.
     */
    private boolean staticCollidersRegistered = false;
    public void registerStaticColliders() {
        if (staticCollidersRegistered) return;
        staticCollidersRegistered = true;
        addBox(colliders, mansionPos.x, mansionPos.z, 22f, 16f);   // matches renderMansion shell
        addBox(colliders, hospitalPos.x, hospitalPos.z, 18f, 12f); // matches renderHospital
        addBox(colliders, factoryPos.x, factoryPos.z, 30f, 20f);   // matches renderFactory
    }

    /** Register a solid building box. Called during scene build. */
    private void addBox(java.util.List<Box> list, float cx, float cz, float w, float d) {
        list.add(new Box(cx, cz, w, d));
    }

    /** All building colliders (walls only — houses' doorways handled apart). */
    public java.util.List<Box> getColliders() { return colliders; }

    /** Town houses: collider minus doorway — walk-in allowed only through the door. */
    public java.util.List<Box> getHouses() { return houses; }

    /** True if (x,z) is inside any solid collider (with player radius pad). */
    public boolean insideSolid(float x, float z, float r) {
        for (Box b : colliders)
            if (x > b.minX - r && x < b.maxX + r && z > b.minZ - r && z < b.maxZ + r) return true;
        return false;
    }

    /**
     * House test with doorway allowance: inside the house footprint is solid
     * EXCEPT a 1.6m-wide doorway strip centered on the house's front (-Z) face.
     */
    public boolean insideHouseSolid(float x, float z, float r) {
        for (Box b : houses) {
            if (x > b.minX - r && x < b.maxX + r && z > b.minZ - r && z < b.maxZ + r) {
                boolean doorStrip = Math.abs(x - (b.minX + b.maxX) / 2f) < 0.8f + r
                                    && z < b.minZ + 0.9f && z > b.minZ - 0.3f;
                if (!doorStrip) return true;
            }
        }
        return false;
    }

    /** Mansion (player home) position — player + crystals spawn here. */
    public Vector3f getMansionPos() { return mansionPos; }
    public Vector3f getLakeCenter() { return lakeCenter; }
    /** TOC tree of knowledge position — walk up to retrieve system data. */
    public Vector3f getTocTreePos() { return tocTreePos; }

    // ── Model shops (linked to DePIN) ────────────────────────────────────────

    /** A shop stall the player can interact with to spend DePIN credits. */
    public static final class Shop {
        public final String name;
        public final String description;
        public final double cost;
        public final Vector3f pos;

        Shop(String name, String description, double cost, Vector3f pos) {
            this.name = name;
            this.description = description;
            this.cost = cost;
            this.pos = pos;
        }
    }

    /** The 5 model-upgrade shops positioned alongside the mansion. */
    public Shop[] getShops() {
        return new Shop[]{
            new Shop("RAG",        "Retrieval-Augmented Generation — faster model recall", 15.0, new Vector3f(mansionPos.x - 20f + 0f * 8f, 0f, mansionPos.z + 14f)),
            new Shop("KG Node",    "Knowledge Graph node — store facts the agents learn",  20.0, new Vector3f(mansionPos.x - 20f + 1f * 8f, 0f, mansionPos.z + 14f)),
            new Shop("Deps",       "Dependency analysis — smarter code reviews",           10.0, new Vector3f(mansionPos.x - 20f + 2f * 8f, 0f, mansionPos.z + 14f)),
            new Shop("LoRA",       "LoRA adapter slot — swappable skill weights",          30.0, new Vector3f(mansionPos.x - 20f + 3f * 8f, 0f, mansionPos.z + 14f)),
            new Shop("Router",     "ModelRouter — complexity-based model tier routing",    25.0, new Vector3f(mansionPos.x - 20f + 4f * 8f, 0f, mansionPos.z + 14f))
        };
    }

    /** Returns the nearest shop within range (meters), or -1 if none are close. */
    public int nearestShopIndex(float px, float pz, float range) {
        int best = -1;
        float bestDist = range * range;
        for (int i = 0; i < 5; i++) {
            Shop s = getShops()[i];
            float dx = px - s.pos.x;
            float dz = pz - s.pos.z;
            float d2 = dx * dx + dz * dz;
            if (d2 < bestDist) {
                bestDist = d2;
                best = i;
            }
        }
        return best;
    }

    // Camera position (set each render) for chunk culling.
    private float camX, camZ;

    private boolean chunkVisible(float x, float z) {
        return WorldBuilder.chunkVisibleAt(x, z, camX, camZ);
    }

    /** Render the whole outside world. floorY = ground Y, camX/camZ = camera. */
    public void render(Renderer r, float floorY, float camX, float camZ, float time) {
        this.camX = camX;
        this.camZ = camZ;
        Season s = season();
        Weather w = weather();
        boolean night = isNight();

        renderGround(r, floorY, s, w);
        renderLake(r, floorY, time);
        // Steps 71+73: path lanterns (night-aware) + firefly scouts looping the tree ring
        renderPathLanterns(r, floorY, night);
        // H23 (step 74): rest benches + repo-name signposts at the path forks
        renderBenchesAndSignposts(r, floorY, night);
        renderForest(r, floorY, time, s);
        renderFlowers(r, floorY, time, s);
        if (night) renderFireflies(r, floorY, time);
        renderMansion(r, floorY, night);
        renderHospital(r, floorY, night);
        renderFactory(r, floorY, night);
        renderTocTree(r, floorY, time, night);
        renderTowns(r, floorY, night, time);
        renderModelShops(r, floorY, night);
        renderInventions(r, floorY, time, night);
    }

    private boolean isNight() {
        int h = TimeClock.hour();
        return h < 6 || h >= 20;
    }

    // ── Ground ──────────────────────────────────────────────────────────────

    private void renderGround(Renderer r, float floorY, Season s, Weather w) {
        float cx = 0f, cz = (MIN_Z + MAX_Z) / 2f;
        float wd = HALF_W * 2f, dp = MAX_Z - MIN_Z;
        // Base grass (or snow in winter)
        int groundTex = (s == Season.WINTER) ? Renderer.TEX_WHITE : Renderer.TEX_GRASS;
        r.drawCube(new Vector3f(cx, floorY - 0.1f, cz), new Vector3f(wd, 0.2f, dp), groundTex);

        // Seasonal tint overlay — a thin translucent-ish color wash
        float[] tint = seasonTint(s);
        // H22 (step 72): weather-forest coupling — rain darkens the wash
        // (soaked ground), snow whitens it; CLEAR passes through untouched.
        if (w == Weather.RAIN) { tint[0] *= 0.55f; tint[1] *= 0.60f; tint[2] *= 0.62f; }
        else if (w == Weather.SNOW) { tint[0] = tint[0] * 0.4f + 0.6f; tint[1] = tint[1] * 0.4f + 0.6f; tint[2] = tint[2] * 0.4f + 0.6f; }
        r.drawCubeColor(new Vector3f(cx, floorY - 0.05f, cz),
            new Vector3f(wd, 0.02f, dp), tint[0], tint[1], tint[2]);

        // H18 (step 65): cobblestone paths — the 5 radial strips are now
        // cobbled: base dirt bed + cobble cap (alternating grey cubes per
        // segment, deterministic offset → same stones every boot). Reads as
        // real paving from the palace entrance out to the forest ring.
        for (int i = 0; i < 5; i++) {
            float px = (i - 2) * 30f;
            r.drawCubeColor(new Vector3f(px, floorY - 0.08f, -80f),
                new Vector3f(2.5f, 0.02f, 60f), 0.45f, 0.35f, 0.22f);
            // Cobble cap: 12 segments of alternating greys per strip
            for (int seg = 0; seg < 12; seg++) {
                float sz = -110f + seg * 5f;
                if (!chunkVisible(px, sz)) continue;
                float g = ((i + seg) % 2 == 0) ? 0.52f : 0.44f;
                r.drawCubeColor(new Vector3f(px, floorY - 0.04f, sz),
                    new Vector3f(2.1f, 0.03f, 4.6f), g, g, g + 0.02f);
            }
        }
    }

    private float[] seasonTint(Season s) {
        switch (s) {
            case WINTER: return new float[]{0.95f, 0.96f, 1.0f};
            case SPRING: return new float[]{0.55f, 0.85f, 0.45f};
            case AUTUMN: return new float[]{0.85f, 0.55f, 0.25f};
            default:     return new float[]{0.35f, 0.65f, 0.30f};
        }
    }

    // ── Lake ─────────────────────────────────────────────────────────────────

    private void renderLake(Renderer r, float floorY, float time) {
        // STEP 95v8: the lake's night-purple look was calibrated in v7 under
        // the HOME light with its own draw-level night math (albedo drop,
        // moon path, budget). It must NOT ride the phase light — wrap the
        // pass so every phase draws byte-identical to v7.
        r.pushHomeLight();
        float lx = lakeCenter.x, lz = lakeCenter.z;
        // STEP 92b: hex-cell traveling-wave surface. The old surface was a grid
        // of SQUARE wave cubes — the lake read as a square pool. A pointy-top
        // hex grid (vertex along +Z) tiles the elliptical basin in hexagons.
        // Tiling pitch (Architect fix: "no grass in between"): the mesh's unit
        // circumradius is 0.5, so cap circumradius = 0.5·hr and the seamless
        // pitch is x = √3·0.5·hr (flat-side to flat-side), z = 0.75·hr
        // (1.5·circumradius) with odd rows offset half the x pitch. The old
        // √3·hr/1.5·hr spacing assumed a unit circumradius → every cell was
        // spaced twice its width and grass showed through between all hexes.
        final float hr = 2.4f;                       // tile scale (cap circumradius 0.5·hr = 1.2)
        final float xStep = (float) Math.sqrt(3.0) * 0.5f * hr;
        final float zStep = 0.75f * hr;
        // STEP 93: rim cull fix — cell centers up to half a pitch outside the
        // ellipse still carry bodies overlapping the rim. Testing the center
        // against the exact ellipse skipped those and let the opaque grass
        // plane show through as green hairlines at the shoreline.
        final float rxGrown = 30f + xStep, rzGrown = 20f + zStep;
        // STEP 94: foam is a fixed thin waterline band (see loop body).
        int row = 0;
        for (float wz = lz - 24f; wz <= lz + 24f; wz += zStep, row++) {
            float xOff = (row % 2 != 0) ? xStep * 0.5f : 0f;
            for (float wx = lx - 32f + xOff; wx <= lx + 32f; wx += xStep) {
                // inside lake grown by one cell pitch → rim bodies kept
                float edx = wx - lx, edz = wz - lz;
                float eOuter = (edx * edx) / (rxGrown * rxGrown) + (edz * edz) / (rzGrown * rzGrown);
                if (eOuter >= 1f) continue;
                // true-ellipse distance for depth + foam banding
                float eTrue = (edx * edx) / (30f * 30f) + (edz * edz) / (20f * 20f);
                if (!chunkVisible(wx, wz)) continue;
                // One deep-skirted column per cell: the cap rides the same
                // traveling wave family. STEP 94v3: medium swell ±0.14 — the
                // v2 ±0.20 exposed glowing side walls as green seams between
                // cells at grazing night light.
                float h = 0.055f * (float) Math.cos(wx * 0.22f + time * 1.6f)
                        + 0.045f * (float) Math.cos(wz * 0.30f - time * 1.2f)
                        + 0.030f * (float) Math.sin((wx + wz) * 0.22f + time * 0.8f);
                // Shallow-water damping: the swell tapers to 15% over the
                // outer quarter of the lake — rim cells stay near waterline
                // level (no wave walls against the sand berm; real
                // shorelines calm), deep cells keep the full roll.
                float damp = eTrue > 0.75f ? Math.max(1f - 3.4f * (eTrue - 0.75f), 0.15f) : 1f;
                h *= damp;
                // STEP 94v3 (anti "mint floor") — the v2 pale-rim gradient
                // was read from the shoreline camera: most visible cells sit
                // at MID eTrue → pastel mint everywhere, no dark anywhere.
                // v3 inverts it: water is DEEP everywhere (navy→teal only by
                // depth), the SKY reflection cue is distance-driven (far
                // water pales toward the horizon — a real Fresnel cue near
                // the camera is unnecessary), and night drops albedo hard.
                // STEP 94v4 (anti "white in the water") — Architect saw white
                // fields on the live v3 lake at night. Root causes, all AFTER
                // the night-albedo drop so the dimming never touched them:
                // (1) glitter cells snapped to full white and at unlucky
                // swell phases merged into connected white patches (Pillow:
                // 2.15% achromatic-white in a fresh tour vs 0.47% in r6 —
                // phase-dependent); (2) waterline foam (0.88,0.98,1.0) read
                // as white dashes around the shore; (3) the far-horizon sky
                // wash paled past the night dim. v4 removes every white
                // writer: foam → moonlit seafoam, glitter → additive
                // cyan-silver shimmer (capped, never a snap), sky wash →
                // steel blue BEFORE the dim. All writers now sit after the
                // night drop so night stays dark-coherent. The spectacular
                // layer: a moon-path specular corridor down the lake
                // meridian (the classic moonlight streak, strongest at
                // night) plus deep hue travel — troughs toward indigo,
                // crests toward cerulean — so the surface undulates in
                // COLOR, not brightness blobs.
                float depthT = (float) Math.pow(Math.min(eTrue, 1f), 0.7f);
                float rC = 0.07f + 0.10f * depthT;      // deep navy → teal
                float gC = 0.22f + 0.22f * depthT;
                float bC = 0.40f + 0.16f * depthT;
                // wave light/dark — k clamps h to [0,1] around the mean.
                // v4 hue travel: troughs dip toward indigo, crests lift
                // toward cerulean — the lake rolls in hue, not brightness.
                float k = Math.min(Math.max(h * 3.2f + 0.5f, 0f), 1f);
                rC += -0.10f * (1f - k) + 0.02f * k;
                gC += -0.10f * (1f - k) + 0.10f * k;
                bC += -0.05f * (1f - k) + 0.14f * k;
                // sky reflection with distance — starts at 8 m, 70% by 30 m.
                // v4: target is DAY/NIGHT aware — day keeps the v3 pale-steel
                // horizon fade (v3's day look was never flagged), night uses
                // darker steel blue BEFORE the dim (the night wash must not
                // glow against a night sky).
                float dCam = (float) Math.sqrt((wx - camX) * (wx - camX) + (wz - camZ) * (wz - camZ));
                float skyBlend = 0.70f * Math.min(Math.max((dCam - 8f) / 22f, 0f), 1f);
                boolean nightNow = isNight();
                float skyR = nightNow ? 0.30f : 0.62f;
                float skyG = nightNow ? 0.50f : 0.78f;
                float skyB = nightNow ? 0.66f : 0.90f;
                rC += (skyR - rC) * skyBlend;
                gC += (skyG - gC) * skyBlend;
                bC += (skyB - bC) * skyBlend;
                // Night albedo drop (tour + real evening play): kill the
                // pastel brightness so dark blue stays dark blue. v4: every
                // writer below this line inherits the dim — the v3 ordering
                // let foam and glitter bypass it entirely.
                if (isNight()) { rC *= 0.55f; gC *= 0.60f; bC *= 0.62f; }
                // v4 moon path: one-lobed Gaussian down the lake meridian
                // (x = lx), 18 m 1/e half-width. Viewer-independent, so the
                // tour and every shore see the same classic streak. Night
                // intensity ×0.95, day ×0.25. Additive on a dimmed base —
                // corridor cells land ~0.2-0.4 per channel, never near
                // white. A slow phase roll makes the path breathe. v4c
                // synthesis: r7's base amplitude read "subtle", r8's wide
                // boost read "milky" — so the base returns to r7 strength
                // and the punch moves into a NARROW hot core (~2 m
                // half-width, the true moon column): bright where the eye
                // looks, dark navy everywhere else.
                float mpath = (float) Math.exp(-((wx - lx) * (wx - lx)) * 0.00155f);
                float mNight = isNight() ? 0.95f : 0.25f;
                float mPhase = 0.85f + 0.15f * (float) Math.sin(time * 0.9f + wz * 0.12f);
                rC += 0.18f * mNight * mpath * mPhase;
                gC += 0.22f * mNight * mpath * mPhase;
                bC += 0.26f * mNight * mpath * mPhase;
                // v4d hot core: narrow Gaussian ON TOP of the subtle base
                // path — but windowed by viewer distance. r9 proved the
                // lesson: a viewer-independent core lights the cells under
                // the camera, and near-field perspective magnification
                // turns a modest addend into a 14%-white ice field. A
                // moon streak physically lives on the FAR water: fade the
                // core in from 6 m, full by 15 m. Near field stays dark
                // navy; the streak reads mid-lake → horizon.
                float core = (float) Math.exp(-((wx - lx) * (wx - lx)) * 0.070f);
                float vWin = Math.min(Math.max((dCam - 6f) / 9f, 0f), 1f);
                float cAmp = mNight * mPhase;
                // v4e: r10 was white-free but the streak read as a thread —
                // amplitude up into the tinted ceiling (never white) and
                // width 0.090→0.070 so the column reads at distance.
                rC += 0.40f * core * cAmp * vWin;
                gC += 0.52f * core * cAmp * vWin;
                bC += 0.62f * core * cAmp * vWin;
                if (eTrue > 0.965f) {
                    // Thin waterline foam: fixed ~3.5%-of-radius band
                    // (≈0.5 m wide) gently breathing. v4: moonlit SEAFOAM
                    // (green-leaning) instead of the v3 near-white ring,
                    // with its own night palette — the dashes stay but
                    // they read as foam on a night sea, not white paint.
                    float breathe = 0.92f + 0.08f * (float) Math.sin(time * 1.3f + (wx + wz) * 0.15f);
                    if (isNight()) {
                        rC = 0.21f * breathe; gC = 0.45f * breathe; bC = 0.42f * breathe;
                    } else {
                        rC = 0.34f * breathe; gC = 0.72f * breathe; bC = 0.64f * breathe;
                    }
                } else {
                    // Sun glitter v4: sparkle stays pointwise but it now
                    // MODULATES (additive cyan-silver flash, capped, never
                    // a snap) instead of snapping to (0.96,0.99,1.0). The
                    // v3 snap values are exactly the achromatic-white
                    // patches the Architect caught. v4b: caps stay tinted
                    // (r well below g/b) so a flash reads unmistakably
                    // pale-cyan; inside the NARROW hot core the product
                    // threshold drops to 0.70 so the streak itself
                    // sparkles denser — light on moving water.
                    float g1 = (float) Math.sin(wx * 2.1f + wz * 1.7f + time * 2.7f);
                    float g2 = (float) Math.sin(wz * 1.6f - wx * 1.3f - time * 2.1f);
                    if (k > 0.55f && (g1 * g2 > 0.82f || (core * vWin > 0.50f && g1 * g2 > 0.70f))) {
                        float flash = 0.86f + 0.14f * g1;
                        rC = Math.min(rC + 0.34f * flash, 0.62f);
                        gC = Math.min(gC + 0.50f * flash, 0.78f);
                        bC = Math.min(bC + 0.55f * flash, 0.85f);
                    }
                    // Moon-path crest kiss: inside the corridor, crests
                    // lift a hair and troughs drop a hair — the moving
                    // light-ripple texture the eye reads on a real
                    // moonlit sea. v4c: back to r7's tight band (the r8
                    // wide lift helped the milk).
                    if (mpath > 0.35f) {
                        float lift = 0.10f * (k - 0.5f) * (mpath - 0.35f) * 2.9f;
                        rC += lift * 0.7f; gC += lift * 0.9f; bC += lift;
                    }
                }
                // v4d hard tinted ceiling: the structural anti-white
                // guarantee. No water cell may exceed these post-dim
                // values (r < g < b, cyan family), so even at full flash
                // the renderer can never clamp a pale vertex to
                // screen-white. Achromatic white on the lake is now
                // geometrically impossible.
                rC = Math.min(rC, 0.76f);
                gC = Math.min(gC, 0.86f);
                bC = Math.min(bC, 0.92f);
                // STEP 94v5 (bloom white-kill — the REAL root cause): the
                // v4 vertex ceiling (0.76,0.86,0.92) sits at luminance ~0.85,
                // INSIDE BloomEffect's bright pass (smoothstep 0.6→0.9).
                // Composite then adds the blurred scene back at ~0.95
                // intensity, so every ceiling-hit cell self-adds to ~1.7 and
                // clips to screen-white. Only cells subtending enough screen
                // area (standing on the shore, free-look) keep the blur
                // self-contained; the fixed tour camera saw thin diluted
                // bands — which is why both r10/r11 passed clean and the
                // Architect still saw white hexes live. Fix: budget the
                // final cell luminance BELOW the bright-pass knee (0.58) at
                // night — water contributes nothing to bloom, so no
                // composite can lift it to white. The brightness budget
                // moves into blue: the streak reads as a vivid cerulean
                // column, still the brightest thing on the lake, never
                // achromatic. Night-only: the day steel-haze look is
                // untouched (never flagged) and its bloom is a feature.
                // STEP 94v6 (all-blue tightening): the v5 budget 0.58 left a
                // band of streak cells at lum 0.55-0.58 (ceiling-family 0.76/
                // 0.86/0.92 scaled down) that still reads as PALE MINT at
                // grazing bearings (orbit-v5c stations 21-23, 32, 35-36).
                // Architect bar: hex water ALL BLUE. Budget now 0.50 —
                // every night water cell lands in the saturated-teal band;
                // the streak stays the brightest blue on the lake, can no
                // longer read as pale/white anywhere. Night-only; day
                // steel-haze bloom untouched.
                if (isNight()) {
                    // STEP 94v7 (Architect: slight purple hue): night water
                    // drifts toward indigo-violet — green dips, blue+red lift.
                    // Order matters: shift FIRST, v6 budget LAST, so every
                    // cell is still clamped ≤ lum 0.50 (r<g<b cyan family,
                    // all-blue bar, and purple can never rebloom a cell).
                    rC += 0.02f * bC;
                    gC *= 0.94f;
                    bC += 0.08f * (1f - bC);
                    float lum = 0.2126f * rC + 0.7152f * gC + 0.0722f * bC;
                    if (lum > 0.50f) {
                        float s = 0.50f / lum;
                        rC *= s; gC *= s; bC *= s;
                    }
                }
                r.drawHexPrismColor(new Vector3f(wx, floorY + 0.22f + h, wz),
                    new Vector3f(hr * 1.06f, 0.60f, hr * 1.06f),
                    rC, gC, bC);
            }
        }
        // Sandy shoreline — hex cells on the SAME grid, just outside the water
        // ellipse, so shore + water share one coherent hex tessellation.
        row = 0;
        for (float wz = lz - 27f; wz <= lz + 27f; wz += zStep, row++) {
            float xOff = (row % 2 != 0) ? xStep * 0.5f : 0f;
            for (float wx = lx - 35f + xOff; wx <= lx + 35f; wx += xStep) {
                // outside the GROWN water ellipse (same boundary as water cells)
                // but inside the sand ring → sand never underlays water cells
                float sdx = wx - lx, sdz = wz - lz;
                float sInner = (sdx * sdx) / (rxGrown * rxGrown) + (sdz * sdz) / (rzGrown * rzGrown);
                if (sInner < 1f) continue;
                float sOuter = (sdx * sdx) / (34.2f * 34.2f) + (sdz * sdz) / (24.2f * 24.2f);
                if (sOuter >= 1f) continue;
                if (!chunkVisible(wx, wz)) continue;
                r.drawHexPrismColor(new Vector3f(wx, floorY + 0.02f, wz),
                    new Vector3f(hr, 0.08f, hr), 0.62f, 0.55f, 0.40f);
            }
        }
        r.popLight(); // STEP 95v8: end lake/home-pair bypass
    }

    // ── Forest (Fibonacci phyllotaxis, deciduous + evergreen) ──

    private void renderForest(Renderer r, float floorY, float time, Season s) {
        // H16 (step 63): forest fill 220 → 660 (×3). Density falls off
        // radially from the mansion (parkland near, wild far), spread across
        // the full world width in three lobes so no region is bare.
        int count = 660;
        for (int ti = 0; ti < count; ti++) {
            // Golden-angle spiral → natural, non-clustering scatter
            float ang = ti * GOLDEN;
            float rad = 4f + 2.2f * (float) Math.sqrt(ti);
            float tx = (float) Math.cos(ang) * rad * 1.4f;
            float tz = -90f + (float) Math.sin(ang) * rad;
            // Step 63: radial density falloff — near-mansion ring is parkland
            // (skip 2 of 3), the far field is dense. Same seed order → same
            // world every boot (deterministic E2E).
            float mansionDist = (float) Math.sqrt((tx - mansionPos.x) * (tx - mansionPos.x)
                                                + (tz - mansionPos.z) * (tz - mansionPos.z));
            if (mansionPos.z - tz < 40f && (ti % 3 != 0)) continue; // parkland thinning
            // Keep inside bounds, avoid the lake and feature footprints
            if (Math.abs(tx) > HALF_W - 2f) continue;
            if (tz < MIN_Z + 2f || tz > MAX_Z - 2f) continue;
            if (inLake(tx, tz)) continue;
            if (near(tx, tz, mansionPos, 14f)) continue;
            if (near(tx, tz, hospitalPos, 12f)) continue;
            if (near(tx, tz, factoryPos, 16f)) continue;
            if (near(tx, tz, tocTreePos, 20f)) continue;
            if (!chunkVisible(tx, tz)) continue;

            // Distance-based LOD: near trees get full detail (branches + 34
            // leaves), far trees collapse to a trunk + a few canopy blobs.
            // This is the single biggest draw-call win on the Intel HD 510 —
            // a full tree is ~46 cubes, a far tree is ~5.
            float dx = tx - camX, dz = tz - camZ;
            float dist2 = dx * dx + dz * dz;
            int lod = dist2 < 12f * 12f ? 0 : dist2 < 28f * 28f ? 1 : 2;

            // H16 (step 63 completion): three species — every 7th tree is
            // deadwood (bare gnarled trunk, catches the eye), every 4th (of
            // the rest) is evergreen conifer, the majority deciduous. Same
            // seed order → same world every boot (deterministic E2E).
            if (ti % 7 == 0) renderDeadwood(r, tx, floorY, tz, ti, lod);
            else if (ti % 4 == 0) renderEvergreen(r, tx, floorY, tz, ti, lod);
            else renderDeciduous(r, tx, floorY, tz, ti, s, lod);
        }
        renderForestFloor(r, floorY, time, s);
    }

    /** H17 (step 64): forest floor dressing — grass tufts, fallen logs,
     *  rocks. Instanced-cheap: 1 cube per tuft, 3 per log, 1 per rock, all
     *  on the same deterministic seed order as the trees so the world is
     *  identical every boot. Draw calls scale with chunk visibility (LOD
     *  culls the far half entirely). */
    private void renderForestFloor(Renderer r, float floorY, float time, Season s) {
        int count = 220; // 110 tufts + 55 logs + 55 rocks by stride
        for (int i = 0; i < count; i++) {
            float ang = i * GOLDEN + 0.9f; // offset from the tree spiral
            float rad = 6f + 2.6f * (float) Math.sqrt(i);
            float fx = (float) Math.cos(ang) * rad * 1.4f;
            float fz = -90f + (float) Math.sin(ang) * rad;
            if (Math.abs(fx) > HALF_W - 2f) continue;
            if (fz < MIN_Z + 2f || fz > MAX_Z - 2f) continue;
            if (inLake(fx, fz)) continue;
            if (near(fx, fz, mansionPos, 16f) || near(fx, fz, hospitalPos, 12f)
                || near(fx, fz, factoryPos, 16f) || near(fx, fz, tocTreePos, 20f)) continue;
            if (!chunkVisible(fx, fz)) continue;

            if (i % 4 == 0) {
                // Fallen log — lying cylinder-ish (3 cubes: 2 body + 1 broken end)
                float logAng = (i % 9) * 0.7f;
                float lx = (float) Math.cos(logAng) * 1.4f, lz = (float) Math.sin(logAng) * 0.8f;
                float logY = floorY + 0.16f;
                r.drawCubeColor(new Vector3f(fx, logY, fz),
                    new Vector3f(2.0f, 0.32f, 0.42f), 0.42f, 0.30f, 0.18f);
                r.drawCubeColor(new Vector3f(fx + lx, logY + 0.06f, fz + lz),
                    new Vector3f(1.2f, 0.30f, 0.40f), 0.46f, 0.33f, 0.20f);
                r.drawCubeColor(new Vector3f(fx - 1.05f, logY - 0.04f, fz - lz * 0.4f),
                    new Vector3f(0.36f, 0.40f, 0.44f), 0.35f, 0.26f, 0.16f);
            } else if (i % 4 == 1) {
                // Rock — grey boulder pair (big + small neighbor)
                r.drawCubeColor(new Vector3f(fx, floorY + 0.14f, fz),
                    new Vector3f(0.7f, 0.4f, 0.6f), 0.45f, 0.45f, 0.46f);
                r.drawCubeColor(new Vector3f(fx + 0.6f, floorY + 0.08f, fz + 0.3f),
                    new Vector3f(0.35f, 0.24f, 0.3f), 0.40f, 0.40f, 0.42f);
            } else {
                // Grass tufts — 2 crossed blades each; winter tints them white-ish
                boolean winter = s == Season.WINTER;
                float gr = winter ? 0.75f : 0.25f, gg = winter ? 0.85f : 0.55f,
                      gb = winter ? 0.90f : 0.15f;
                r.drawCubeColor(new Vector3f(fx, floorY + 0.09f, fz),
                    new Vector3f(0.06f, 0.22f, 0.30f), gr, gg, gb);
                r.drawCubeColor(new Vector3f(fx + 0.08f, floorY + 0.07f, fz + 0.05f),
                    new Vector3f(0.26f, 0.18f, 0.06f), gr * 0.9f, gg * 0.9f, gb);
            }
        }
    }

    /** H16 3rd species: deadwood — bare gnarled trunk + stub branches. */
    private void renderDeadwood(Renderer r, float tx, float floorY, float tz, int ti, int lod) {
        float trunkH = 2.2f + (ti % 4) * 0.5f;
        float lean = 0.12f * (ti % 3 - 1); // slight deterministic lean
        r.drawCubeColor(new Vector3f(tx + lean, floorY + trunkH / 2f, tz),
            new Vector3f(0.26f, trunkH, 0.26f), 0.36f, 0.28f, 0.22f);
        if (lod > 1) return; // far LOD: trunk only
        // Gnarled stubs — golden-angle forks, bare (no canopy)
        int stubs = lod == 0 ? 3 : 2;
        for (int b = 0; b < stubs; b++) {
            float by = floorY + trunkH * (0.55f + 0.18f * b);
            float bAng = b * GOLDEN + ti * 1.3f;
            float bLen = 0.9f - 0.2f * b;
            r.drawCubeColor(new Vector3f(tx + lean + (float) Math.cos(bAng) * bLen / 2f,
                              by + bLen * 0.35f, tz + (float) Math.sin(bAng) * bLen / 2f),
                new Vector3f(0.12f, 0.7f, 0.12f), 0.32f, 0.25f, 0.19f);
        }
    }

    private void renderDeciduous(Renderer r, float tx, float floorY, float tz, int ti, Season s, int lod) {
        float trunkH = 2.6f + (ti % 5) * 0.5f;
        // Trunk + bark ridges
        r.drawCube(new Vector3f(tx, floorY + trunkH / 2f, tz),
            new Vector3f(0.34f, trunkH, 0.34f), Renderer.TEX_BARK);
        if (lod == 0) {
            for (int ridge = 0; ridge < 3; ridge++) {
                float ry = floorY + 0.5f + ridge * (trunkH * 0.3f);
                r.drawCube(new Vector3f(tx, ry, tz), new Vector3f(0.38f, 0.07f, 0.38f), Renderer.TEX_WOOD);
            }
        }
        // Visible branches — golden-angle forks, thicker and longer (near only)
        if (lod == 0) {
            int branchCount = 4;
            for (int b = 0; b < branchCount; b++) {
                float by = floorY + trunkH * (0.5f + 0.13f * b);
                float bAng = b * GOLDEN + ti * 0.7f;
                float bLen = 1.6f - 0.3f * b;
                float bdx = (float) Math.cos(bAng) * bLen;
                float bdz = (float) Math.sin(bAng) * bLen;
                r.drawCube(new Vector3f(tx + bdx * 0.5f, by + 0.3f, tz + bdz * 0.5f),
                    new Vector3f(0.16f, 0.16f, bLen), Renderer.TEX_BARK);
                // Sub-branch
                float sAng = bAng + GOLDEN;
                float sLen = bLen * 0.6f;
                r.drawCube(new Vector3f(tx + bdx + (float) Math.cos(sAng) * sLen * 0.5f,
                        by + 0.6f, tz + bdz + (float) Math.sin(sAng) * sLen * 0.5f),
                    new Vector3f(0.10f, 0.10f, sLen), Renderer.TEX_BARK);
            }
        }
        // Phyllotaxis canopy — leaf color follows season. LOD scales leaf count.
        float[] leaf = leafColor(s);
        int leaves = lod == 0 ? 34 : lod == 1 ? 12 : 4;
        float baseY = floorY + trunkH + 0.5f;
        for (int n = 0; n < leaves; n++) {
            float lang = n * GOLDEN;
            float lrad = 0.45f * (float) Math.sqrt(n + 1);
            float lx = tx + lrad * (float) Math.cos(lang);
            float lz = tz + lrad * (float) Math.sin(lang);
            float ly = baseY + 1.1f * (1f - (float) n / leaves);
            float ls = 0.40f * (1f - 0.5f * (float) n / leaves);
            r.drawCubeColor(new Vector3f(lx, ly, lz),
                new Vector3f(ls, ls, ls), leaf[0], leaf[1] + 0.08f * (n % 3), leaf[2]);
        }
    }

    private void renderEvergreen(Renderer r, float tx, float floorY, float tz, int ti, int lod) {
        float trunkH = 3.0f + (ti % 3) * 0.6f;
        r.drawCube(new Vector3f(tx, floorY + trunkH / 2f, tz),
            new Vector3f(0.30f, trunkH, 0.30f), Renderer.TEX_BARK);
        // Conifer cone — stacked shrinking green discs (Fibonacci taper)
        int tiers = lod == 0 ? 6 : lod == 1 ? 3 : 2;
        for (int t = 0; t < tiers; t++) {
            float ty = floorY + trunkH * 0.4f + t * 0.55f;
            float frac = 1f - (float) t / tiers;
            float w = 1.8f * frac;
            r.drawCubeColor(new Vector3f(tx, ty, tz),
                new Vector3f(w, 0.5f, w), 0.05f, 0.35f + 0.05f * t, 0.10f);
        }
        // Snow cap in winter
        if (season() == Season.WINTER) {
            r.drawCubeColor(new Vector3f(tx, floorY + trunkH * 0.4f + tiers * 0.55f, tz),
                new Vector3f(0.5f, 0.3f, 0.5f), 0.95f, 0.97f, 1.0f);
        }
    }

    private float[] leafColor(Season s) {
        switch (s) {
            case SPRING: return new float[]{0.15f, 0.60f, 0.20f};
            case AUTUMN: return new float[]{0.80f, 0.45f, 0.10f};
            case WINTER: return new float[]{0.35f, 0.40f, 0.35f};
            default:     return new float[]{0.10f, 0.45f, 0.12f};
        }
    }

    // ── Flowers / foliage ────────────────────────────────────────────────────

    private void renderFlowers(Renderer r, float floorY, float time, Season s) {
        if (s == Season.WINTER) return;  // no flowers in snow
        int count = 120;
        for (int i = 0; i < count; i++) {
            float ang = i * GOLDEN;
            float rad = 6f + 1.5f * (float) Math.sqrt(i);
            float fx = (float) Math.cos(ang) * rad * 1.3f;
            float fz = -100f + (float) Math.sin(ang) * rad;
            if (Math.abs(fx) > HALF_W - 1f || fz < MIN_Z + 1f || fz > MAX_Z - 1f) continue;
            if (inLake(fx, fz)) continue;
            if (!chunkVisible(fx, fz)) continue;
            // Stem + bloom (color varies by season)
            float[] c = flowerColor(s, i);
            r.drawCubeColor(new Vector3f(fx, floorY + 0.15f, fz),
                new Vector3f(0.05f, 0.3f, 0.05f), 0.15f, 0.5f, 0.15f);
            r.drawCubeColor(new Vector3f(fx, floorY + 0.35f, fz),
                new Vector3f(0.22f, 0.12f, 0.22f), c[0], c[1], c[2]);
        }
    }

    private float[] flowerColor(Season s, int i) {
        float[][] palette = s == Season.SPRING
            ? new float[][]{{0.95f,0.4f,0.6f},{0.9f,0.8f,0.2f},{0.6f,0.4f,0.9f},{1f,1f,1f}}
            : new float[][]{{0.9f,0.3f,0.3f},{0.9f,0.6f,0.2f},{0.7f,0.3f,0.7f},{1f,0.9f,0.4f}};
        return palette[i % palette.length];
    }

    // ── Fireflies (night only) ───────────────────────────────────────────────

    private void renderFireflies(Renderer r, float floorY, float time) {
        int count = 60;
        for (int i = 0; i < count; i++) {
            float ang = i * GOLDEN;
            float rad = 8f + 2f * (float) Math.sqrt(i);
            float fx = (float) Math.cos(ang) * rad * 1.2f;
            float fz = -110f + (float) Math.sin(ang) * rad;
            if (Math.abs(fx) > HALF_W - 1f || fz < MIN_Z + 1f || fz > MAX_Z - 1f) continue;
            if (!chunkVisible(fx, fz)) continue;
            // Drifting glow — position + brightness oscillate
            float drift = (float) Math.sin(time * 0.7f + i * 1.3f);
            float fy = floorY + 0.8f + 0.6f * (float) Math.sin(time * 1.1f + i);
            float glow = 0.4f + 0.6f * (0.5f + 0.5f * (float) Math.sin(time * 2.0f + i * 0.9f));
            r.drawCubeColor(new Vector3f(fx + drift * 0.5f, fy, fz),
                new Vector3f(0.10f, 0.10f, 0.10f), 0.9f * glow, 1.0f * glow, 0.4f * glow);
        }
    }

    // ── Mansion (player home — rooms for everything) ─────────────────────────

    private void renderMansion(Renderer r, float floorY, boolean night) {
        float mx = mansionPos.x, mz = mansionPos.z;
        float w = MANSION_W, d = MANSION_D, h = 7f;
        float cy = floorY + h / 2f, t = 0.3f;

        // Collider registered eagerly in registerStaticColliders() (H13b: the
        // mansion is solid; t_b2e9bc3f: registration must not depend on a
        // culled render pass).

        // Foundation
        r.drawCube(new Vector3f(mx, floorY + 0.1f, mz), new Vector3f(w, 0.2f, d), Renderer.TEX_CONCRETE);
        // Walls (stone)
        r.drawCube(new Vector3f(mx, cy, mz - d / 2f), new Vector3f(w, h, t), Renderer.TEX_WALL);
        r.drawCube(new Vector3f(mx, cy, mz + d / 2f), new Vector3f(w, h, t), Renderer.TEX_WALL);
        r.drawCube(new Vector3f(mx - w / 2f, cy, mz), new Vector3f(t, h, d), Renderer.TEX_WALL);
        r.drawCube(new Vector3f(mx + w / 2f, cy, mz), new Vector3f(t, h, d), Renderer.TEX_WALL);
        // Grand roof (peaked, two tiers)
        r.drawCube(new Vector3f(mx, floorY + h + 0.5f, mz), new Vector3f(w + 1f, 0.3f, d + 1f), Renderer.TEX_DOOR);
        r.drawCube(new Vector3f(mx, floorY + h + 1.4f, mz), new Vector3f(w * 0.6f, 0.3f, d * 0.6f), Renderer.TEX_DOOR);
        // Chimney
        r.drawCube(new Vector3f(mx + 6f, floorY + h + 2.2f, mz + 3f), new Vector3f(0.8f, 2f, 0.8f), Renderer.TEX_WOOD);
        // Grand entrance door
        r.drawCube(new Vector3f(mx, floorY + 1.6f, mz - d / 2f - 0.05f), new Vector3f(2.2f, 3.2f, 0.1f), Renderer.TEX_DOOR);
        // Windows (warm at night, cool by day) — two rows
        int winTex = night ? Renderer.TEX_NEON_AMBER : Renderer.TEX_NEON_CYAN;
        for (int i = 0; i < 4; i++) {
            float wx = mx - 7f + i * 4.5f;
            r.drawCube(new Vector3f(wx, floorY + 3.5f, mz - d / 2f - 0.05f), new Vector3f(1.4f, 1.4f, 0.05f), winTex);
            r.drawCube(new Vector3f(wx, floorY + 3.5f, mz + d / 2f + 0.05f), new Vector3f(1.4f, 1.4f, 0.05f), winTex);
        }
        // Columns at the entrance
        for (int s = -1; s <= 1; s += 2) {
            r.drawCube(new Vector3f(mx + s * 1.6f, floorY + 1.6f, mz - d / 2f - 0.3f),
                new Vector3f(0.4f, 3.2f, 0.4f), Renderer.TEX_WHITE);
        }
        // Mansion sign
        r.drawCube(new Vector3f(mx, floorY + h - 0.4f, mz - d / 2f - 0.1f),
            new Vector3f(4f, 0.4f, 0.06f), Renderer.TEX_NEON_AMBER);
    }

    // ── Hospital ─────────────────────────────────────────────────────────────

    private void renderHospital(Renderer r, float floorY, boolean night) {
        float hx = hospitalPos.x, hz = hospitalPos.z;
        float w = 18f, d = 12f, h = 6f;
        float cy = floorY + h / 2f, t = 0.3f;
        // Collider registered eagerly in registerStaticColliders() (H13b: solid)
        r.drawCube(new Vector3f(hx, floorY + 0.1f, hz), new Vector3f(w, 0.2f, d), Renderer.TEX_CONCRETE);
        r.drawCube(new Vector3f(hx, cy, hz - d / 2f), new Vector3f(w, h, t), Renderer.TEX_WHITE);
        r.drawCube(new Vector3f(hx, cy, hz + d / 2f), new Vector3f(w, h, t), Renderer.TEX_WHITE);
        r.drawCube(new Vector3f(hx - w / 2f, cy, hz), new Vector3f(t, h, d), Renderer.TEX_WHITE);
        r.drawCube(new Vector3f(hx + w / 2f, cy, hz), new Vector3f(t, h, d), Renderer.TEX_WHITE);
        r.drawCube(new Vector3f(hx, floorY + h + 0.3f, hz), new Vector3f(w + 0.5f, 0.3f, d + 0.5f), Renderer.TEX_METAL);
        // Red cross (medical) — two bars
        r.drawCube(new Vector3f(hx, floorY + h - 0.5f, hz - d / 2f - 0.05f), new Vector3f(1.2f, 0.3f, 0.05f), Renderer.TEX_BOOK_RED);
        r.drawCube(new Vector3f(hx, floorY + h - 0.5f, hz - d / 2f - 0.05f), new Vector3f(0.3f, 1.2f, 0.05f), Renderer.TEX_BOOK_RED);
        // Windows
        int winTex = night ? Renderer.TEX_NEON_AMBER : Renderer.TEX_NEON_CYAN;
        for (int i = 0; i < 5; i++) {
            float wx = hx - 7f + i * 3.5f;
            r.drawCube(new Vector3f(wx, floorY + 3f, hz - d / 2f - 0.05f), new Vector3f(1.2f, 1.2f, 0.05f), winTex);
        }
    }

    // ── Program factory (8 language wings) ───────────────────────────────────

    private void renderFactory(Renderer r, float floorY, boolean night) {
        float fx = factoryPos.x, fz = factoryPos.z;
        float w = 30f, d = 20f, h = 8f;
        float cy = floorY + h / 2f, t = 0.3f;
        // Collider registered eagerly in registerStaticColliders() (H13b: solid)
        // Industrial shell
        r.drawCube(new Vector3f(fx, floorY + 0.1f, fz), new Vector3f(w, 0.2f, d), Renderer.TEX_CONCRETE);
        r.drawCube(new Vector3f(fx, cy, fz - d / 2f), new Vector3f(w, h, t), Renderer.TEX_METAL);
        r.drawCube(new Vector3f(fx, cy, fz + d / 2f), new Vector3f(w, h, t), Renderer.TEX_METAL);
        r.drawCube(new Vector3f(fx - w / 2f, cy, fz), new Vector3f(t, h, d), Renderer.TEX_METAL);
        r.drawCube(new Vector3f(fx + w / 2f, cy, fz), new Vector3f(t, h, d), Renderer.TEX_METAL);
        // Sawtooth roof
        for (int i = 0; i < 5; i++) {
            float rx = fx - 12f + i * 6f;
            r.drawCube(new Vector3f(rx, floorY + h + 0.4f, fz), new Vector3f(5f, 0.3f, d), Renderer.TEX_METAL);
        }
        // 8 language wings — colored bays along the front, each with a sign
        String[] langs = {"Rust","Java","Python","HTML","Perl","C","BASIC","Ruby","Fortran"};
        int[][] langColors = {
            {204,102,51},{230,128,26},{77,128,230},{230,77,77},
            {102,153,230},{77,77,179},{128,128,128},{230,51,51},{102,77,204}
        };
        for (int i = 0; i < langs.length; i++) {
            float bx = fx - 13f + i * 3.2f;
            float cr = langColors[i][0] / 255f, cg = langColors[i][1] / 255f, cb = langColors[i][2] / 255f;
            r.drawCubeColor(new Vector3f(bx, floorY + 1.5f, fz - d / 2f - 0.1f),
                new Vector3f(2.6f, 3f, 0.2f), cr, cg, cb);
            // Bay sign (colored)
            r.drawCubeColor(new Vector3f(bx, floorY + 3.4f, fz - d / 2f - 0.1f),
                new Vector3f(2.2f, 0.4f, 0.06f), cr, cg, cb);
        }
        // Smokestacks
        for (int i = 0; i < 3; i++) {
            float sx = fx - 6f + i * 6f;
            r.drawCube(new Vector3f(sx, floorY + h + 2f, fz + 5f), new Vector3f(0.8f, 4f, 0.8f), Renderer.TEX_METAL);
        }
    }

    // ── TOC tree of knowledge (massive) ──────────────────────────────────────

    private void renderTocTree(Renderer r, float floorY, float time, boolean night) {
        float tx = tocTreePos.x, tz = tocTreePos.z;
        float trunkH = 14f;
        // Massive trunk
        r.drawCube(new Vector3f(tx, floorY + trunkH / 2f, tz), new Vector3f(2.5f, trunkH, 2.5f), Renderer.TEX_BARK);
        // Root buttresses
        for (int i = 0; i < 6; i++) {
            float ang = i * (float) Math.PI / 3f;
            r.drawCube(new Vector3f(tx + (float) Math.cos(ang) * 1.5f, floorY + 0.8f, tz + (float) Math.sin(ang) * 1.5f),
                new Vector3f(1.2f, 1.6f, 1.2f), Renderer.TEX_BARK);
        }
        // Huge Fibonacci canopy — many large leaf clusters
        int clusters = 40;
        for (int n = 0; n < clusters; n++) {
            float lang = n * GOLDEN;
            float lrad = 3f + 1.5f * (float) Math.sqrt(n);
            float lx = tx + lrad * (float) Math.cos(lang);
            float lz = tz + lrad * (float) Math.sin(lang);
            float ly = floorY + trunkH + 1.5f * (1f - (float) n / clusters);
            float ls = 2.2f * (1f - 0.4f * (float) n / clusters);
            r.drawCubeColor(new Vector3f(lx, ly, lz),
                new Vector3f(ls, ls, ls), 0.08f, 0.40f, 0.15f);
        }
        // Glowing "knowledge" orbs orbiting the canopy (data nodes)
        for (int i = 0; i < 12; i++) {
            float ang = time * 0.4f + i * GOLDEN;
            float rad = 8f;
            float ox = tx + (float) Math.cos(ang) * rad;
            float oz = tz + (float) Math.sin(ang) * rad;
            float oy = floorY + trunkH + 2f + 1.5f * (float) Math.sin(time + i);
            int tex = (i % 3 == 0) ? Renderer.TEX_NEON_CYAN
                    : (i % 3 == 1) ? Renderer.TEX_NEON_GREEN : Renderer.TEX_NEON_AMBER;
            r.drawCube(new Vector3f(ox, oy, oz), new Vector3f(0.5f, 0.5f, 0.5f), tex);
        }
    }

    // ── Towns + teleporters ──────────────────────────────────────────────────

    private void renderTowns(Renderer r, float floorY, boolean night, float time) {
        // Three small towns, each a cluster of houses + a teleporter pad
        float[][] townCenters = {{-90f, -160f}, {100f, -220f}, {-40f, -270f}};
        for (int t = 0; t < townCenters.length; t++) {
            float cx = townCenters[t][0], cz = townCenters[t][1];
            // 5 houses per town (Fibonacci scatter)
            for (int h = 0; h < 5; h++) {
                float ang = h * GOLDEN;
                float rad = 4f + 1.5f * (float) Math.sqrt(h);
                float hx = cx + (float) Math.cos(ang) * rad;
                float hz = cz + (float) Math.sin(ang) * rad;
                renderHouse(r, hx, floorY, hz, night, h);
            }
            // Town teleporter pad
            renderTownTeleporter(r, cx, floorY, cz, time);
        }
    }

    private void renderHouse(Renderer r, float hx, float floorY, float hz, boolean night, int h) {
        float w = 5f, d = 4f, hh = 3f;
        // Collider + doorway (H13b): registered once per house, doorway on -Z
        float hminX = hx - w / 2f, hminZ = hz - d / 2f;
        boolean registered = false;
        for (Box b : houses) if (b.minX == hminX && b.minZ == hminZ) { registered = true; break; }
        if (!registered) addBox(houses, hx, hz, w, d);
        r.drawCube(new Vector3f(hx, floorY + 0.1f, hz), new Vector3f(w, 0.15f, d), Renderer.TEX_CONCRETE);
        r.drawCube(new Vector3f(hx, floorY + hh / 2f, hz - d / 2f), new Vector3f(w, hh, 0.2f), Renderer.TEX_WALL);
        r.drawCube(new Vector3f(hx, floorY + hh / 2f, hz + d / 2f), new Vector3f(w, hh, 0.2f), Renderer.TEX_WALL);
        r.drawCube(new Vector3f(hx - w / 2f, floorY + hh / 2f, hz), new Vector3f(0.2f, hh, d), Renderer.TEX_WALL);
        r.drawCube(new Vector3f(hx + w / 2f, floorY + hh / 2f, hz), new Vector3f(0.2f, hh, d), Renderer.TEX_WALL);
        // Roof (color varies per house) — slab + parapet ring (H14, step 61)
        int[] roofTex = {Renderer.TEX_BOOK_RED, Renderer.TEX_BOOK_BLUE, Renderer.TEX_BOOK_ORANGE, Renderer.TEX_BOOK_GREY, Renderer.TEX_BOOK_YELLOW};
        r.drawCube(new Vector3f(hx, floorY + hh + 0.4f, hz), new Vector3f(w + 0.4f, 0.3f, d + 0.4f), roofTex[h % roofTex.length]);
        // Parapet: 4 thin rim walls on the roof edge (reads as a real building)
        float py = floorY + hh + 0.75f, pt = 0.15f, ph = 0.5f;
        r.drawCube(new Vector3f(hx, py, hz - d / 2f - 0.12f), new Vector3f(w + 0.4f, ph, pt), roofTex[h % roofTex.length]);
        r.drawCube(new Vector3f(hx, py, hz + d / 2f + 0.12f), new Vector3f(w + 0.4f, ph, pt), roofTex[h % roofTex.length]);
        r.drawCube(new Vector3f(hx - w / 2f - 0.12f, py, hz), new Vector3f(pt, ph, d + 0.4f), roofTex[h % roofTex.length]);
        r.drawCube(new Vector3f(hx + w / 2f + 0.12f, py, hz), new Vector3f(pt, ph, d + 0.4f), roofTex[h % roofTex.length]);
        // Chimney (every house deserves smoke someday — H19 hook point)
        r.drawCube(new Vector3f(hx + w / 2f - 0.6f, floorY + hh + 1.1f, hz + d / 4f),
            new Vector3f(0.5f, 1.0f, 0.5f), Renderer.TEX_BARK);
        // H20 (step 70): door mesh — panel + jamb + lintel + step + knob.
        // Slightly ajar (5° deterministic per house) so entrances read as real.
        float doorW = 1.1f, doorH = 2.2f;
        float ajar = 0.08f * ((h % 3) - 1); // -1, 0, +1 lean
        // Panel — offset sideways by the ajar amount (hinged left)
        r.drawCube(new Vector3f(hx - doorW / 2f + doorW / 2f * 0.15f + ajar * 0.3f, floorY + 1.1f, hz - d / 2f - 0.06f - ajar * 0.15f),
            new Vector3f(doorW * 0.85f, doorH, 0.1f), Renderer.TEX_DOOR);
        // Jamb posts + lintel
        r.drawCube(new Vector3f(hx - doorW / 2f - 0.06f, floorY + doorH / 2f, hz - d / 2f - 0.06f),
            new Vector3f(0.12f, doorH + 0.1f, 0.12f), Renderer.TEX_BARK);
        r.drawCube(new Vector3f(hx + doorW / 2f + 0.06f, floorY + doorH / 2f, hz - d / 2f - 0.06f),
            new Vector3f(0.12f, doorH + 0.1f, 0.12f), Renderer.TEX_BARK);
        r.drawCube(new Vector3f(hx, floorY + doorH + 0.1f, hz - d / 2f - 0.06f),
            new Vector3f(doorW + 0.36f, 0.12f, 0.12f), Renderer.TEX_BARK);
        // Step stone in front of the door
        r.drawCubeColor(new Vector3f(hx, floorY + 0.03f, hz - d / 2f - 0.35f),
            new Vector3f(1.4f, 0.06f, 0.5f), 0.5f, 0.5f, 0.52f);
        // Knob (bright so it catches the bloom)
        r.drawCubeColor(new Vector3f(hx + 0.28f, floorY + 1.05f, hz - d / 2f - 0.13f - ajar * 0.15f),
            new Vector3f(0.06f, 0.06f, 0.06f), 1.0f, 0.85f, 0.4f);
        int winTex = night ? Renderer.TEX_NEON_AMBER : Renderer.TEX_NEON_CYAN;
        r.drawCube(new Vector3f(hx + 1.5f, floorY + 1.8f, hz - d / 2f - 0.05f), new Vector3f(0.9f, 0.9f, 0.05f), winTex);
    }

    private void renderTownTeleporter(Renderer r, float cx, float floorY, float cz, float time) {
        float pulse = 0.5f + 0.5f * (float) Math.sin(time * 3f);
        r.drawCube(new Vector3f(cx, floorY + 0.06f, cz), new Vector3f(2f, 0.08f, 2f), Renderer.TEX_NEON_AMBER);
        r.drawCube(new Vector3f(cx, floorY + 0.1f, cz), new Vector3f(1.5f + pulse * 0.3f, 0.06f, 1.5f + pulse * 0.3f), Renderer.TEX_NEON_CYAN);
        r.drawCube(new Vector3f(cx, floorY + 0.13f, cz), new Vector3f(0.7f + pulse * 0.2f, 0.05f, 0.7f + pulse * 0.2f), Renderer.TEX_WHITE);
        // Rising beam
        float beamH = 1.5f + pulse * 1.5f;
        r.drawCube(new Vector3f(cx, floorY + 0.15f + beamH / 2f, cz), new Vector3f(0.5f, beamH, 0.5f), Renderer.TEX_NEON_CYAN);
    }

    // ── Model shops (linked to DePIN) ────────────────────────────────────────

    private void renderModelShops(Renderer r, float floorY, boolean night) {
        // A row of shops near the mansion — each sells a DePIN upgrade
        String[] shops = {"RAG","KG Node","Deps","LoRA","Router"};
        int[] shopTex = {Renderer.TEX_NEON_CYAN, Renderer.TEX_NEON_GREEN, Renderer.TEX_NEON_AMBER, Renderer.TEX_NEON_PINK, Renderer.TEX_NEON_CYAN};
        for (int i = 0; i < shops.length; i++) {
            float sx = mansionPos.x - 20f + i * 8f;
            float sz = mansionPos.z + 14f;
            // Shop stall
            r.drawCube(new Vector3f(sx, floorY + 0.1f, sz), new Vector3f(5f, 0.15f, 4f), Renderer.TEX_CONCRETE);
            r.drawCube(new Vector3f(sx, floorY + 1.5f, sz - 2f), new Vector3f(5f, 3f, 0.2f), Renderer.TEX_WOOD);
            r.drawCube(new Vector3f(sx - 2.5f, floorY + 1.5f, sz), new Vector3f(0.2f, 3f, 4f), Renderer.TEX_WOOD);
            r.drawCube(new Vector3f(sx + 2.5f, floorY + 1.5f, sz), new Vector3f(0.2f, 3f, 4f), Renderer.TEX_WOOD);
            // Awning (colored)
            r.drawCube(new Vector3f(sx, floorY + 3.2f, sz - 1f), new Vector3f(5.4f, 0.2f, 2.5f), shopTex[i]);
            // Sign
            r.drawCube(new Vector3f(sx, floorY + 3.6f, sz - 2f), new Vector3f(3f, 0.4f, 0.06f), shopTex[i]);
        }
    }

    // ── 15 AI-themed inventions ──────────────────────────────────────────────

    private void renderInventions(Renderer r, float floorY, float time, boolean night) {
        // Scattered across the world on a golden spiral, each a distinct
        // modern/AI-themed structure. 15 total.
        for (int i = 0; i < 15; i++) {
            float ang = i * GOLDEN;
            float rad = 20f + 6f * (float) Math.sqrt(i);
            float ix = (float) Math.cos(ang) * rad * 1.1f;
            float iz = -140f + (float) Math.sin(ang) * rad;
            if (Math.abs(ix) > HALF_W - 3f || iz < MIN_Z + 3f || iz > MAX_Z - 3f) continue;
            if (inLake(ix, iz)) continue;
            if (near(ix, iz, mansionPos, 12f) || near(ix, iz, hospitalPos, 12f)
                || near(ix, iz, factoryPos, 14f) || near(ix, iz, tocTreePos, 18f)) continue;
            if (!chunkVisible(ix, iz)) continue;
            renderInvention(r, ix, floorY, iz, i, time, night);
        }
    }

    private void renderInvention(Renderer r, float x, float floorY, float z, int i, float time, boolean night) {
        switch (i) {
            case 0 -> renderNeuralMonolith(r, x, floorY, z, time);
            case 1 -> renderQuantumCore(r, x, floorY, z, time);
            case 2 -> renderDataObelisk(r, x, floorY, z);
            case 3 -> renderHologramTower(r, x, floorY, z, time);
            case 4 -> renderServerShrine(r, x, floorY, z, night);
            case 5 -> renderAntennaArray(r, x, floorY, z);
            case 6 -> renderFusionRing(r, x, floorY, z, time);
            case 7 -> renderCrystalReactor(r, x, floorY, z, time);
            case 8 -> renderOrbitalLens(r, x, floorY, z, time);
            case 9 -> renderMemoryVault(r, x, floorY, z);
            case 10 -> renderSignalBeacon(r, x, floorY, z, time);
            case 11 -> renderLogicGarden(r, x, floorY, z);
            case 12 -> renderTimeSpire(r, x, floorY, z, time);
            case 13 -> renderGravityWell(r, x, floorY, z, time);
            case 14 -> renderDreamCatcher(r, x, floorY, z, time);
        }
    }

    private void renderNeuralMonolith(Renderer r, float x, float floorY, float z, float time) {
        r.drawCube(new Vector3f(x, floorY + 2.5f, z), new Vector3f(1.5f, 5f, 1.5f), Renderer.TEX_METAL);
        for (int i = 0; i < 5; i++) {
            float ly = floorY + 0.8f + i * 0.9f;
            float glow = 0.5f + 0.5f * (float) Math.sin(time * 2f + i);
            r.drawCubeColor(new Vector3f(x, ly, z + 0.76f), new Vector3f(1.2f, 0.3f, 0.05f), 0.2f, 0.8f * glow, 1f * glow);
        }
    }

    private void renderQuantumCore(Renderer r, float x, float floorY, float z, float time) {
        r.drawCube(new Vector3f(x, floorY + 1.5f, z), new Vector3f(2f, 3f, 2f), Renderer.TEX_METAL);
        float pulse = 0.5f + 0.5f * (float) Math.sin(time * 4f);
        r.drawCubeColor(new Vector3f(x, floorY + 1.5f, z), new Vector3f(1.2f + pulse * 0.4f, 1.2f + pulse * 0.4f, 1.2f + pulse * 0.4f), 0.6f, 0.2f, 1f);
    }

    private void renderDataObelisk(Renderer r, float x, float floorY, float z) {
        for (int i = 0; i < 6; i++) {
            float s = 1.6f - i * 0.2f;
            r.drawCube(new Vector3f(x, floorY + 0.4f + i * 0.7f, z), new Vector3f(s, 0.7f, s), Renderer.TEX_CONCRETE);
        }
        r.drawCube(new Vector3f(x, floorY + 4.8f, z), new Vector3f(0.4f, 0.6f, 0.4f), Renderer.TEX_NEON_CYAN);
    }

    private void renderHologramTower(Renderer r, float x, float floorY, float z, float time) {
        r.drawCube(new Vector3f(x, floorY + 2f, z), new Vector3f(0.6f, 4f, 0.6f), Renderer.TEX_METAL);
        float flicker = 0.6f + 0.4f * (float) Math.sin(time * 3f);
        r.drawCubeColor(new Vector3f(x, floorY + 4.5f, z), new Vector3f(1.5f, 1.5f, 1.5f), 0.3f, 0.9f * flicker, 1f * flicker);
    }

    private void renderServerShrine(Renderer r, float x, float floorY, float z, boolean night) {
        for (int i = 0; i < 4; i++) {
            r.drawCube(new Vector3f(x, floorY + 0.4f + i * 0.6f, z), new Vector3f(1.2f, 0.5f, 0.8f), Renderer.TEX_DOOR);
            int led = night ? Renderer.TEX_NEON_GREEN : Renderer.TEX_NEON_AMBER;
            r.drawCube(new Vector3f(x + 0.5f, floorY + 0.4f + i * 0.6f, z + 0.4f), new Vector3f(0.08f, 0.08f, 0.08f), led);
        }
    }

    private void renderAntennaArray(Renderer r, float x, float floorY, float z) {
        for (int i = 0; i < 3; i++) {
            float ax = x - 1.5f + i * 1.5f;
            r.drawCube(new Vector3f(ax, floorY + 2.5f, z), new Vector3f(0.15f, 5f, 0.15f), Renderer.TEX_METAL);
            r.drawCube(new Vector3f(ax, floorY + 5.2f, z), new Vector3f(0.5f, 0.1f, 0.5f), Renderer.TEX_NEON_AMBER);
        }
    }

    private void renderFusionRing(Renderer r, float x, float floorY, float z, float time) {
        for (int i = 0; i < 8; i++) {
            float ang = time * 1.5f + i * (float) Math.PI / 4f;
            float ox = x + (float) Math.cos(ang) * 2.5f;
            float oz = z + (float) Math.sin(ang) * 2.5f;
            r.drawCubeColor(new Vector3f(ox, floorY + 1.5f, oz), new Vector3f(0.4f, 0.4f, 0.4f), 1f, 0.5f, 0.2f);
        }
        r.drawCubeColor(new Vector3f(x, floorY + 1.5f, z), new Vector3f(1f, 1f, 1f), 1f, 0.9f, 0.6f);
    }

    private void renderCrystalReactor(Renderer r, float x, float floorY, float z, float time) {
        r.drawCube(new Vector3f(x, floorY + 1f, z), new Vector3f(2f, 2f, 2f), Renderer.TEX_METAL);
        float pulse = 0.5f + 0.5f * (float) Math.sin(time * 3f);
        r.drawCubeColor(new Vector3f(x, floorY + 1f, z), new Vector3f(0.8f + pulse * 0.3f, 0.8f + pulse * 0.3f, 0.8f + pulse * 0.3f), 0.1f, 1f * pulse, 0.5f);
    }

    private void renderOrbitalLens(Renderer r, float x, float floorY, float z, float time) {
        r.drawCube(new Vector3f(x, floorY + 1.5f, z), new Vector3f(0.3f, 3f, 0.3f), Renderer.TEX_METAL);
        float ang = time * 0.8f;
        float ox = x + (float) Math.cos(ang) * 2f;
        float oz = z + (float) Math.sin(ang) * 2f;
        r.drawCubeColor(new Vector3f(ox, floorY + 2.5f, oz), new Vector3f(0.8f, 0.8f, 0.8f), 0.5f, 0.8f, 1f);
    }

    private void renderMemoryVault(Renderer r, float x, float floorY, float z) {
        r.drawCube(new Vector3f(x, floorY + 1.2f, z), new Vector3f(2.5f, 2.4f, 2.5f), Renderer.TEX_METAL);
        r.drawCube(new Vector3f(x, floorY + 1.2f, z + 1.3f), new Vector3f(1.8f, 1.8f, 0.1f), Renderer.TEX_DOOR);
        r.drawCube(new Vector3f(x, floorY + 1.2f, z + 1.36f), new Vector3f(0.3f, 0.3f, 0.05f), Renderer.TEX_PLAQUE);
    }

    private void renderSignalBeacon(Renderer r, float x, float floorY, float z, float time) {
        r.drawCube(new Vector3f(x, floorY + 3f, z), new Vector3f(0.5f, 6f, 0.5f), Renderer.TEX_METAL);
        float pulse = 0.5f + 0.5f * (float) Math.sin(time * 2f);
        r.drawCubeColor(new Vector3f(x, floorY + 6.2f, z), new Vector3f(0.8f, 0.8f, 0.8f), 1f * pulse, 0.3f, 0.3f);
    }

    private void renderLogicGarden(Renderer r, float x, float floorY, float z) {
        // A grid of AND/OR/XOR "gates" as glowing blocks
        for (int i = 0; i < 9; i++) {
            float gx = x - 2f + (i % 3) * 2f;
            float gz = z - 2f + (i / 3) * 2f;
            int tex = (i % 3 == 0) ? Renderer.TEX_NEON_CYAN : (i % 3 == 1) ? Renderer.TEX_NEON_GREEN : Renderer.TEX_NEON_AMBER;
            r.drawCube(new Vector3f(gx, floorY + 0.3f, gz), new Vector3f(0.8f, 0.6f, 0.8f), tex);
        }
    }

    private void renderTimeSpire(Renderer r, float x, float floorY, float z, float time) {
        for (int i = 0; i < 8; i++) {
            float s = 1.2f - i * 0.12f;
            r.drawCube(new Vector3f(x, floorY + 0.5f + i * 0.8f, z), new Vector3f(s, 0.8f, s), Renderer.TEX_METAL);
        }
        float pulse = 0.5f + 0.5f * (float) Math.sin(time * 1.5f);
        r.drawCubeColor(new Vector3f(x, floorY + 7f, z), new Vector3f(0.5f, 0.5f, 0.5f), 0.8f, 0.8f * pulse, 1f * pulse);
    }

    private void renderGravityWell(Renderer r, float x, float floorY, float z, float time) {
        for (int i = 0; i < 5; i++) {
            float ang = time * 1.2f + i * GOLDEN;
            float rad = 1.5f + i * 0.4f;
            float ox = x + (float) Math.cos(ang) * rad;
            float oz = z + (float) Math.sin(ang) * rad;
            r.drawCubeColor(new Vector3f(ox, floorY + 0.5f + i * 0.3f, oz), new Vector3f(0.3f, 0.3f, 0.3f), 0.5f, 0.3f, 0.9f);
        }
    }

    private void renderDreamCatcher(Renderer r, float x, float floorY, float z, float time) {
        // A ring of floating orbs (dream fragments)
        for (int i = 0; i < 10; i++) {
            float ang = time * 0.6f + i * GOLDEN;
            float rad = 2.5f;
            float ox = x + (float) Math.cos(ang) * rad;
            float oz = z + (float) Math.sin(ang) * rad;
            float oy = floorY + 1.5f + 0.8f * (float) Math.sin(time + i);
            r.drawCubeColor(new Vector3f(ox, oy, oz), new Vector3f(0.25f, 0.25f, 0.25f), 0.8f, 0.6f, 1f);
        }
    }

    // ── Path lanterns + fireflies (steps 71 + 73) ───────────────────────────

    /** H21 (step 71): lantern posts along the cobbled paths — post + cap +
     *  glowing orb (amber at night, pale by day). One per cobble segment,
     *  alternating sides so the paving reads as lit, not lined. */
    private void renderPathLanterns(Renderer r, float floorY, boolean night) {
        for (int i = 0; i < 5; i++) {
            float px = (i - 2) * 30f;
            for (int seg = 0; seg < 12; seg += 2) { // every other cobble segment
                float lz = -110f + seg * 5f;
                float lx = px + ((seg % 2 == 0) ? 1.55f : -1.55f);
                if (!chunkVisible(lx, lz)) continue;
                // Post (bark, 1.6m) + crossbar + lantern box
                r.drawCube(new Vector3f(lx, floorY + 0.8f, lz),
                    new Vector3f(0.08f, 1.6f, 0.08f), Renderer.TEX_BARK);
                r.drawCube(new Vector3f(lx, floorY + 1.62f, lz),
                    new Vector3f(0.5f, 0.06f, 0.5f), Renderer.TEX_WOOD);
                if (night) {
                    r.drawCubeColor(new Vector3f(lx, floorY + 1.35f, lz),
                        new Vector3f(0.22f, 0.28f, 0.22f), 1.0f, 0.80f, 0.45f);
                } else {
                    r.drawCubeColor(new Vector3f(lx, floorY + 1.35f, lz),
                        new Vector3f(0.22f, 0.28f, 0.22f), 0.85f, 0.85f, 0.80f);
                }
                // Cap
                r.drawCubeColor(new Vector3f(lx, floorY + 1.68f, lz),
                    new Vector3f(0.3f, 0.05f, 0.3f), 0.3f, 0.2f, 0.12f);
            }
        }
    }

    /** H23 (step 74): rest benches + repo-name signposts at the path forks.
     *  A bench (seat + legs + backrest) sits beside every other cobble
     *  segment's fork point, and a signpost (post + two direction boards)
     *  stands where the radial paths branch. Deterministic placement —
     *  same benches every boot (E2E stable). Cheap: 6 cubes per bench,
     *  3 per signpost, chunk-culled. The repo-name text rides on the sign
     *  via GameEngine's FontRenderer (renderPathSignposts). */
    private void renderBenchesAndSignposts(Renderer r, float floorY, boolean night) {
        // Benches: one per path, ~midway along the cobble run, alternating side
        for (int i = 0; i < 5; i++) {
            float px = (i - 2) * 30f;
            float bz = -92.5f + (i % 2) * 20f;   // two rows, staggered per path
            float bx = px + ((i % 2 == 0) ? 2.6f : -2.6f);
            if (!chunkVisible(bx, bz)) continue;
            // Seat (wood plank) + 2 legs + backrest
            r.drawCube(new Vector3f(bx, floorY + 0.45f, bz),
                new Vector3f(0.5f, 0.07f, 1.8f), Renderer.TEX_WOOD);
            for (int s2 = -1; s2 <= 1; s2 += 2) {
                r.drawCube(new Vector3f(bx, floorY + 0.22f, bz + s2 * 0.42f),
                    new Vector3f(0.42f, 0.45f, 0.08f), Renderer.TEX_BARK);
            }
            r.drawCube(new Vector3f(bx + ((i % 2 == 0) ? 0.22f : -0.22f), floorY + 0.72f, bz),
                new Vector3f(0.07f, 0.55f, 1.5f), Renderer.TEX_WOOD);
        }
        // Signposts at the forks — where the radial paths cross the ring path
        // (z = -110, the cobble cap's first segment boundary)
        for (int i = 0; i < 5; i++) {
            float px = (i - 2) * 30f;
            float sx = px + 1.55f, sz = -110f;
            if (!chunkVisible(sx, sz)) continue;
            // Post + two opposing direction boards (arrow-plate shapes)
            r.drawCube(new Vector3f(sx, floorY + 0.9f, sz),
                new Vector3f(0.1f, 1.8f, 0.1f), Renderer.TEX_BARK);
            r.drawCubeColor(new Vector3f(sx, floorY + 1.45f, sz),
                new Vector3f(0.06f, 0.28f, 1.1f), 0.85f, 0.72f, 0.45f);   // pointing ahead
            r.drawCubeColor(new Vector3f(sx, floorY + 1.15f, sz),
                new Vector3f(0.06f, 0.28f, 1.1f), 0.75f, 0.62f, 0.38f);   // pointing behind
        }
    }

        // ── Helpers ──────────────────────────────────────────────────────────────

    private boolean inLake(float x, float z) {
        float dx = x - lakeCenter.x, dz = z - lakeCenter.z;
        return (dx * dx) / (30f * 30f) + (dz * dz) / (20f * 20f) < 1f;
    }

    private boolean near(float x, float z, Vector3f p, float r) {
        float dx = x - p.x, dz = z - p.z;
        return dx * dx + dz * dz < r * r;
    }
}
