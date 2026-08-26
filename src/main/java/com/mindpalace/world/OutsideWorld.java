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
    private final Vector3f hospitalPos = new Vector3f(-60f, 0f, -120f);
    private final Vector3f factoryPos = new Vector3f(80f, 0f, -150f);
    private final Vector3f tocTreePos = new Vector3f(0f, 0f, -250f);
    private final Vector3f lakeCenter = new Vector3f(40f, 0f, -210f);

    /** Mansion (player home) position — player + crystals spawn here. */
    public Vector3f getMansionPos() { return mansionPos; }
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
        int h = LocalTime.now().getHour();
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
        r.drawCubeColor(new Vector3f(cx, floorY - 0.05f, cz),
            new Vector3f(wd, 0.02f, dp), tint[0], tint[1], tint[2]);

        // A few dirt paths radiating from the palace entrance (walkable feel)
        for (int i = 0; i < 5; i++) {
            float px = (i - 2) * 30f;
            r.drawCubeColor(new Vector3f(px, floorY - 0.08f, -80f),
                new Vector3f(2.5f, 0.02f, 60f), 0.45f, 0.35f, 0.22f);
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
        float lw = 60f, ld = 40f;
        float lx = lakeCenter.x, lz = lakeCenter.z;
        // Deep water base
        r.drawCube(new Vector3f(lx, floorY + 0.02f, lz), new Vector3f(lw, 0.1f, ld), Renderer.TEX_WATER);
        // High-res traveling-wave surface (finer grid than before)
        int gw = 30, gd = 20;
        for (int gx = 0; gx < gw; gx++) {
            for (int gz = 0; gz < gd; gz++) {
                float wx = lx - lw / 2f + (gx + 0.5f) * (lw / gw);
                float wz = lz - ld / 2f + (gz + 0.5f) * (ld / gd);
                if (!chunkVisible(wx, wz)) continue;
                float h = 0.08f * (float) Math.cos(wx * 0.9f + time * 1.6f)
                        + 0.06f * (float) Math.cos(wz * 1.3f - time * 1.2f)
                        + 0.04f * (float) Math.sin((wx + wz) * 0.5f + time * 0.8f);
                float a = 0.5f + 0.5f * (float) Math.cos(wx * 0.6f + wz * 0.5f + time * 1.4f);
                r.drawCubeColor(new Vector3f(wx, floorY + 0.08f + h, wz),
                    new Vector3f(lw / gw + 0.03f, 0.05f, ld / gd + 0.03f),
                    0.08f, 0.30f + 0.25f * a, 0.70f + 0.25f * a);
            }
        }
        // Sandy shoreline ring
        for (int i = 0; i < 40; i++) {
            float ang = i * GOLDEN;
            float rad = 32f;
            float sx = lx + (float) Math.cos(ang) * rad;
            float sz = lz + (float) Math.sin(ang) * rad * 0.7f;
            r.drawCubeColor(new Vector3f(sx, floorY - 0.06f, sz),
                new Vector3f(1.5f, 0.04f, 1.5f), 0.75f, 0.70f, 0.55f);
        }
    }

    // ── Forest (Fibonacci phyllotaxis, deciduous + evergreen) ──

    private void renderForest(Renderer r, float floorY, float time, Season s) {
        int count = 220;  // dense forest
        for (int ti = 0; ti < count; ti++) {
            // Golden-angle spiral → natural, non-clustering scatter
            float ang = ti * GOLDEN;
            float rad = 4f + 2.2f * (float) Math.sqrt(ti);
            float tx = (float) Math.cos(ang) * rad * 1.4f;
            float tz = -90f + (float) Math.sin(ang) * rad;
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

            // Every 4th tree is an evergreen (conifer); rest deciduous
            if (ti % 4 == 0) renderEvergreen(r, tx, floorY, tz, ti, lod);
            else renderDeciduous(r, tx, floorY, tz, ti, s, lod);
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
        float w = 22f, d = 16f, h = 7f;
        float cy = floorY + h / 2f, t = 0.3f;

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
        r.drawCube(new Vector3f(hx, floorY + 0.1f, hz), new Vector3f(w, 0.15f, d), Renderer.TEX_CONCRETE);
        r.drawCube(new Vector3f(hx, floorY + hh / 2f, hz - d / 2f), new Vector3f(w, hh, 0.2f), Renderer.TEX_WALL);
        r.drawCube(new Vector3f(hx, floorY + hh / 2f, hz + d / 2f), new Vector3f(w, hh, 0.2f), Renderer.TEX_WALL);
        r.drawCube(new Vector3f(hx - w / 2f, floorY + hh / 2f, hz), new Vector3f(0.2f, hh, d), Renderer.TEX_WALL);
        r.drawCube(new Vector3f(hx + w / 2f, floorY + hh / 2f, hz), new Vector3f(0.2f, hh, d), Renderer.TEX_WALL);
        // Roof (color varies per house)
        int[] roofTex = {Renderer.TEX_BOOK_RED, Renderer.TEX_BOOK_BLUE, Renderer.TEX_BOOK_ORANGE, Renderer.TEX_BOOK_GREY, Renderer.TEX_BOOK_YELLOW};
        r.drawCube(new Vector3f(hx, floorY + hh + 0.4f, hz), new Vector3f(w + 0.4f, 0.3f, d + 0.4f), roofTex[h % roofTex.length]);
        // Door + window
        r.drawCube(new Vector3f(hx, floorY + 1.1f, hz - d / 2f - 0.05f), new Vector3f(1.1f, 2.2f, 0.1f), Renderer.TEX_DOOR);
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
