package com.mindpalace.world;

import java.util.concurrent.CopyOnWriteArrayList;

import com.mindpalace.render.Camera;
import com.mindpalace.render.Renderer;
import com.mindpalace.render.Texture;
import com.mindpalace.github.GitHubClient;
import com.mindpalace.github.RepoScanner;
import org.joml.Vector3f;
import com.mindpalace.render.FrustumCuller;
import java.util.*;

/**
 * Procedural world builder — hallways, rooms, bookcases, neon signs, stairwell.
 * Rooms sorted by repo size. Hardwood floors, wallpaper, exit signs.
 */
public class WorldBuilder {

    private final FrustumCuller frustum = new FrustumCuller();
    private long culledRoomsLastFrame = 0; // F4-visible stat

    /** Rooms the frustum culler skipped last frame (perf HUD row). */
    public long getCulledRoomsLastFrame() { return culledRoomsLastFrame; }

    private List<Room> rooms = new CopyOnWriteArrayList<>();
    private List<Hallway> hallways = new ArrayList<>();

    public static final float HALLWAY_WIDTH = 3.5f;
    public static final float HALLWAY_HEIGHT = 4.0f;
    public static final float DOOR_SPACING = 5.0f;
    public static final float HALLWAY_START_OFFSET = 3.0f;

    // Stairway geometry (walkable ramp between floors)
    public static final int STAIR_STEPS = 8;
    public static final float STAIR_RUN = 0.6f;
    public static final float STAIR_OFFSET = 0.0f;   // stairs start at hallway end (was 2.0, hidden behind end wall)

    private RepoMapper repoMapper;
    private RoomPopulator populator;
    private FogOfWar fogOfWar;
    private final OutsideWorld outsideWorld = new OutsideWorld();

    /**
     * t_0c1374b0: optional in-game clock pin for e2e determinism. Null = live
     * wall clock (default). When set, every sky-phase / lighting decision
     * (GameEngine sky dome, WorldBuilder celestial + hallway windows) honors
     * the pin instead of java.time.LocalTime, so an evening tour renders the
     * same daytime scenes the brightness thresholds were calibrated on.
     */
    private Integer clockPin = null;
    public void setClockPin(Integer hour) { this.clockPin = hour; }
    public boolean isClockPinned() { return clockPin != null; }
    public int getPinHour() { return clockPin != null ? clockPin : 12; }
    private int effectiveHour() {
        return clockPin != null ? clockPin : java.time.LocalTime.now().getHour();
    }

    // Lazily-loaded repo poster images (path -> GPU texture). Loaded on first
    // render of each room's poster; falls back to the diagram when absent.
    private final Map<String, Texture> posterTextures = new HashMap<>();
    private final Set<String> posterLoadFailed = new HashSet<>();
    // Poster images are decoded on ONE background thread (stb decode + disk read cost 100-500 ms on the render thread the
    // first time a room came into view). Only the GL upload stays on the render thread, at most one per frame.
    private final Map<String, java.util.concurrent.Future<Texture.Pixels>> posterDecodes = new HashMap<>();
    private final java.util.concurrent.ExecutorService posterDecoder = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "poster-decoder");
        t.setDaemon(true);
        return t;
    });
    private int posterUploadsThisFrame;

    // Global animation clock (seconds) — drives pulsing teleporters, neon, etc.
    public float time = 0f;
    public void tick(float dt) { time += dt; }

    // Stairways connecting floors: {startZ, startY, endZ, endY}
    private final List<float[]> stairways = new ArrayList<>();

    private static final float ROOM_CULL_DISTANCE = 30.0f;

    // ── Phase F: outside-world chunked streaming ──
    // The outside world is divided into square chunks (CHUNK meters). Only
    // chunks within OUTSIDE_RENDER_DISTANCE of the camera are drawn, so the
    // forest/lake/lakehouse decorations stream in small pieces instead of
    // being rendered all-at-once every frame. This is the perf win on the
    // Intel HD 510 (the outside scene alone was ~1200 cubes drawn every frame
    // regardless of where the player stood).
    private static final float OUTSIDE_CHUNK = 8f;          // chunk cell size (m)
    private static final float OUTSIDE_RENDER_DIST = 55f;   // draw within this radius (matches fog start ~60m)
    private float camX, camZ;                               // camera pos (set each render)

    /** True if the chunk containing (x,z) is within the streaming radius. */
    private boolean chunkVisible(float x, float z) {
        return chunkVisibleAt(x, z, camX, camZ);
    }

    /** Pure chunk culling: is the chunk at (x,z) within radius of (camX,camZ)? */
    public static boolean chunkVisibleAt(float x, float z, float camX, float camZ) {
        float cx = (float) Math.floor(x / OUTSIDE_CHUNK) * OUTSIDE_CHUNK + OUTSIDE_CHUNK / 2f;
        float cz = (float) Math.floor(z / OUTSIDE_CHUNK) * OUTSIDE_CHUNK + OUTSIDE_CHUNK / 2f;
        float dx = cx - camX, dz = cz - camZ;
        return (dx * dx + dz * dz) <= OUTSIDE_RENDER_DIST * OUTSIDE_RENDER_DIST;
    }

    // ── Planet (open world) ──
    // A small sphere the player can walk around in ~1 minute. Radial gravity
    // pulls toward the center; the surface is the planet radius. The palace
    // stays flat; the teleporter drops you onto the planet.
    public static final float PLANET_RADIUS = 35.0f;
    private final Vector3f planetCenter = new Vector3f(0f, -PLANET_RADIUS - 20f, -120f);
    private boolean planetActive = false;   // true once the player teleports there

    public Vector3f getPlanetCenter() { return planetCenter; }
    public float getPlanetRadius() { return PLANET_RADIUS; }
    public boolean isPlanetActive() { return planetActive; }
    public void setPlanetActive(boolean a) { planetActive = a; }

    /**
     * Teleporter pads — one per floor (except the top floor, which has no
     * "up" pad). Each pad is a distinct destination in the teleporter
     * network. Pads are returned in floor order, so index i == floor i.
     */
    public List<Vector3f> getTeleporterPads() {
        List<Vector3f> pads = new ArrayList<>();
        for (Hallway hw : hallways) {
            if (hw.getFloor() >= hallways.size() - 1) continue; // no pad on top floor
            float padZ = hw.getEnd().z - 1.0f;
            pads.add(new Vector3f(0f, hw.getStart().y, padZ));
        }
        return pads;
    }

    /** The planet's teleporter pad — the +Y pole surface point (return portal). */
    public Vector3f getPlanetPad() {
        return new Vector3f(planetCenter.x, planetCenter.y + PLANET_RADIUS, planetCenter.z);
    }

    /** MP-007 (#153): door-dissolve portal sheet toggle (MP_DOOR_DISSOLVE=0 → pure MP-006). */
    private final boolean doorDissolveEnabled;

    /** MP-008 (#154): baked interior previews on the portal sheet (MP_DOOR_PREVIEW=0 → off). */
    private final boolean doorPreviewEnabled;
    private RoomPreviewBank previewBank; // lazy: only if doorPreviewEnabled
    private boolean interiorOnly = false; // true while baking a preview (see renderRoom)

    public WorldBuilder() {
        repoMapper = new RepoMapper();
        populator = new RoomPopulator();
        fogOfWar = new FogOfWar(4.0f, 8.0f);
        doorDissolveEnabled = !"0".equals(System.getenv("MP_DOOR_DISSOLVE"));
        doorPreviewEnabled = !"0".equals(System.getenv("MP_DOOR_PREVIEW"));
    }

    public FogOfWar getFogOfWar() { return fogOfWar; }

    /** The expanded outside world (mansion spawn, TOC tree, etc.). */
    public OutsideWorld getOutsideWorld() { return outsideWorld; }

    /** True if (x,z) is inside the open outside world (past the courtyard). */
    public boolean isInOpenWorld(float x, float z) {
        return z < -30f && z > OutsideWorld.MIN_Z && Math.abs(x) < OutsideWorld.HALF_W;
    }

    public List<float[]> getStairways() { return stairways; }

    /**
     * Ground height (Y of the floor surface) at a world position, for walkable
     * stairs. Returns the floor Y of the hallway/stairway under (x,z), or the
     * base floor if none. Used by the Player so it can climb stairs instead of
     * teleporting.
     */
    public float getGroundHeight(float x, float z) {
        // Check stairways first (they span between floors)
        for (float[] s : stairways) {
            float startZ = s[0], startY = s[1], endZ = s[2], endY = s[3];
            if (z >= startZ - 0.3f && z <= endZ + 0.3f && Math.abs(x) < HALLWAY_WIDTH / 2f) {
                float t = (z - startZ) / (endZ - startZ);
                t = Math.max(0f, Math.min(1f, t));
                return startY + (endY - startY) * t;
            }
        }
        // Otherwise, find the hallway whose z-range contains this position
        for (Hallway hw : hallways) {
            float hz = hw.getStart().z;
            float len = hw.getEnd().z - hz;
            if (z >= hz - 1f && z <= hz + len + 1f) {
                return hw.getStart().y;
            }
        }
        return 0f;
    }

    /** Demo mode: rooms come from data/demo_repos.json fixtures (no auth). */
    private boolean demoMode = false;
    public void setDemoMode(boolean d) { this.demoMode = d; }

    public void build() {
        System.out.println("[WorldBuilder] Building MindPalace world...");
        if (demoMode) repoMapper.scanDemoRepos(rooms);
        else repoMapper.scanRepos(rooms);

        // Fog of war: fetch remote (incl. private) repos from GitHub and mark them fogged.
        // Demo mode (step 112) must stay zero-network — skip the merge entirely.
        GitHubClient gh = new GitHubClient();
        if (!demoMode && gh.loadTokenFromCredentialManager()) {
            try {
                RepoScanner scanner = new RepoScanner(gh);
                scanner.mergeRemoteRepos(rooms);
            } catch (Exception e) {
                System.err.println("[WorldBuilder] Remote merge failed: " + e.getMessage());
            }
        } else {
            System.out.println("[WorldBuilder] No GitHub token — remote/private repos stay hidden");
        }

        // Populate first so we can sort by book count
        for (Room room : rooms) populator.populateRoom(room);

        // Deduplicate rooms by canonical repo name (local folders with the same
        // git remote collapse to one room — keeps doors showing one true name)
        dedupeRooms();

        // Sort by book count (proxy for repo size) — largest repos first
        rooms.sort((a, b) -> Integer.compare(b.getBooks().size(), a.getBooks().size()));
        System.out.println("[WorldBuilder] Mapped " + rooms.size() + " repos to rooms (sorted by size)");
        layoutWorld();
        System.out.println("[WorldBuilder] World built: " + hallways.size() + " hallways, " + rooms.size() + " rooms");
    }

    /** Collapse rooms that share a canonical repo name (case-insensitive). */
    private void dedupeRooms() {
        Map<String, Room> seen = new LinkedHashMap<>();
        for (Room room : rooms) {
            String key = room.getRepoName().toLowerCase();
            Room existing = seen.get(key);
            if (existing == null) {
                seen.put(key, room);
            } else {
                // Merge: keep the room with more books (richer content)
                if (room.getBooks().size() > existing.getBooks().size()) {
                    seen.put(key, room);
                }
            }
        }
        int before = rooms.size();
        rooms.clear();
        rooms.addAll(seen.values());
        if (rooms.size() < before) {
            System.out.println("[WorldBuilder] Deduplicated " + (before - rooms.size())
                + " duplicate repo folders");
        }
    }

    private void layoutWorld() {
        // Step 113 (M2): layout is now a strategy (LAYOUT_ALGORITHMS.md).
        // CORRIDOR is a bit-identical port of the original body — the world's
        // shape must not change, E2E waypoints depend on it.
        activeLayout.layout(rooms, hallways);
        // No stairways — teleporters handle floor transitions (stairs were
        // removed: they poked through the floor above and looked like a
        // "block in the middle of the hall").
        stairways.clear();
    }

    /** Active layout strategy (default: the classic corridor). */
    private RoomLayout activeLayout = RoomLayout.CORRIDOR;
    /** Switch the layout strategy (must be called before build()). */
    public void setLayout(RoomLayout l) { this.activeLayout = l; }

    public void render(Renderer r, Camera camera) {
        posterUploadsThisFrame = 0;
        Vector3f camPos = camera.getPosition();
        Vector3f camFront = camera.getFront();
        this.camX = camPos.x;
        this.camZ = camPos.z;
        // perf: frustum culling — planes from the current proj*view
        frustum.update(r.getProjectionMatrix(), r.getViewMatrix());
        long before = culledRoomsLastFrame;
        culledRoomsLastFrame = 0;

        // Planet — always render when active (it's the open world)
        if (planetActive) renderPlanet(r, camPos);

        for (Hallway hw : hallways) {
            if (isHallwayNear(hw, camPos)) renderHallway(r, hw);
        }

        // Outside world — render independently of the hallway, so the player
        // can walk deep into it without the floor-0 hallway culling it away.
        // (Previously renderOutside was called from renderHallway, so walking
        // past the hallway's cull radius made the whole world vanish.)
        if (!planetActive) {
            Hallway hw0 = hallways.isEmpty() ? null : hallways.get(0);
            if (hw0 != null) {
                float frontZ = hw0.getStart().z - 2.0f;
                renderOutside(r, hw0.getStart().y, frontZ - 48.0f, camPos);
            }
        }

        for (Room room : rooms) {
            Vector3f c = room.getRoomCenter();
            float dist = camPos.distance(c);
            if (dist > ROOM_CULL_DISTANCE) continue;
            // perf: proper frustum test (room AABB) replaces the 2D front check
            if (!frustum.intersectsAabb(c, Room.ROOM_WIDTH / 2f, 1.5f, Room.ROOM_DEPTH / 2f)) {
                culledRoomsLastFrame++;
                continue;
            }
            // Fog of war: skip fogged rooms until their hex is revealed
            if (room.isFogged() && !fogOfWar.isRoomRevealed(room)) continue;
            renderRoom(r, room);
        }

        // MP-008 (#154): pump the preview bank — at most ONE room baked per
        // frame, drawn from the same visible+revealed set we just rendered.
        if (doorPreviewEnabled && doorDissolveEnabled) {
            if (previewBank == null) {
                previewBank = new RoomPreviewBank(this::renderRoomInterior);
            }
            List<Room> visible = new ArrayList<>();
            for (Room room : rooms) {
                Vector3f cc = room.getRoomCenter();
                if (camPos.distance(cc) > ROOM_CULL_DISTANCE) continue;
                if (room.isFogged() && !fogOfWar.isRoomRevealed(room)) continue;
                visible.add(room);
            }
            if (previewBank.bakeOne(r, visible) > 0) previewsBakedThisSession++;
            sheetShimmerPhase += 0.016f; // ~1 frame @60fps; shimmer-only, e2e-stable
        }
    }

    /** MP-008 evidence counter (F-visible: "[PreviewBank] baked=" in e2e logs). */
    private int previewsBakedThisSession = 0;
    public int getPreviewsBaked() { return previewsBakedThisSession; }

    /** MP-008: shimmer clock, advanced once per rendered frame in render(). */
    private float sheetShimmerPhase = 0f;

    /** MP-008 (#154): release the preview bank's GL resources (idempotent). */
    public void cleanup() {
        if (previewBank != null) {
            previewBank.dispose();
            previewBank = null;
        }
    }

    /** MP-008: preview texture for a room (0 = not baked yet / feature off). */
    public int getPreviewTexture(Room room) {
        return previewBank == null ? 0 : previewBank.getPreviewTexture(room);
    }

    /**
     * MP-008 (#154): draw one room's interior into the bank's currently-bound
     * 256² bake FBO. The bake camera is parked inside the bake pass (bank
     * owns Camera matrices — WorldBuilder only supplies the geometry draw).
     */
    private void renderRoomInterior(Renderer r, Room room) {
        interiorOnly = true;
        try {
            renderRoom(r, room);
        } finally {
            interiorOnly = false;
        }
    }

    /**
     * The planet — a grass sphere with phyllotaxis trees, cosine water, and a
     * populated surface. Rendered as a sphere mesh plus surface decorations
     * placed by the golden angle so they spread evenly over the globe.
     */
    private void renderPlanet(Renderer r, Vector3f camPos) {
        // Grass sphere (procedural turf texture)
        r.drawSphere(planetCenter, new Vector3f(PLANET_RADIUS, PLANET_RADIUS, PLANET_RADIUS), Renderer.TEX_GRASS);

        // Surface decorations — Fibonacci-sphere distribution (golden-angle
        // spiral over the sphere) so trees/rocks spread evenly, no clustering.
        float golden = 2.399963f;
        int n = 60;
        for (int i = 0; i < n; i++) {
            // Evenly distribute points on the sphere via the golden spiral
            float y = 1f - 2f * (i + 0.5f) / n;          // -1..1
            float rad = (float) Math.sqrt(Math.max(0f, 1f - y * y));
            float theta = golden * i;
            float dx = rad * (float) Math.cos(theta);
            float dz = rad * (float) Math.sin(theta);
            Vector3f dir = new Vector3f(dx, y, dz);      // unit surface normal
            Vector3f pos = new Vector3f(planetCenter).add(dir.mul(PLANET_RADIUS, new Vector3f()));

            // Cull decorations behind the camera (cheap: dot with view dir)
            Vector3f toCam = new Vector3f(camPos).sub(pos);
            if (toCam.dot(dir) < 0) continue;

            // Every 5th point is a tree; the rest are rocks/grass tufts
            if (i % 5 == 0) {
                renderPlanetTree(r, pos, dir);
            } else if (i % 3 == 0) {
                r.drawCubeColor(pos, new Vector3f(0.5f, 0.4f, 0.5f), 0.45f, 0.42f, 0.40f);
            } else {
                r.drawCubeColor(pos, new Vector3f(0.3f, 0.2f, 0.3f), 0.12f, 0.5f, 0.14f);
            }
        }

        // Return teleporter pad at the +Y pole — the way back to the palace.
        // Distinct Copper/Cobalt pair so the "Planet" menu entry is matchable.
        Vector3f pad = getPlanetPad();
        renderTeleporter(r, pad.y, pad.z, PortalTheme.PLANET);
    }

    /** A tree standing on the planet surface, oriented along the local normal. */
    private void renderPlanetTree(Renderer r, Vector3f base, Vector3f normal) {
        // Trunk (bark) — a thin cube along the normal
        Vector3f trunkMid = new Vector3f(base).add(normal.x * 1.2f, normal.y * 1.2f, normal.z * 1.2f);
        r.drawCube(trunkMid, new Vector3f(0.3f, 2.4f, 0.3f), Renderer.TEX_BARK);
        // Canopy — a few leaf cubes clustered at the top
        Vector3f top = new Vector3f(base).add(normal.x * 2.6f, normal.y * 2.6f, normal.z * 2.6f);
        r.drawCubeColor(top, new Vector3f(1.6f, 1.6f, 1.6f), 0.10f, 0.45f, 0.12f);
        r.drawCubeColor(new Vector3f(top).add(normal.x * 0.5f, normal.y * 0.5f, normal.z * 0.5f),
            new Vector3f(1.1f, 1.1f, 1.1f), 0.12f, 0.5f, 0.14f);
    }

    private boolean isHallwayNear(Hallway hw, Vector3f camPos) {
        float hz = hw.getStart().z;
        float len = hw.getEnd().z - hz;
        float midZ = hz + len / 2f;
        return Math.abs(camPos.x) < HALLWAY_WIDTH + 5 && Math.abs(camPos.z - midZ) < len / 2f + 40;
    }


    // ── Hallway ──

    private void renderHallway(Renderer r, Hallway hw) {
        Vector3f s = hw.getStart();
        float w = hw.getWidth(), h = hw.getHeight();
        float len = hw.getEnd().z - s.z;
        float cx = 0, cz = s.z + len / 2f;
        float wallT = 0.25f;

        // Hardwood floor — warm brown planks
        r.drawCube(new Vector3f(cx, s.y, cz), new Vector3f(w, 0.12f, len), Renderer.TEX_HARDWOOD);
        // Baseboard trim
        r.drawCube(new Vector3f(cx, s.y + 0.15f, cz), new Vector3f(w, 0.08f, len), Renderer.TEX_DOOR);
        // Ceiling
        r.drawCube(new Vector3f(cx, s.y + h, cz), new Vector3f(w, 0.15f, len), Renderer.TEX_CEILING);
        // Crown molding
        r.drawCube(new Vector3f(cx, s.y + h - 0.08f, cz), new Vector3f(w, 0.08f, len), Renderer.TEX_DOOR);
        // End wall at START and END — the teleporter pad sits just before the
        // end wall, so the hallway no longer opens into the void.
        r.drawCube(new Vector3f(cx, s.y + h / 2f, s.z), new Vector3f(w, h, wallT), Renderer.TEX_WALLPAPER);
        r.drawCube(new Vector3f(cx, s.y + h / 2f, s.z + len), new Vector3f(w, h, wallT), Renderer.TEX_WALLPAPER);
        // Side walls with doors
        renderWallWithDoors(r, s, hw, -1);
        renderWallWithDoors(r, s, hw, 1);

        // Floor indicator sign at hallway start
        float signX = 0, signY = s.y + h - 0.5f, signZ = s.z + 1.5f;
        r.drawCube(new Vector3f(signX, signY, signZ),
            new Vector3f(1.5f, 0.4f, 0.08f), Renderer.TEX_NEON_GREEN);

        // Teleporter pad at hallway end — every floor gets one, including the
        // top, so the pads form a fully connected network rather than a set of
        // one-way emitters with a dead end at the top. Themed with this floor's
        // complementary color pair (pad index == floor index, see
        // getTeleporterPads) so its menu entry is visually matchable.
        float padZ = hw.getEnd().z - 1.0f;
        renderTeleporter(r, s.y, padZ, PortalTheme.forPad(hw.getFloor()));

        // Poster frames on walls between doors
        renderPosters(r, s, hw);

        // Special areas only on floor 0 — placed in FRONT of the palace
        // entrance (negative Z) so they don't overlap upper floors' hallways.
        // (renderOutside is now called from render() directly, not here, so
        // the outside world survives the hallway cull.)
        if (hw.getFloor() == 0) {
            float frontZ = hw.getStart().z - 2.0f;   // just before the entrance wall
            renderLaboratory(r, s.y, frontZ - 8.0f);   // lab spans ~ -10..-2
            renderCourtyard(r, s.y, frontZ - 22.0f);   // courtyard ~ -24..-12
        }
    }

    /**
     * Teleporter — a pulsing portal pad with a rising light beam, a rotating
     * glow ring, and a swirling particle column (Phase G upgrade). Replaces
     * the old stairwell. Every color comes from the pad's PortalTheme: outer
     * ring + orbit bars + sparkles glow theme.colorA (accent), inner pad +
     * rising beam glow theme.colorB (complement) — so each linked pad has a
     * stable two-color identity a player can match to its menu entry.
     */
    private void renderTeleporter(Renderer r, float floorY, float padZ, PortalTheme theme) {
        float pulse = 0.5f + 0.5f * (float) Math.sin(time * 3.0f);
        float padY = floorY + 0.06f;

        // Base ring (theme accent) — slightly larger so the glow ring sits on top
        r.drawCubeColor(new Vector3f(0, padY, padZ), new Vector3f(2.0f, 0.08f, 2.0f),
            theme.colorA.x, theme.colorA.y, theme.colorA.z);
        // Inner pad (theme complement, pulses) — concentric animated rings
        r.drawCubeColor(new Vector3f(0, padY + 0.04f, padZ),
            new Vector3f(1.5f + pulse * 0.3f, 0.06f, 1.5f + pulse * 0.3f),
            theme.colorB.x, theme.colorB.y, theme.colorB.z);
        // Inner bright core (white-hot center)
        r.drawCube(new Vector3f(0, padY + 0.07f, padZ),
            new Vector3f(0.7f + pulse * 0.2f, 0.05f, 0.7f + pulse * 0.2f), Renderer.TEX_WHITE);

        // Rising light beam (vertical column, pulses upward) — theme complement
        float beamH = 1.5f + pulse * 1.5f;
        r.drawCubeColor(new Vector3f(0, padY + 0.1f + beamH / 2f, padZ),
            new Vector3f(0.5f, beamH, 0.5f),
            theme.colorB.x, theme.colorB.y, theme.colorB.z);

        // Rotating glow ring (two thin bars sweeping around the beam) — accent
        for (int ring = 0; ring < 2; ring++) {
            float ang = time * 1.5f + ring * (float) Math.PI;
            float ox = (float) Math.cos(ang) * 1.4f;
            float oz = (float) Math.sin(ang) * 1.4f;
            r.drawCubeColor(new Vector3f(ox, padY + 0.9f + ring * 0.4f, padZ + oz),
                new Vector3f(0.25f, 0.06f, 0.25f),
                theme.colorA.x, theme.colorA.y, theme.colorA.z);
        }

        // Swirling particle column — spiral of rising orbs
        for (int i = 0; i < 10; i++) {
            float phase = time * 2.5f + i * 0.62f;          // swirl rate + spacing
            float spiralAng = phase;
            float radius = 0.9f * (1.0f - (i / 10f));        // taper inward with height
            float ox = (float) Math.cos(spiralAng) * radius;
            float oz = (float) Math.sin(spiralAng) * radius;
            float oy = padY + 0.3f + (i / 10f) * 2.2f;       // rise with index
            // Alternate accent/complement/white for a shimmer — the pad reads
            // as exactly its two theme hues plus a white core.
            float sz = 0.10f + 0.06f * (float) Math.sin(time * 5f + i);
            if (i % 3 == 0) {
                r.drawCubeColor(new Vector3f(ox, oy, padZ + oz), new Vector3f(sz, sz, sz),
                    theme.colorA.x, theme.colorA.y, theme.colorA.z);
            } else if (i % 3 == 1) {
                r.drawCubeColor(new Vector3f(ox, oy, padZ + oz), new Vector3f(sz, sz, sz),
                    theme.colorB.x, theme.colorB.y, theme.colorB.z);
            } else {
                r.drawCube(new Vector3f(ox, oy, padZ + oz), new Vector3f(sz, sz, sz), Renderer.TEX_WHITE);
            }
        }

        // Floating sparkle orbs around the beam (legacy — kept for density) — accent
        for (int i = 0; i < 4; i++) {
            float ang = time * 2.0f + i * (float) Math.PI / 2f;
            float ox = (float) Math.cos(ang) * 1.2f;
            float oz = (float) Math.sin(ang) * 1.2f;
            float oy = padY + 0.5f + 0.4f * (float) Math.sin(time * 4.0f + i);
            r.drawCubeColor(new Vector3f(ox, oy, padZ + oz),
                new Vector3f(0.12f, 0.12f, 0.12f),
                theme.colorA.x, theme.colorA.y, theme.colorA.z);
        }
    }

    private void renderLaboratory(Renderer r, float floorY, float labZ) {
        float lw = 10f, ld = 8f, lh = 5f;
        float cx = 0, cy = floorY + lh / 2f, cz = labZ + ld / 2f;
        float t = 0.3f;

        // Concrete floor
        r.drawCube(new Vector3f(cx, floorY, cz), new Vector3f(lw, 0.15f, ld), Renderer.TEX_CONCRETE);
        // Ceiling
        r.drawCube(new Vector3f(cx, floorY + lh, cz), new Vector3f(lw, 0.15f, ld), Renderer.TEX_METAL);
        // Metal walls
        r.drawCube(new Vector3f(cx, cy, cz - ld / 2f), new Vector3f(lw, lh, t), Renderer.TEX_METAL);
        r.drawCube(new Vector3f(cx, cy, cz + ld / 2f), new Vector3f(lw, lh, t), Renderer.TEX_METAL);
        r.drawCube(new Vector3f(cx - lw / 2f, cy, cz), new Vector3f(t, lh, ld), Renderer.TEX_METAL);
        r.drawCube(new Vector3f(cx + lw / 2f, cy, cz), new Vector3f(t, lh, ld), Renderer.TEX_METAL);

        // Lab tables (3 rows)
        for (int row = 0; row < 3; row++) {
            float tz = cz - ld / 2f + 2f + row * 2.5f;
            // Table surface
            r.drawCube(new Vector3f(cx - 2f, floorY + 1.0f, tz), new Vector3f(3f, 0.08f, 1.2f), Renderer.TEX_METAL);
            // Legs
            for (int lx = -1; lx <= 1; lx += 2) {
                for (int lz = -1; lz <= 1; lz += 2) {
                    r.drawCube(new Vector3f(cx - 2f + lx * 1.3f, floorY + 0.5f, tz + lz * 0.5f),
                        new Vector3f(0.08f, 1.0f, 0.08f), Renderer.TEX_METAL);
                }
            }
            // Computer monitor (block)
            r.drawCube(new Vector3f(cx - 2f, floorY + 1.4f, tz - 0.3f),
                new Vector3f(0.8f, 0.6f, 0.15f), Renderer.TEX_NEON_CYAN);
            // Keyboard
            r.drawCube(new Vector3f(cx - 2f, floorY + 1.08f, tz + 0.3f),
                new Vector3f(0.6f, 0.04f, 0.2f), Renderer.TEX_WHITE);
        }

        // Microscope (center table)
        float mz = cz;
        r.drawCube(new Vector3f(cx + 2f, floorY + 1.0f, mz), new Vector3f(2f, 0.08f, 1.2f), Renderer.TEX_METAL);
        for (int lx = -1; lx <= 1; lx += 2) {
            for (int lz = -1; lz <= 1; lz += 2) {
                r.drawCube(new Vector3f(cx + 2f + lx * 0.8f, floorY + 0.5f, mz + lz * 0.5f),
                    new Vector3f(0.08f, 1.0f, 0.08f), Renderer.TEX_METAL);
            }
        }
        // Microscope body
        r.drawCube(new Vector3f(cx + 2f, floorY + 1.3f, mz), new Vector3f(0.15f, 0.5f, 0.15f), Renderer.TEX_WHITE);
        // Eyepiece
        r.drawCube(new Vector3f(cx + 2f, floorY + 1.6f, mz), new Vector3f(0.08f, 0.15f, 0.08f), Renderer.TEX_DOOR);
        // Objective lens
        r.drawCube(new Vector3f(cx + 2f, floorY + 1.05f, mz), new Vector3f(0.06f, 0.1f, 0.06f), Renderer.TEX_NEON_AMBER);

        // Server rack (back wall)
        for (int i = 0; i < 4; i++) {
            float sy = floorY + 0.3f + i * 0.5f;
            r.drawCube(new Vector3f(cx + lw / 2f - 0.5f, sy, cz + ld / 2f - 0.3f),
                new Vector3f(0.6f, 0.4f, 0.5f), Renderer.TEX_DOOR);
            // Blinking lights
            r.drawCube(new Vector3f(cx + lw / 2f - 0.3f, sy, cz + ld / 2f - 0.1f),
                new Vector3f(0.05f, 0.05f, 0.05f), Renderer.TEX_NEON_GREEN);
        }

        // Lab sign
        r.drawCube(new Vector3f(cx, floorY + lh - 0.3f, cz - ld / 2f + 0.15f),
            new Vector3f(3f, 0.3f, 0.06f), Renderer.TEX_NEON_AMBER);
    }

    private void renderCourtyard(Renderer r, float floorY, float courtZ) {
        float cw = 14f, cd = 12f, ch = 6f;
        float cx = 0, cy = floorY + ch / 2f, cz = courtZ + cd / 2f;
        float t = 0.3f;

        // Hardwood floor
        r.drawCube(new Vector3f(cx, floorY, cz), new Vector3f(cw, 0.12f, cd), Renderer.TEX_HARDWOOD);
        // Glass ceiling (cyan-tinted)
        r.drawCube(new Vector3f(cx, floorY + ch, cz), new Vector3f(cw, 0.1f, cd), Renderer.TEX_NEON_CYAN);
        // Wallpapered walls
        r.drawCube(new Vector3f(cx, cy, cz - cd / 2f), new Vector3f(cw, ch, t), Renderer.TEX_WALLPAPER);
        r.drawCube(new Vector3f(cx, cy, cz + cd / 2f), new Vector3f(cw, ch, t), Renderer.TEX_WALLPAPER);
        r.drawCube(new Vector3f(cx - cw / 2f, cy, cz), new Vector3f(t, ch, cd), Renderer.TEX_WALLPAPER);
        r.drawCube(new Vector3f(cx + cw / 2f, cy, cz), new Vector3f(t, ch, cd), Renderer.TEX_WALLPAPER);

        // Central fountain — fully hexagonal (step 92f). Square water violated
        // the "water is hex, not square" directive and the square basin read
        // as a featureless grey block from the hall door (sweep ledger:
        // "reads as white cylinder, no basin"). drawCube can't rotate, so a
        // hollow rim isn't available; instead the basin is a solid hex plinth
        // with the water surface as a raised hex prism — reads as a styled
        // fountain from every angle.
        float fx = cx, fz = cz;
        // Basin plinth (hex)
        r.drawHexPrism(new Vector3f(fx, floorY + 0.28f, fz), new Vector3f(1.7f, 0.56f, 1.7f), Renderer.TEX_CONCRETE);
        // Water surface (hex) resting on the plinth. STEP 94: the old single
        // TEX_NEON_CYAN disc shared its tint with the NPC pads and read as a
        // glowing pad, not water. Stacked layers instead — pale foam rim
        // slightly LARGER and LOWER than the water body, water gradient
        // deep-teal — the same cue stack as the lake. Gentle bob (sine of
        // world clock) so the surface is alive without wave-grid cost.
        float fbob = 0.012f * (float) Math.sin(time * 2.2f);
        // STEP 94v6 (all-blue night water — the fountain was the second
        // white-hex-water site the v5 lake patch never covered): the foam
        // rims were written (0.90,0.97,1.0) — luminance ~0.96, deep inside
        // BloomEffect's bright pass — so at night the rims and the TEX_WHITE
        // top tier self-added to the same screen-white hex plates the
        // Architect flagged on the lake (r12 frame 12: white plate on the
        // top-tier water). At night every fountain hex-water-adjacent tier
        // now stays in the blue family below the 0.5 kne...[truncated]
        boolean fnight = TimeClock.hour() < 6
            || TimeClock.hour() >= 18;
        // Foam rim (pale, larger, tucked under the water edge).
        // v6: at night the rim is DEEP-CYAN (below the bloom knee) — the
        // old pale-white rim was the fountain's white hex plate.
        if (fnight) {
            // STEP 94v7 (Architect: slight purple hue): fountain night tiers
            // drift with the lake — mild indigo-violet lean, still deep-cyan
            // family and far below the bloom knee. Day branch untouched.
            r.drawHexPrismColor(new Vector3f(fx, floorY + 0.575f, fz),
                new Vector3f(1.48f, 0.05f, 1.48f), 0.09f, 0.33f, 0.49f);
        } else {
            r.drawHexPrismColor(new Vector3f(fx, floorY + 0.575f, fz),
                new Vector3f(1.48f, 0.05f, 1.48f), 0.90f, 0.97f, 1.0f);
        }
        // Main water body
        r.drawHexPrismColor(new Vector3f(fx, floorY + 0.60f + fbob, fz),
            new Vector3f(1.34f, 0.06f, 1.34f), 0.16f, 0.50f, 0.72f);
        // Deep center layer
        r.drawHexPrismColor(new Vector3f(fx, floorY + 0.625f + fbob, fz),
            new Vector3f(0.95f, 0.05f, 0.95f), 0.08f, 0.32f, 0.55f);
        // Center pillar (hex) — v6: night reads steel-blue, not white
        if (fnight) {
            r.drawHexPrism(new Vector3f(fx, floorY + 0.92f, fz),
                new Vector3f(0.21f, 0.6f, 0.21f), Renderer.TEX_NEON_CYAN);
        } else {
            r.drawHexPrism(new Vector3f(fx, floorY + 0.92f, fz),
                new Vector3f(0.21f, 0.6f, 0.21f), Renderer.TEX_WHITE);
        }
        // Top tier: hex plinth + hex water (same layered water stack)
        // v6: night plinth is steel-blue (below the knee), not TEX_WHITE.
        if (fnight) {
            r.drawHexPrismColor(new Vector3f(fx, floorY + 1.305f, fz),
                new Vector3f(1.2f, 0.16f, 1.2f), 0.10f, 0.38f, 0.50f);
        } else {
            r.drawHexPrism(new Vector3f(fx, floorY + 1.30f, fz),
                new Vector3f(1.2f, 0.16f, 1.2f), Renderer.TEX_WHITE);
        }
        if (fnight) {
            // v7 purple drift: rim tiers (0.07,0.36,0.47)→(0.09,0.33,0.49).
            r.drawHexPrismColor(new Vector3f(fx, floorY + 1.40f, fz),
                new Vector3f(0.86f, 0.05f, 0.86f), 0.09f, 0.33f, 0.49f);
        } else {
            r.drawHexPrismColor(new Vector3f(fx, floorY + 1.40f, fz),
                new Vector3f(0.86f, 0.05f, 0.86f), 0.90f, 0.97f, 1.0f);
        }
        r.drawHexPrismColor(new Vector3f(fx, floorY + 1.43f + fbob, fz),
            new Vector3f(0.70f, 0.06f, 0.70f), 0.16f, 0.50f, 0.72f);

        // Couches (left side)
        for (int i = 0; i < 2; i++) {
            float sx = cx - cw / 2f + 2f + i * 4f;
            float sz = cz - cd / 2f + 2f;
            // Seat
            r.drawCube(new Vector3f(sx, floorY + 0.4f, sz), new Vector3f(2.5f, 0.3f, 1.0f), Renderer.TEX_BOOK_RED);
            // Back
            r.drawCube(new Vector3f(sx, floorY + 0.8f, sz + 0.5f), new Vector3f(2.5f, 0.5f, 0.15f), Renderer.TEX_BOOK_RED);
            // Armrests
            r.drawCube(new Vector3f(sx - 1.1f, floorY + 0.5f, sz), new Vector3f(0.2f, 0.2f, 1.0f), Renderer.TEX_DOOR);
            r.drawCube(new Vector3f(sx + 1.1f, floorY + 0.5f, sz), new Vector3f(0.2f, 0.2f, 1.0f), Renderer.TEX_DOOR);
        }

        // TV screens (back wall)
        for (int i = 0; i < 2; i++) {
            float tvx = cx - 2f + i * 4f;
            r.drawCube(new Vector3f(tvx, floorY + 2.5f, cz + cd / 2f - 0.2f),
                new Vector3f(2.5f, 1.5f, 0.1f), Renderer.TEX_NEON_CYAN);
            // Frame
            r.drawCube(new Vector3f(tvx, floorY + 2.5f, cz + cd / 2f - 0.15f),
                new Vector3f(2.7f, 1.7f, 0.08f), Renderer.TEX_DOOR);
        }

        // Bar (right side)
        float bx = cx + cw / 2f - 2f, bz = cz - cd / 2f + 3f;
        r.drawCube(new Vector3f(bx, floorY + 1.0f, bz), new Vector3f(3f, 0.1f, 1.0f), Renderer.TEX_DOOR);
        // Bar front panel
        r.drawCube(new Vector3f(bx, floorY + 0.5f, bz + 0.5f), new Vector3f(3f, 1.0f, 0.1f), Renderer.TEX_DOOR);
        // Stools
        for (int i = 0; i < 3; i++) {
            float sx = bx - 1f + i * 1f;
            r.drawCube(new Vector3f(sx, floorY + 0.5f, bz - 0.5f), new Vector3f(0.3f, 0.6f, 0.3f), Renderer.TEX_METAL);
            r.drawCube(new Vector3f(sx, floorY + 0.85f, bz - 0.5f), new Vector3f(0.4f, 0.08f, 0.4f), Renderer.TEX_BOOK_RED);
        }

        // Hotel safe (corner, encrypted secrets)
        float safeX = cx - cw / 2f + 1.5f, safeZ = cz + cd / 2f - 1.5f;
        r.drawCube(new Vector3f(safeX, floorY + 0.6f, safeZ), new Vector3f(1.0f, 1.0f, 0.8f), Renderer.TEX_METAL);
        // Safe door
        r.drawCube(new Vector3f(safeX, floorY + 0.6f, safeZ + 0.4f), new Vector3f(0.8f, 0.8f, 0.05f), Renderer.TEX_DOOR);
        // Combination dial
        r.drawCube(new Vector3f(safeX, floorY + 0.6f, safeZ + 0.43f), new Vector3f(0.15f, 0.15f, 0.03f), Renderer.TEX_PLAQUE);
        // Handle
        r.drawCube(new Vector3f(safeX + 0.2f, floorY + 0.6f, safeZ + 0.45f), new Vector3f(0.08f, 0.3f, 0.06f), Renderer.TEX_PLAQUE);

        // Courtyard sign
        r.drawCube(new Vector3f(cx, floorY + ch - 0.3f, cz - cd / 2f + 0.15f),
            new Vector3f(4f, 0.3f, 0.06f), Renderer.TEX_NEON_AMBER);
    }

    private void renderOutside(Renderer r, float floorY, float outZ, Vector3f camPos) {
        // The expanded open world (Phase I) — ~300×250m of forest, lake,
        // mansion, hospital, factory, TOC tree, towns, shops, inventions.
        // Delegated to OutsideWorld; the sky dome is drawn by GameEngine, so
        // the old flat band-stack sky (which never meshed at the horizon) is
        // gone. Sun/moon/stars ride the sky arc here (step 92d: real spheres
        // on a diurnal orbit, not the old fixed square slabs).
        outsideWorld.render(r, floorY, camX, camZ, time);
        renderCelestial(r, camPos);
    }

    /** Sun/moon sky-arc position (STEP 92d) — camera-relative, shared with the
     *  E2E sky waypoint so aim-at-target uses the SAME formula (no drift).
     *  hour24 = sun's clock hour; pass hour+12 for the moon. Sunrise 06:00 at
     *  the east horizon (−X), noon highest, sunset 18:00 west (+X), with a
     *  fixed south (+Z) lean so bodies sit inside a pitched view. */
    public static Vector3f celestialPosition(int hour24, Vector3f cam) {
        int h = ((hour24 % 24) + 24) % 24;
        float theta = (float) Math.toRadians((h - 6) * 15f);   // 6h→0°, 12h→90°, 18h→180°
        // STEP 94v7: bodies belong in SPACE, not parked on the ground. The
        // old dist=44 arc sank to +4 u above the camera at dusk — halo big
        // enough to clip terrain, so the sun visibly "sat on the ground".
        // Arc to 140 u out (inside far plane 200, inside sky dome 150), peak
        // +112 u above the camera at noon, min +9.5 u at horizon. Radii are
        // scaled (44/140) so ANGULAR size is unchanged from the old look.
        float dist = 140f;
        return new Vector3f(
            cam.x - (float) Math.cos(theta) * dist,
            cam.y + 9.5f + (float) Math.sin(theta) * dist * (34f / 44f),
            cam.z + 14f * (140f / 44f));
    }

    /** Sun/moon/stars: real spheres on a diurnal arc (step 92d). The old
     *  implementation bolted flat square cube-slabs to one fixed sky spot —
     *  the "square sun/moon" — and never moved them. */
    private void renderCelestial(Renderer r, Vector3f camPos) {
        // STEP 95v8: moon/stars are EMITTERS at night — wrap the pass in a
        // full-bright bypass so the night phase dim never touches them.
        // Day: NO wrap — the sun rides the home pair exactly like v7
        // (day byte-stable no-op).
        if (r.isPhaseNight()) r.pushLight();
        int hour = TimeClock.hour();
        boolean sunUp = hour >= 5 && hour < 19;
        boolean moonUp = hour >= 18 || hour < 7;
        // STEP 94v7: at 140 u out the distance fog would eat ~35% of each
        // body's color — kill fog for the celestial pass only, restore after.
        if (sunUp || moonUp) r.setFogEnabled(0.0f);
        if (sunUp) {
            Vector3f sp = celestialPosition(hour, camPos);
            if (sp.y > camPos.y) { // risen above the horizon
                float t = Math.max(0f, Math.min(1f, (sp.y - camPos.y - 9.5f) / (140f * 34f / 44f))); // 0 horizon → 1 high
                // Layered glow: wide soft halo → mid glow → bright core.
                // v7: radii ×(140/44) — same angular size, now 140 u out.
                r.drawSphereColor(sp, new Vector3f(20.7f, 20.7f, 20.7f), 1.00f, 0.55f + 0.20f * t, 0.25f + 0.15f * t);
                r.drawSphereColor(sp, new Vector3f(14.0f, 14.0f, 14.0f), 1.00f, 0.62f + 0.26f * t, 0.28f + 0.22f * t);
                r.drawSphereColor(sp, new Vector3f(8.3f, 8.3f, 8.3f), 1.00f, 0.78f, 0.42f);
            }
        }
        if (moonUp) {
            Vector3f mp = celestialPosition(hour + 12, camPos);
            if (mp.y > camPos.y) {
                // Pale glow + core + face craters (billboarded toward camera).
                // v7: HALF SIZE (Architect: moon could be smaller by 1/2) and
                // 140 u out — radii ×0.5×(140/44), face details scale along.
                r.drawSphereColor(mp, new Vector3f(6.05f, 6.05f, 6.05f), 0.50f, 0.55f, 0.68f);
                r.drawSphereColor(mp, new Vector3f(3.8f, 3.8f, 3.8f), 0.90f, 0.92f, 0.98f);
                Vector3f dir = new Vector3f(camPos).sub(mp).normalize(); // moon → camera
                Vector3f lat = new Vector3f(dir.z, 0, -dir.x).normalize();
                r.drawSphereColor(new Vector3f(mp).add(new Vector3f(dir).mul(1.05f)).add(new Vector3f(lat).mul(0.35f)),
                    new Vector3f(0.67f, 0.67f, 0.67f), 0.76f, 0.78f, 0.86f);
                r.drawSphereColor(new Vector3f(mp).add(new Vector3f(dir).mul(0.95f)).add(new Vector3f(lat).mul(-0.40f)).sub(0f, 0.09f, 0f),
                    new Vector3f(0.47f, 0.47f, 0.47f), 0.78f, 0.80f, 0.88f);
                r.drawSphereColor(new Vector3f(mp).add(new Vector3f(dir).mul(1.10f)).add(0f, 0.15f, 0f),
                    new Vector3f(0.41f, 0.41f, 0.41f), 0.74f, 0.76f, 0.85f);
            }
        }
        if (sunUp || moonUp) r.setFogEnabled(1.0f);
        // Stars: strict night gate, matched to the sky-dome night phase.
        if (hour >= 20 || hour < 6) {
            Constellation.render(r, camPos.x, camPos.y + 26f, camPos.z, time);
        }
        if (r.isPhaseNight()) r.popLight();
    }


    private float[] lerp(float[] a, float[] b, float t) {
        return new float[]{ a[0]+(b[0]-a[0])*t, a[1]+(b[1]-a[1])*t, a[2]+(b[2]-a[2])*t };
    }

    private void renderWallWithDoors(Renderer r, Vector3f s, Hallway hw, int side) {
        float h = hw.getHeight(), len = hw.getEnd().z - s.z;
        float wallX = side * hw.getWidth() / 2f, wallT = 0.25f;
        float dw = Room.DOOR_WIDTH, dh = Room.DOOR_HEIGHT;

        List<Float> doorZs = new ArrayList<>();
        for (Room room : rooms)
            if (room.getHallwaySide() == (side == -1 ? 0 : 1) && room.getFloor() == hw.getFloor())
                if (room.getDoorPosition() != null) {
                    // Fogged rooms have no visible door until revealed
                    if (room.isFogged() && !fogOfWar.isRoomRevealed(room)) continue;
                    doorZs.add(room.getDoorPosition().z);
                }
        doorZs.sort(Float::compare);

        float prevZ = s.z;
        for (float dz : doorZs) {
            float segEnd = dz - dw / 2f;
            if (segEnd > prevZ) {
                float segCz = (prevZ + segEnd) / 2f;
                r.drawCube(new Vector3f(wallX, s.y + h / 2f, segCz),
                    new Vector3f(wallT, h, segEnd - prevZ), Renderer.TEX_WALLPAPER);
            }
            float aboveH = h - dh;
            if (aboveH > 0)
                r.drawCube(new Vector3f(wallX, s.y + dh + aboveH / 2f, dz),
                    new Vector3f(wallT, aboveH, dw), Renderer.TEX_WALLPAPER);
            prevZ = dz + dw / 2f;
        }
        if (prevZ < s.z + len) {
            float segCz = (prevZ + s.z + len) / 2f;
            r.drawCube(new Vector3f(wallX, s.y + h / 2f, segCz),
                new Vector3f(wallT, h, s.z + len - prevZ), Renderer.TEX_WALLPAPER);
        }

        // Neon signs + exit signs above each door
        for (Room room : rooms) {
            if (room.getHallwaySide() == (side == -1 ? 0 : 1) && room.getFloor() == hw.getFloor()) {
                Vector3f dp = room.getDoorPosition();
                if (dp == null) continue;
                if (room.isFogged() && !fogOfWar.isRoomRevealed(room)) continue;
                renderNeonSign(r, wallX, s.y + h - 0.3f, dp.z, room);
                renderExitSign(r, wallX, s.y + h - 0.1f, dp.z);
                renderDoorFrame(r, wallX, s.y, dp.z, room);
            }
        }

        renderHallwayWindows(r, s, hw, wallX, side);
    }

    /** H20 (step 69): emissive window panes on the wall segments between
     *  doors — warm glow at "night" (18:00-06:00), dim neutral glass by day.
     *  One quad per mid-segment, centered on the wall, cheap (1 cube each). */
    private void renderHallwayWindows(Renderer r, Vector3f s, Hallway hw, float wallX, int side) {
        float len = hw.getEnd().z - s.z;
        float dw = Room.DOOR_WIDTH, dh = Room.DOOR_HEIGHT;
        // v7 TimeClock is the pin-aware choke point (--time N); t_0c1374b0's
        // per-class clockPin/effectiveHour was folded into it (GameEngine
        // still calls world.setClockPin/isClockPinned/getPinHour — pinned
        // flag mirrors TimeClock's override).
        int hour = TimeClock.hour();
        boolean night = hour < 6 || hour >= 18;
        // midpoints between consecutive doors get one pane each
        List<Float> doorZs = new ArrayList<>();
        for (Room room : rooms)
            if (room.getHallwaySide() == (side == -1 ? 0 : 1) && room.getFloor() == hw.getFloor())
                if (room.getDoorPosition() != null
                    && !(room.isFogged() && !fogOfWar.isRoomRevealed(room)))
                    doorZs.add(room.getDoorPosition().z);
        doorZs.sort(Float::compare);
        float prev = s.z;
        for (float dz : doorZs) {
            float segEnd = dz - dw / 2f;
            float mid = (prev + segEnd) / 2f;
            if (segEnd - prev > 1.6f && chunkVisible(wallX, mid)) {
                // pane: tall thin quad just inside the wall face, at eye height
                // pane faces INTO the hallway: offset toward center (x=0)
                float paneX = wallX - Math.signum(wallX) * 0.02f;
                if (night) {
                    r.drawCubeColor(new Vector3f(paneX, s.y + dh * 0.75f, mid),
                        new Vector3f(0.05f, 0.9f, 1.0f), 1.0f, 0.85f, 0.55f);
                } else {
                    r.drawCubeColor(new Vector3f(paneX, s.y + dh * 0.75f, mid),
                        new Vector3f(0.05f, 0.9f, 1.0f), 0.55f, 0.65f, 0.75f);
                }
            }
            prev = dz + dw / 2f;
        }
    }

    private void renderNeonSign(Renderer r, float wallX, float signY, float signZ, Room room) {
        float signW = 2.0f, signH = 0.35f, signD = 0.06f;
        float glowW = signW + 0.15f, glowH = signH + 0.15f, glowD = 0.03f;
        float offsetX = wallX > 0 ? -0.15f : 0.15f;
        int neonColor = room.isPrivate() ? Renderer.TEX_NEON_PINK : Renderer.TEX_NEON_CYAN;

        // Dark backing plate (so the glowing text has contrast — a bright sign
        // face with bright text was unreadable "weird letters").
        r.drawCube(new Vector3f(wallX + offsetX, signY, signZ),
            new Vector3f(glowD, glowH, glowW), Renderer.TEX_CEILING);
        // Thin neon border (the glow), not a solid bright face
        r.drawCube(new Vector3f(wallX + offsetX + (wallX > 0 ? -0.02f : 0.02f), signY, signZ),
            new Vector3f(signD, signH, signW), Renderer.TEX_CEILING);
        float bw = 0.04f;
        r.drawCube(new Vector3f(wallX + offsetX, signY + signH / 2f + 0.06f, signZ),
            new Vector3f(glowD + 0.02f, 0.06f, bw), neonColor);
        r.drawCube(new Vector3f(wallX + offsetX, signY - signH / 2f - 0.06f, signZ),
            new Vector3f(glowD + 0.02f, 0.06f, bw), neonColor);
    }

    private void renderExitSign(Renderer r, float wallX, float signY, float signZ) {
        float esw = 0.5f, esh = 0.2f, esd = 0.04f;
        float offsetX = wallX > 0 ? -0.15f : 0.15f;
        r.drawCube(new Vector3f(wallX + offsetX, signY, signZ),
            new Vector3f(esd, esh, esw), Renderer.TEX_NEON_GREEN);
    }

    /**
     * Glowing neon door frame — a thin emissive trim around each doorway so
     * doors read as portals, not just holes in the wall. Static (no pulse):
     * the Architect wants doors to sit still, not "breathe".
     */
    private void renderDoorFrame(Renderer r, float wallX, float floorY, float doorZ, Room room) {
        float dw = Room.DOOR_WIDTH, dh = Room.DOOR_HEIGHT;
        float offsetX = wallX > 0 ? -0.12f : 0.12f;
        // Heatmap tier (M3 step 123): commit activity last 30 days —
        // 0 = privacy color (cyan/pink), 1-2 = green, 3-5 = amber, 6+ = red.
        int color;
        int act = room.getActivity30d();
        if (act >= 6) color = Renderer.TEX_NEON_RED;
        else if (act >= 3) color = Renderer.TEX_NEON_AMBER;
        else if (act >= 1) color = Renderer.TEX_NEON_GREEN;
        else color = room.isPrivate() ? Renderer.TEX_NEON_PINK : Renderer.TEX_NEON_CYAN;
        float t = 0.10f; // trim thickness — 2x so frames stay visible on-axis
                         // (at 0.06 the posts sat only 0.025 proud of the wall
                         // face and aliased away at hallway distance)

        // Two vertical side posts — proud of the wall face (wallX±0.125) by
        // ~0.055 so they never sink into the wall or flicker out; extended
        // upward t/2 so the post/lintel corner overlaps with no gap.
        r.drawCube(new Vector3f(wallX + offsetX, floorY + (dh + t) / 2f, doorZ - dw / 2f),
            new Vector3f(t, dh + t, t), color);
        r.drawCube(new Vector3f(wallX + offsetX, floorY + (dh + t) / 2f, doorZ + dw / 2f),
            new Vector3f(t, dh + t, t), color);
        // Top lintel
        r.drawCube(new Vector3f(wallX + offsetX, floorY + dh, doorZ),
            new Vector3f(t, t, dw), color);
        // Soft glow halo behind the trim — nestled between the wall face
        // (wallX∓0.125) and the post fronts (wallX∓0.17: halo front at
        // wallX∓0.145), proud of the wall, recessed behind the posts, so
        // no faces are coplanar (the old halo front sat exactly on the
        // post-front plane → z-fight shimmer).
        // #153: gate the halo on the door state — opaque and proud of the
        // wall, it would otherwise sit IN FRONT of the portal sheet and
        // hide the dissolve; once the door starts opening the sheet owns
        // the aperture instead.
        if (room.getDoorOpenAmount() <= 0f) {
            r.drawCube(new Vector3f(wallX + offsetX + (wallX > 0 ? -0.025f : 0.025f), floorY + dh / 2f, doorZ),
                new Vector3f(0.02f, dh + 0.2f, dw + 0.2f), Renderer.TEX_NEON_AMBER);
        }
    }

    private void renderPosters(Renderer r, Vector3f s, Hallway hw) {
        float h = hw.getHeight(), len = hw.getEnd().z - s.z;
        // Place posters on both walls between doors
        for (int side = -1; side <= 1; side += 2) {
            float wallX = side * hw.getWidth() / 2f;
            float offsetX = wallX > 0 ? -0.15f : 0.15f;
            // Posters every 10m
            for (float pz = s.z + 5f; pz < s.z + len - 2f; pz += 10f) {
                float py = s.y + h * 0.55f;
                // Frame
                float fw = 0.8f, fh = 1.0f, fd = 0.04f;
                r.drawCube(new Vector3f(wallX + offsetX, py, pz),
                    new Vector3f(fd + 0.02f, fh + 0.06f, fw + 0.06f), Renderer.TEX_DOOR);
                // Poster (white) — proud of the frame's camera-side face by
                // 0.01: the old offsets (-0.01) put the poster front at
                // EXACTLY the frame front plane (coplanar) → brown/white
                // z-fight flicker ("poster overhang", sweep shot 05).
                r.drawCube(new Vector3f(wallX + offsetX + (wallX > 0 ? -0.02f : 0.02f), py, pz),
                    new Vector3f(fd, fh, fw), Renderer.TEX_WHITE);
            }
        }
    }

    // ── Room ──

    /**
     * MP-006 (#152): per-district solid door color. District == floor (one
     * hallway per floor, CorridorLayout). Defaults are 4 distinct district
     * colors; any floor can be overridden via config, e.g.
     *   MP_DOOR_COLOR_FLOOR1 (env) or mindpalace.door.color.floor1 (property)
     *   = "R,G,B"  (0..1 floats). One solid color for all doors of a floor;
     *   frames/handles stay neutral wood.
     */
    private static final float[][] DISTRICT_DOOR_COLORS = {
        {0.15f, 0.45f, 0.75f},  // floor 1 — blue
        {0.20f, 0.60f, 0.30f},  // floor 2 — green
        {0.80f, 0.55f, 0.15f},  // floor 3 — amber
        {0.65f, 0.20f, 0.20f},  // floor 4 — red
    };
    /** Neutral fallback for floors beyond the palette (districts stay one color each). */
    private static final float[] DISTRICT_DOOR_FALLBACK = {0.40f, 0.25f, 0.14f};

    /** District door color for a floor index (0-based), honoring config overrides. */
    static float[] districtDoorColor(int floor) {
        int idx = Math.max(0, floor);
        if (idx >= DISTRICT_DOOR_COLORS.length) return DISTRICT_DOOR_FALLBACK;
        float[] base = DISTRICT_DOOR_COLORS[idx];
        String over = System.getProperty("mindpalace.door.color.floor" + idx,
            System.getenv("MP_DOOR_COLOR_FLOOR" + idx));
        if (over != null && !over.isBlank()) {
            String[] p = over.split(",");
            if (p.length == 3) {
                try {
                    return new float[] {
                        Float.parseFloat(p[0].trim()),
                        Float.parseFloat(p[1].trim()),
                        Float.parseFloat(p[2].trim()),
                    };
                } catch (NumberFormatException ignored) {
                    // malformed override -> fall through to the palette default
                }
            }
        }
        return base;
    }

    private void renderRoom(Renderer r, Room room) {
        Vector3f c = room.getRoomCenter();
        float w = Room.ROOM_WIDTH, d = Room.ROOM_DEPTH, h = Room.ROOM_HEIGHT;
        float t = Room.WALL_THICKNESS;
        int side = room.getHallwaySide();

        // Per-room language accent — tint the whole room, then reset to neutral
        // H36 (#125): the tint ALSO carries the repo-health glow — health 50
        // (unknown) is EXACT neutral; below 50 dims the accent by up to 40%,
        // above 50 brightens it by up to +15%. Material/color change only (no
        // new geometry) per the issue's perf note.
        float[] tint = room.getTint();
        int hs = room.getHealthScore();
        float glow = hs >= 50
            ? 1.0f + (Math.min(hs, 100) - 50) / 50f * 0.15f
            : 1.0f - (50 - hs) / 50f * 0.4f;
        r.setTint(tint[0] * glow, tint[1] * glow, tint[2] * glow);

        // Hardwood floor
        r.drawCube(new Vector3f(c.x, c.y - h / 2f, c.z), new Vector3f(w, 0.1f, d), Renderer.TEX_HARDWOOD);
        // Baseboard trim
        r.drawCube(new Vector3f(c.x, c.y - h / 2f + 0.12f, c.z), new Vector3f(w, 0.06f, d), Renderer.TEX_DOOR);
        // Ceiling
        r.drawCube(new Vector3f(c.x, c.y + h / 2f, c.z), new Vector3f(w, 0.1f, d), Renderer.TEX_CEILING);
        // Crown molding
        r.drawCube(new Vector3f(c.x, c.y + h / 2f - 0.06f, c.z), new Vector3f(w, 0.06f, d), Renderer.TEX_DOOR);

        // Wallpapered walls
        float bz = side == 0 ? c.z + d / 2f : c.z - d / 2f;
        r.drawCube(new Vector3f(c.x, c.y, bz), new Vector3f(w, h, t), Renderer.TEX_WALLPAPER);
        r.drawCube(new Vector3f(c.x - w / 2f, c.y, c.z), new Vector3f(t, h, d), Renderer.TEX_WALLPAPER);
        r.drawCube(new Vector3f(c.x + w / 2f, c.y, c.z), new Vector3f(t, h, d), Renderer.TEX_WALLPAPER);

        // Front wall with door
        float fz = side == 0 ? c.z - d / 2f : c.z + d / 2f;
        float dh = Room.DOOR_WIDTH / 2f, dw = Room.DOOR_HEIGHT;
        float leftW = (w - Room.DOOR_WIDTH) / 2f;
        r.drawCube(new Vector3f(c.x - w / 2f + leftW / 2f, c.y, fz), new Vector3f(leftW, h, t), Renderer.TEX_WALLPAPER);
        r.drawCube(new Vector3f(c.x + w / 2f - leftW / 2f, c.y, fz), new Vector3f(leftW, h, t), Renderer.TEX_WALLPAPER);
        r.drawCube(new Vector3f(c.x, c.y + h / 2f - (h - dw) / 2f, fz), new Vector3f(Room.DOOR_WIDTH, h - dw, t), Renderer.TEX_WALLPAPER);

        // Door panel — MP-006 (#152): per-district solid door color; frame
        // posts/lintel stay neutral wood. District color drawn with the room
        // accent (setTint above) NEUTRALIZED so the language tint never
        // re-shifts the district color, restored for the rest of the room.
        // MP-007 (#153): as the leaf slides up, a translucent
        // district-colored portal sheet with a glowing rim fades in inside
        // the doorway (alpha ramps with the same door-open progress —
        // ~0.5s at the 2.0/s open speed). Closed doors render exactly the
        // MP-006 opaque leaf; fully open the sheet stays lit and mostly
        // solid (~0.85 alpha — the doorway ghosts the room through it, the
        // promised "window into the room", not a gap in geometry).
        // MP_DOOR_DISSOLVE=0 falls back to pure MP-006 behaviour.
        // #153 FIX: leaf + sheet live in the HALLWAY APERTURE (thin in X —
        // the wall plane renderWallWithDoors cuts at doorX = ±HALLWAY_WIDTH/2).
        // The old cubes sat on the z-plane "front wall" at doorZ ∓ ROOM_DEPTH/2,
        // ~3 m off the doorway and edge-on to any hallway camera, so neither the
        // district leaf (MP-006) nor the portal sheet (MP-007) was ever visible
        // from the hallway — the always-drawn amber halo read as the door
        // instead. Same district geometry, re-oriented onto the real aperture;
        // sheet offset toward the hallway keeps it in front of the leaf so the
        // crossfade reads as one door, two states.
        float doorApertureX = room.getDoorPosition() != null
            ? room.getDoorPosition().x
            : c.x + (side == 0 ? w / 2f + Room.WALL_THICKNESS : -(w / 2f + Room.WALL_THICKNESS));
        float hallDir = side == 0 ? 1f : -1f;   // toward the hallway walkway
        float open = room.getDoorOpenAmount();
        float doorSlide = open * dw;
        float doorPanelY = c.y - h / 2f + dw / 2f + doorSlide;
        float doorPanelH = dw * (1f - open);
        if (doorPanelH > 0.01f) {
            float[] dt = districtDoorColor(room.getFloor());
            r.setTint(1f, 1f, 1f);
            r.drawCubeColor(new Vector3f(doorApertureX, doorPanelY, c.z),
                new Vector3f(0.06f, doorPanelH, Room.DOOR_WIDTH - 0.04f), dt[0], dt[1], dt[2]);
            r.setTint(tint[0] * glow, tint[1] * glow, tint[2] * glow);
        }
        // Portal sheet — fades in inside the doorway while (and after) the
        // leaf slides up; fully closed doors draw nothing (the opaque leaf is
        // the whole doorway, pixel-identical to MP-006).
        // MP-008 (#154): when this room's interior preview is baked, the
        // sheet becomes a sampled window onto the baked interior with
        // view-angle parallax + a gentle shimmer. Not baked yet → plain
        // MP-007 rim sheet (also the MP_DOOR_PREVIEW=0 path).
        if (doorDissolveEnabled && open > 0f) {
            float[] dt = districtDoorColor(room.getFloor());
            float portalAlpha = Math.min(0.85f, open * 1.25f);     // lit by ~80% of the ramp
            int previewTex = doorPreviewEnabled ? getPreviewTexture(room) : 0;
            r.setTint(1f, 1f, 1f);
            r.drawPortalQuad(
                new Vector3f(doorApertureX + hallDir * 0.08f, c.y - h / 2f + dw / 2f, c.z),
                new Vector3f(0.06f, dw - 0.08f, Room.DOOR_WIDTH - 0.08f),
                dt[0] * 1.35f, dt[1] * 1.35f, dt[2] * 1.35f,
                portalAlpha, 0.22f, 2,                             // plane 2 = thin-X doorway quad (Y/Z rim)
                previewTex, sheetShimmerPhase);
            r.setTint(tint[0] * glow, tint[1] * glow, tint[2] * glow);
        }

        // Door frame
        float ft = 0.08f;
        r.drawCube(new Vector3f(c.x - dh, c.y - h / 2f + dw / 2f, fz), new Vector3f(ft, dw, ft), Renderer.TEX_DOOR);
        r.drawCube(new Vector3f(c.x + dh, c.y - h / 2f + dw / 2f, fz), new Vector3f(ft, dw, ft), Renderer.TEX_DOOR);
        r.drawCube(new Vector3f(c.x, c.y - h / 2f + dw, fz), new Vector3f(Room.DOOR_WIDTH, ft, ft), Renderer.TEX_DOOR);
        r.drawCube(new Vector3f(c.x, c.y - h / 2f + dw + 0.15f, fz), new Vector3f(Room.DOOR_WIDTH * 0.8f, 0.15f, 0.05f), Renderer.TEX_PLAQUE);

        // Repo poster board — above the door, shows repo name/language/stars.
        // Dark backing plate; the text is drawn by GameEngine.renderRoomPoster().
        float posterY = c.y - h / 2f + dw + 0.15f + 0.45f;
        // The plate used to sit at fz +/- 0.03, i.e. INSIDE the 0.2 m front wall, so the plate, bars, image and text were all invisible.
        float posterZ = side == 0 ? fz + (Room.WALL_FACE + 0.03f) : fz - (Room.WALL_FACE + 0.03f);
        r.drawCube(new Vector3f(c.x, posterY, posterZ),
            new Vector3f(2.2f, 0.7f, 0.04f), Renderer.TEX_CEILING);

        // Language-distribution diagram — a histogram of the repo's real file
        // languages, drawn as colored bars (each bar's height = its share of
        // files, color = the language's book-spine color). This is the poster
        // "diagram": it visualizes the repo's actual composition at a glance.
        // If the repo ships a real image (screenshot/diagram/logo), render that
        // instead on the poster — the diagram is the fallback.
        Texture posterTex = getPosterTexture(room);
        float posterFacingYaw = side == 0 ? (float) Math.PI : 0f;
        if (posterTex != null) {
            r.drawImageQuad(posterTex, new Vector3f(c.x, posterY, side == 0 ? posterZ + 0.025f : posterZ - 0.025f),   // in FRONT of the plate
                1.9f, 0.6f, posterFacingYaw);
        } else {
            renderPosterDiagram(r, room, c.x, posterY, posterZ, side);
        }

        // Doorknob
        float knobX = c.x + dh - 0.1f;
        float knobY = c.y - h / 2f + dw / 2f;
        float knobZ = side == 0 ? fz + 0.06f : fz - 0.06f;
        r.drawCube(new Vector3f(knobX, knobY, knobZ), new Vector3f(0.06f, 0.06f, 0.06f), Renderer.TEX_PLAQUE);

        // Table + chairs in center
        renderFurniture(r, c, w, d, h);

        // Ornaments — potted plant + lamp
        renderOrnaments(r, c, w, d, h, side);

        // Lab devices — test files as glowing devices in the back corner
        renderLabDevices(r, room, c, w, d, h, side);

        // Bookcases on 3 walls — the 4th (front/door) wall gets its own
        // display shelving + star wall art (step 75), so no wall is bare.
        renderBookcase(r, room, 0);
        renderBookcase(r, room, -1);
        renderBookcase(r, room, 1);
        renderFrontWallDisplay(r, room, c, w, d, h, side);

        r.setTint(1.0f, 1.0f, 1.0f); // reset to neutral
    }

    /** H24 (step 75): room interiors II — display shelves + star wall art on
     *  the 4th wall (the front/door wall). The two wall flanks beside the
     *  door get one floating shelf each (glass-plaque slab + 2 mini brackets),
     *  and the over-door wall strip carries the repo's star rating rendered
     *  as a row of gold stars (1-5, from the repo's real starCount). Reads as
     *  curated merch wall, mirrors the bookcase recipe, cheap (3 cubes/shelf
     *  flank + stars = 1 cube each, max 5). */
    private void renderFrontWallDisplay(Renderer r, Room room, Vector3f c, float w, float d, float h, int side) {
        float floorY = c.y - h / 2f;
        float fz = side == 0 ? c.z - d / 2f : c.z + d / 2f;
        float inZ = side == 0 ? fz + 0.12f : fz - 0.12f;   // just inside the front wall
        float dh = Room.DOOR_WIDTH / 2f;

        // One floating display shelf per door flank (left + right), eye height
        for (int fl = -1; fl <= 1; fl += 2) {
            float shx = c.x + fl * (dh + (w / 2f - dh) / 2f);
            r.drawCubeColor(new Vector3f(shx, floorY + 1.5f, inZ),
                new Vector3f(1.1f, 0.05f, 0.4f), 0.72f, 0.58f, 0.32f);
            // Brackets
            r.drawCubeColor(new Vector3f(shx - 0.4f, floorY + 1.36f, inZ),
                new Vector3f(0.05f, 0.14f, 0.08f), 0.4f, 0.3f, 0.18f);
            r.drawCubeColor(new Vector3f(shx + 0.4f, floorY + 1.36f, inZ),
                new Vector3f(0.05f, 0.14f, 0.08f), 0.4f, 0.3f, 0.18f);
            // Shelf cargo: 2 mini books + a trophy cube, colors from the room tint
            float[] tint = room.getTint();
            r.drawCubeColor(new Vector3f(shx - 0.22f, floorY + 1.65f, inZ),
                new Vector3f(0.12f, 0.26f, 0.08f), 0.2f + tint[0] * 0.6f, 0.2f + tint[1] * 0.6f, 0.25f + tint[2] * 0.6f);
            r.drawCubeColor(new Vector3f(shx - 0.07f, floorY + 1.63f, inZ),
                new Vector3f(0.1f, 0.22f, 0.14f), 0.25f + tint[2] * 0.6f, 0.2f + tint[0] * 0.5f, 0.2f + tint[1] * 0.5f);
            r.drawCubeColor(new Vector3f(shx + 0.3f, floorY + 1.62f, inZ),
                new Vector3f(0.14f, 0.2f, 0.14f), 0.95f, 0.8f, 0.3f);   // gold trophy
        }

        // Star wall art — the repo's starCount as gold star cubes (1-5),
        // stacked in flanking columns beside the door (the over-door strip is
        // fully occupied by the poster board, and ROOM_HEIGHT 3.5 leaves no
        // headroom above it — stars up there would be embedded in the ceiling).
        int stars = Math.max(0, Math.min(5, (int) Math.ceil(Math.log10(Math.max(1, room.getStarCount() + 1)) * 2.2f)));
        int left = (stars + 1) / 2, right = stars / 2;
        for (int fl = -1; fl <= 1; fl += 2) {
            int count = fl < 0 ? left : right;
            float colX = c.x + fl * (dh + 0.35f);
            for (int s2 = 0; s2 < count; s2++) {
                float sy = floorY + 1.85f + s2 * 0.3f;
                // Star = gold core + 4 tiny points (plus-shape), reads as a star at room scale
                r.drawCubeColor(new Vector3f(colX, sy, inZ), new Vector3f(0.16f, 0.16f, 0.04f), 1.0f, 0.85f, 0.3f);
                r.drawCubeColor(new Vector3f(colX, sy + 0.11f, inZ), new Vector3f(0.05f, 0.08f, 0.03f), 1.0f, 0.85f, 0.3f);
                r.drawCubeColor(new Vector3f(colX, sy - 0.11f, inZ), new Vector3f(0.05f, 0.08f, 0.03f), 1.0f, 0.85f, 0.3f);
                r.drawCubeColor(new Vector3f(colX - 0.11f, sy, inZ), new Vector3f(0.08f, 0.05f, 0.03f), 1.0f, 0.85f, 0.3f);
                r.drawCubeColor(new Vector3f(colX + 0.11f, sy, inZ), new Vector3f(0.08f, 0.05f, 0.03f), 1.0f, 0.85f, 0.3f);
            }
        }
    }

    /**
     * Draw a language-distribution histogram on the repo poster board.
     * Each bar represents a language present in the repo's files; bar height
     * is that language's share of the total, and color is its book-spine color.
     * This is the poster's "diagram" — a live visualization of the repo's
     * composition derived from the real files that populate the room.
     */
    private void renderPosterDiagram(Renderer r, Room room, float cx, float posterY, float posterZ, int side) {
        // Count files per language from the room's books (the real files).
        Map<String, Integer> langCount = new LinkedHashMap<>();
        for (Book b : room.getBooks()) {
            String lang = b.getLanguage() != null ? b.getLanguage() : "Other";
            langCount.merge(lang, 1, Integer::sum);
        }
        if (langCount.isEmpty()) return;

        // Sort languages by count (largest first) — the dominant language leads.
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(langCount.entrySet());
        sorted.sort((a, b) -> b.getValue() - a.getValue());
        int top = Math.min(sorted.size(), 6);
        int maxCount = sorted.get(0).getValue();

        float boardW = 2.2f, boardH = 0.7f;
        float barGap = 0.03f;
        float barW = (boardW - 0.15f - barGap * (top - 1)) / top;
        float startX = cx - boardW / 2f + 0.08f + barW / 2f;
        float baseY = posterY - boardH / 2f + 0.05f;   // bottom of the chart area
        float maxBarH = boardH - 0.16f;                // leave headroom for the title

        for (int i = 0; i < top; i++) {
            Map.Entry<String, Integer> e = sorted.get(i);
            int texId = Book.textureIdForLanguage(e.getKey());
            float frac = (float) e.getValue() / maxCount;
            float barH = Math.max(0.02f, maxBarH * frac);
            float bx = startX + i * (barW + barGap);
            float by = baseY + barH / 2f;
            // The poster faces into the room; offset the bars a hair in front of
            // the backing plate so they don't z-fight.
            float bz = side == 0 ? posterZ + 0.03f : posterZ - 0.03f;
            r.drawCube(new Vector3f(bx, by, bz), new Vector3f(barW, barH, 0.03f), texId);
        }
    }

    /**
     * Lazily load (and cache) a repo's poster image texture. Returns null if the
     * repo has no image or the image failed to load (then the diagram is used).
     */
    private Texture getPosterTexture(Room room) {
        String path = room.getPosterImagePath();
        if (path == null) return null;
        Texture tex = posterTextures.get(path);
        if (tex != null) return tex;
        if (posterLoadFailed.contains(path)) return null;
        java.util.concurrent.Future<Texture.Pixels> pending = posterDecodes.get(path);
        if (pending == null) {                        // first sight of this poster: start decoding, draw the diagram meanwhile
            posterDecodes.put(path, posterDecoder.submit(() -> Texture.decode(path, 1024)));
            return null;
        }
        if (!pending.isDone() || posterUploadsThisFrame >= 1) return null;     // not decoded yet / this frame's upload budget is spent
        posterDecodes.remove(path);
        try {
            tex = Texture.upload(pending.get());
            posterUploadsThisFrame++;
            posterTextures.put(path, tex);
            return tex;
        } catch (Exception e) {
            posterLoadFailed.add(path);
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            System.err.println("[Poster] image load failed: " + path + " — " + cause.getMessage());
            return null;
        }
    }

    private void renderLabDevices(Renderer r, Room room, Vector3f c, float w, float d, float h, int side) {
        List<LabDevice> devices = room.getLabDevices();
        if (devices.isEmpty()) return;
        float floorY = c.y - h / 2f;

        // Test lab corner — back-left, opposite the plant
        float labX = c.x + w / 2f - 0.8f;
        float labZ = side == 0 ? c.z + d / 2f - 0.8f : c.z - d / 2f + 0.8f;

        int count = Math.min(devices.size(), 6);
        for (int i = 0; i < count; i++) {
            LabDevice dev = devices.get(i);
            float dx = labX - (i % 3) * 0.35f;
            float dz = labZ - (i / 3) * 0.35f;
            // Device body
            r.drawCube(new Vector3f(dx, floorY + 0.35f, dz),
                new Vector3f(0.2f, 0.3f, 0.2f), Renderer.TEX_METAL);
            // Glow indicator (status color)
            r.drawCube(new Vector3f(dx, floorY + 0.6f, dz),
                new Vector3f(0.08f, 0.08f, 0.08f), dev.getGlowTexture());
            dev.setPosition(new Vector3f(dx, floorY + 0.6f, dz));
        }
    }

    private void renderFurniture(Renderer r, Vector3f c, float w, float d, float h) {
        float floorY = c.y - h / 2f;
        // Simple table
        float tableY = floorY + 0.6f;
        r.drawCube(new Vector3f(c.x, tableY, c.z), new Vector3f(1.2f, 0.06f, 0.8f), Renderer.TEX_DOOR);
        // Legs
        for (int lx = -1; lx <= 1; lx += 2)
            for (int lz = -1; lz <= 1; lz += 2)
                r.drawCube(new Vector3f(c.x + lx * 0.5f, floorY + 0.3f, c.z + lz * 0.3f),
                    new Vector3f(0.06f, 0.6f, 0.06f), Renderer.TEX_DOOR);
        // Chairs
        for (int ch = -1; ch <= 1; ch += 2) {
            float chZ = c.z + ch * 0.7f;
            r.drawCube(new Vector3f(c.x, floorY + 0.35f, chZ), new Vector3f(0.5f, 0.06f, 0.5f), Renderer.TEX_SHELF);
            r.drawCube(new Vector3f(c.x, floorY + 0.7f, chZ + 0.2f), new Vector3f(0.5f, 0.5f, 0.06f), Renderer.TEX_SHELF);
        }
    }

    private void renderOrnaments(Renderer r, Vector3f c, float w, float d, float h, int side) {
        float floorY = c.y - h / 2f;

        // Potted plant — back corner
        float plantX = c.x - w / 2f + 0.6f;
        float plantZ = side == 0 ? c.z + d / 2f - 0.6f : c.z - d / 2f + 0.6f;
        // Pot (brown cylinder approximation)
        r.drawCube(new Vector3f(plantX, floorY + 0.2f, plantZ),
            new Vector3f(0.25f, 0.4f, 0.25f), Renderer.TEX_DOOR);
        // Plant foliage (green sphere approximation)
        r.drawCube(new Vector3f(plantX, floorY + 0.55f, plantZ),
            new Vector3f(0.35f, 0.35f, 0.35f), Renderer.TEX_GRASS);
        r.drawCube(new Vector3f(plantX + 0.1f, floorY + 0.5f, plantZ),
            new Vector3f(0.2f, 0.25f, 0.2f), Renderer.TEX_GRASS);

        // Floor lamp — opposite corner
        float lampX = c.x + w / 2f - 0.6f;
        float lampZ = side == 0 ? c.z - d / 2f + 0.6f : c.z + d / 2f - 0.6f;
        // Pole
        r.drawCube(new Vector3f(lampX, floorY + 0.8f, lampZ),
            new Vector3f(0.06f, 1.6f, 0.06f), Renderer.TEX_PLAQUE);
        // Lampshade
        r.drawCube(new Vector3f(lampX, floorY + 1.65f, lampZ),
            new Vector3f(0.3f, 0.2f, 0.3f), Renderer.TEX_NEON_AMBER);
        // Light glow
        r.drawCube(new Vector3f(lampX, floorY + 1.5f, lampZ),
            new Vector3f(0.15f, 0.15f, 0.15f), Renderer.TEX_NEON_AMBER);
    }

    private void renderBookcase(Renderer r, Room room, int wallDir) {
        Vector3f c = room.getRoomCenter();
        float w = Room.ROOM_WIDTH, d = Room.ROOM_DEPTH, h = Room.ROOM_HEIGHT;
        int side = room.getHallwaySide();

        float caseDepth = 0.5f, caseWidth, caseX, caseZ, inset = 0.3f;
        if (wallDir == 0) {
            caseWidth = w - inset * 2; caseX = c.x;
            caseZ = side == 0 ? c.z + d / 2f - caseDepth / 2f - 0.1f : c.z - d / 2f + caseDepth / 2f + 0.1f;
        } else if (wallDir == -1) {
            caseWidth = d - inset * 2; caseX = c.x - w / 2f + caseDepth / 2f + 0.1f; caseZ = c.z;
        } else {
            caseWidth = d - inset * 2; caseX = c.x + w / 2f - caseDepth / 2f - 0.1f; caseZ = c.z;
        }

        float caseBottom = c.y - h / 2f + 0.1f, caseTop = c.y + h / 2f - 0.1f;
        float caseHeight = caseTop - caseBottom, caseMidY = (caseBottom + caseTop) / 2f;

        // Back panel
        float backX = caseX, backZ = caseZ;
        if (wallDir == 0) backZ = side == 0 ? caseZ + caseDepth / 2f - 0.05f : caseZ - caseDepth / 2f + 0.05f;
        else if (wallDir == -1) backX = caseX - caseDepth / 2f + 0.05f;
        else backX = caseX + caseDepth / 2f - 0.05f;
        if (wallDir == 0) r.drawCube(new Vector3f(backX, caseMidY, backZ), new Vector3f(caseWidth, caseHeight, 0.05f), Renderer.TEX_SHELF);
        else r.drawCube(new Vector3f(backX, caseMidY, backZ), new Vector3f(0.05f, caseHeight, caseWidth), Renderer.TEX_SHELF);

        // Side + top/bottom panels
        float pt = 0.06f;
        if (wallDir == 0) {
            r.drawCube(new Vector3f(caseX - caseWidth / 2f, caseMidY, caseZ), new Vector3f(pt, caseHeight, caseDepth), Renderer.TEX_DOOR);
            r.drawCube(new Vector3f(caseX + caseWidth / 2f, caseMidY, caseZ), new Vector3f(pt, caseHeight, caseDepth), Renderer.TEX_DOOR);
            r.drawCube(new Vector3f(caseX, caseBottom, caseZ), new Vector3f(caseWidth, pt, caseDepth), Renderer.TEX_DOOR);
            r.drawCube(new Vector3f(caseX, caseTop, caseZ), new Vector3f(caseWidth, pt, caseDepth), Renderer.TEX_DOOR);
        } else {
            r.drawCube(new Vector3f(caseX, caseMidY, caseZ - caseWidth / 2f), new Vector3f(caseDepth, caseHeight, pt), Renderer.TEX_DOOR);
            r.drawCube(new Vector3f(caseX, caseMidY, caseZ + caseWidth / 2f), new Vector3f(caseDepth, caseHeight, pt), Renderer.TEX_DOOR);
            r.drawCube(new Vector3f(caseX, caseBottom, caseZ), new Vector3f(caseDepth, pt, caseWidth), Renderer.TEX_DOOR);
            r.drawCube(new Vector3f(caseX, caseTop, caseZ), new Vector3f(caseDepth, pt, caseWidth), Renderer.TEX_DOOR);
        }

        // Books grouped by language, then partitioned across the 3 walls so
        // each book is placed exactly once. (Previously every wall re-placed
        // ALL books, so a book's clickable position ended up on the LAST wall
        // drawn — the right wall — regardless of where it was visible. That's
        // why clicks on back/left-wall books missed.)
        int wallIndex = wallDir == 0 ? 0 : (wallDir == -1 ? 1 : 2);
        List<Book> allBooks = room.getBooks();
        Map<String, List<Book>> byLang = new LinkedHashMap<>();
        for (Book bk : allBooks) {
            String lang = bk.getLanguage() != null ? bk.getLanguage() : "Other";
            byLang.computeIfAbsent(lang, k -> new ArrayList<>()).add(bk);
        }
        List<List<Book>> groups = new ArrayList<>(byLang.values());
        int totalBooks = 0;
        for (List<Book> g : groups) totalBooks += Math.min(g.size(), 15);
        if (totalBooks == 0) return;

        int rows = 3;
        float shelfSpacing = (caseHeight - pt * 2) / rows;
        float shelfY0 = caseBottom + pt + shelfSpacing / 2f;
        float bookH = shelfSpacing * 0.75f, bookD = caseDepth * 0.6f;

        int groupIdx = 0, bookInGroup = 0;
        List<Book> currentGroup = groups.isEmpty() ? new ArrayList<>() : groups.get(0);

        for (int row = 0; row < rows; row++) {
            float sy = shelfY0 + row * shelfSpacing;
            if (wallDir == 0)
                r.drawCube(new Vector3f(caseX, sy - bookH / 2f - 0.02f, caseZ), new Vector3f(caseWidth - pt, 0.03f, caseDepth - 0.05f), Renderer.TEX_SHELF);
            else
                r.drawCube(new Vector3f(caseX, sy - bookH / 2f - 0.02f, caseZ), new Vector3f(caseDepth - 0.05f, 0.03f, caseWidth - pt), Renderer.TEX_SHELF);

            float usableWidth = caseWidth - pt * 2 - 0.2f;
            float bookW = 0.10f, bookGap = 0.02f;
            int maxBooks = (int) (usableWidth / (bookW + bookGap));
            int placed = 0;

            while (placed < maxBooks && groupIdx < groups.size()) {
                if (bookInGroup >= currentGroup.size() || bookInGroup >= 15) {
                    groupIdx++; bookInGroup = 0;
                    if (groupIdx >= groups.size()) break;
                    currentGroup = groups.get(groupIdx);
                    float divOff = -usableWidth / 2f + placed * (bookW + bookGap);
                    if (wallDir == 0) r.drawCube(new Vector3f(caseX + divOff, sy, caseZ), new Vector3f(0.02f, bookH, bookD), Renderer.TEX_DOOR);
                    else r.drawCube(new Vector3f(caseX, sy, caseZ + divOff), new Vector3f(bookD, bookH, 0.02f), Renderer.TEX_DOOR);
                    placed++; continue;
                }
                Book book = currentGroup.get(bookInGroup);
                // Assign this book to a wall on first touch; only place it here
                // if it belongs to THIS wall.
                if (book.getWallIndex() < 0) book.setWallIndex(wallIndex);
                if (book.getWallIndex() != wallIndex) { bookInGroup++; continue; }
                float offset = -usableWidth / 2f + placed * (bookW + bookGap) + bookW / 2f;
                if (wallDir == 0) {
                    r.drawCube(new Vector3f(caseX + offset, sy, caseZ), new Vector3f(bookW, bookH, bookD), book.getTextureId());
                    book.setWorldPosition(caseX + offset, sy, caseZ);
                    book.setWallDir(0);
                    book.setPlaced(true);
                } else {
                    r.drawCube(new Vector3f(caseX, sy, caseZ + offset), new Vector3f(bookD, bookH, bookW), book.getTextureId());
                    book.setWorldPosition(caseX, sy, caseZ + offset);
                    book.setWallDir(wallDir);
                    book.setPlaced(true);
                }
                bookInGroup++; placed++;
            }
        }
    }

    public List<Room> getRooms() { return rooms; }
    public List<Hallway> getHallways() { return hallways; }

    /** Add a room to the live world and lay it out (no restart needed). */
    public void addRoom(Room room) {
        rooms.add(room);
        // Live-added rooms go on the LAST existing floor (never create a new
        // floor — there's no hallway for it). Clamp to hallways.size()-1.
        int total = rooms.size();
        int floors = hallways.size();
        int perFloor = (total + floors - 1) / floors;
        int perSide = (perFloor + 1) / 2;

        int idx = total - 1;
        int floor = Math.min(floors - 1, idx / (perSide * 2));
        int within = idx % (perSide * 2);
        int side = within / perSide;
        int i = within % perSide;

        Hallway hw = hallways.get(floor);
        float hz = hw.getStart().z;
        float hy = hw.getStart().y;
        float doorZ = hz + HALLWAY_START_OFFSET + i * DOOR_SPACING;
        float doorX = side == 0 ? -HALLWAY_WIDTH / 2f : HALLWAY_WIDTH / 2f;
        float cx = side == 0 ? -HALLWAY_WIDTH / 2f - Room.ROOM_DEPTH / 2f - Room.WALL_THICKNESS
                             : HALLWAY_WIDTH / 2f + Room.ROOM_DEPTH / 2f + Room.WALL_THICKNESS;

        room.setFloor(floor);
        room.setHallwaySide(side);
        room.setDoorPosition(new Vector3f(doorX, hy + 1.0f, doorZ));
        room.setRoomCenter(new Vector3f(cx, hy + Room.ROOM_HEIGHT / 2f, doorZ));
        room.setDoorRotation(side == 0 ? 90 : -90);

        // Objects spawn: populate books + lab devices for the new room
        populator.populateRoom(room);
        System.out.println("[WorldBuilder] Live-added room: " + room.getRepoName()
            + " (floor " + (floor + 1) + ", " + room.getBooks().size() + " books, "
            + room.getLabDevices().size() + " lab devices)");
    }

    public Room findRoomAt(Vector3f pos) {
        for (Room room : rooms) {
            Vector3f c = room.getRoomCenter();
            if (Math.abs(pos.x - c.x) < Room.ROOM_WIDTH / 2f && Math.abs(pos.z - c.z) < Room.ROOM_DEPTH / 2f) return room;
        }
        return null;
    }
}
