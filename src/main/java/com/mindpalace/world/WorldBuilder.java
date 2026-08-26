package com.mindpalace.world;

import com.mindpalace.render.Camera;
import com.mindpalace.render.Renderer;
import com.mindpalace.render.Texture;
import com.mindpalace.github.GitHubClient;
import com.mindpalace.github.RepoScanner;
import org.joml.Vector3f;
import java.util.*;

/**
 * Procedural world builder — hallways, rooms, bookcases, neon signs, stairwell.
 * Rooms sorted by repo size. Hardwood floors, wallpaper, exit signs.
 */
public class WorldBuilder {
    private List<Room> rooms = new ArrayList<>();
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

    // Lazily-loaded repo poster images (path -> GPU texture). Loaded on first
    // render of each room's poster; falls back to the diagram when absent.
    private final Map<String, Texture> posterTextures = new HashMap<>();
    private final Set<String> posterLoadFailed = new HashSet<>();

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

    public WorldBuilder() {
        repoMapper = new RepoMapper();
        populator = new RoomPopulator();
        fogOfWar = new FogOfWar(4.0f, 8.0f);
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

    public void build() {
        System.out.println("[WorldBuilder] Building MindPalace world...");
        repoMapper.scanRepos(rooms);

        // Fog of war: fetch remote (incl. private) repos from GitHub and mark them fogged
        GitHubClient gh = new GitHubClient();
        if (gh.loadTokenFromCredentialManager()) {
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
        int total = rooms.size();
        int floors = Math.max(4, (total + 16) / 17); // ~17 rooms per floor
        int perFloor = (total + floors - 1) / floors;
        int perSide = (perFloor + 1) / 2;

        float len = perSide * DOOR_SPACING + HALLWAY_START_OFFSET * 2;
        float floorGap = HALLWAY_HEIGHT + 1.0f;
        float zOffset = len + 4.0f;

        for (int f = 0; f < floors; f++) {
            Hallway hw = new Hallway(f);
            hw.setStart(new Vector3f(0, f * floorGap, f * zOffset));
            hw.setEnd(new Vector3f(0, f * floorGap, f * zOffset + len));
            hw.setWidth(HALLWAY_WIDTH);
            hw.setHeight(HALLWAY_HEIGHT);
            hallways.add(hw);
        }

        // No stairways — teleporters handle floor transitions (stairs were
        // removed: they poked through the floor above and looked like a
        // "block in the middle of the hall").
        stairways.clear();

        int idx = 0;
        for (int floor = 0; floor < floors && idx < total; floor++) {
            Hallway hw = hallways.get(floor);
            float hz = hw.getStart().z;
            float hy = hw.getStart().y;
            for (int side = 0; side < 2 && idx < total; side++) {
                for (int i = 0; i < perSide && idx < total; i++) {
                    Room room = rooms.get(idx);
                    room.setFloor(floor);
                    room.setHallwaySide(side);
                    float doorZ = hz + HALLWAY_START_OFFSET + i * DOOR_SPACING;
                    float doorX = side == 0 ? -HALLWAY_WIDTH / 2f : HALLWAY_WIDTH / 2f;
                    float cx = side == 0 ? -HALLWAY_WIDTH / 2f - Room.ROOM_DEPTH / 2f - Room.WALL_THICKNESS
                                         : HALLWAY_WIDTH / 2f + Room.ROOM_DEPTH / 2f + Room.WALL_THICKNESS;
                    room.setDoorPosition(new Vector3f(doorX, hy + 1.0f, doorZ));
                    room.setRoomCenter(new Vector3f(cx, hy + Room.ROOM_HEIGHT / 2f, doorZ));
                    room.setDoorRotation(side == 0 ? 90 : -90);
                    idx++;
                }
            }
        }
    }

    public void render(Renderer r, Camera camera) {
        Vector3f camPos = camera.getPosition();
        Vector3f camFront = camera.getFront();
        this.camX = camPos.x;
        this.camZ = camPos.z;

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
                renderOutside(r, hw0.getStart().y, frontZ - 48.0f);
            }
        }

        for (Room room : rooms) {
            Vector3f c = room.getRoomCenter();
            float dist = camPos.distance(c);
            if (dist > ROOM_CULL_DISTANCE) continue;
            if (!isInFront(camPos, camFront, c, dist)) continue;
            // Fog of war: skip fogged rooms until their hex is revealed
            if (room.isFogged() && !fogOfWar.isRoomRevealed(room)) continue;
            renderRoom(r, room);
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
        Vector3f pad = getPlanetPad();
        renderTeleporter(r, pad.y, pad.z);
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

    private boolean isInFront(Vector3f camPos, Vector3f camFront, Vector3f target, float dist) {
        float dx = target.x - camPos.x, dz = target.z - camPos.z;
        float len = (float) Math.sqrt(dx * dx + dz * dz);
        if (len < 0.01f) return true;
        return (dx / len) * camFront.x + (dz / len) * camFront.z > -0.3f || dist < 8.0f;
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

        // Teleporter pad at hallway end (to next floor) — cool animated portal
        if (hw.getFloor() < hallways.size() - 1) {
            float padZ = hw.getEnd().z - 1.0f;
            renderTeleporter(r, s.y, padZ);
        }

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
     * Teleporter — a pulsing cyan portal pad with a rising light beam, a
     * rotating glow ring, and a swirling particle column (Phase G upgrade).
     * Replaces the old stairwell.
     */
    private void renderTeleporter(Renderer r, float floorY, float padZ) {
        float pulse = 0.5f + 0.5f * (float) Math.sin(time * 3.0f);
        float padY = floorY + 0.06f;

        // Base ring (amber) — slightly larger so the glow ring sits on top
        r.drawCube(new Vector3f(0, padY, padZ), new Vector3f(2.0f, 0.08f, 2.0f), Renderer.TEX_NEON_AMBER);
        // Inner pad (cyan, pulses) — concentric animated rings
        r.drawCube(new Vector3f(0, padY + 0.04f, padZ),
            new Vector3f(1.5f + pulse * 0.3f, 0.06f, 1.5f + pulse * 0.3f), Renderer.TEX_NEON_CYAN);
        // Inner bright core (white-hot center)
        r.drawCube(new Vector3f(0, padY + 0.07f, padZ),
            new Vector3f(0.7f + pulse * 0.2f, 0.05f, 0.7f + pulse * 0.2f), Renderer.TEX_WHITE);

        // Rising light beam (vertical column, pulses upward)
        float beamH = 1.5f + pulse * 1.5f;
        r.drawCube(new Vector3f(0, padY + 0.1f + beamH / 2f, padZ),
            new Vector3f(0.5f, beamH, 0.5f), Renderer.TEX_NEON_CYAN);

        // Rotating glow ring (two thin bars sweeping around the beam)
        for (int ring = 0; ring < 2; ring++) {
            float ang = time * 1.5f + ring * (float) Math.PI;
            float ox = (float) Math.cos(ang) * 1.4f;
            float oz = (float) Math.sin(ang) * 1.4f;
            r.drawCube(new Vector3f(ox, padY + 0.9f + ring * 0.4f, padZ + oz),
                new Vector3f(0.25f, 0.06f, 0.25f), Renderer.TEX_NEON_GREEN);
        }

        // Swirling particle column — spiral of rising orbs
        for (int i = 0; i < 10; i++) {
            float phase = time * 2.5f + i * 0.62f;          // swirl rate + spacing
            float spiralAng = phase;
            float radius = 0.9f * (1.0f - (i / 10f));        // taper inward with height
            float ox = (float) Math.cos(spiralAng) * radius;
            float oz = (float) Math.sin(spiralAng) * radius;
            float oy = padY + 0.3f + (i / 10f) * 2.2f;       // rise with index
            // Alternate cyan/white/green for a shimmer
            int tex = (i % 3 == 0) ? Renderer.TEX_NEON_GREEN
                    : (i % 3 == 1) ? Renderer.TEX_NEON_CYAN : Renderer.TEX_WHITE;
            float sz = 0.10f + 0.06f * (float) Math.sin(time * 5f + i);
            r.drawCube(new Vector3f(ox, oy, padZ + oz), new Vector3f(sz, sz, sz), tex);
        }

        // Floating sparkle orbs around the beam (legacy — kept for density)
        for (int i = 0; i < 4; i++) {
            float ang = time * 2.0f + i * (float) Math.PI / 2f;
            float ox = (float) Math.cos(ang) * 1.2f;
            float oz = (float) Math.sin(ang) * 1.2f;
            float oy = padY + 0.5f + 0.4f * (float) Math.sin(time * 4.0f + i);
            r.drawCube(new Vector3f(ox, oy, padZ + oz),
                new Vector3f(0.12f, 0.12f, 0.12f), Renderer.TEX_NEON_GREEN);
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

        // Central fountain
        float fx = cx, fz = cz;
        // Basin
        r.drawCube(new Vector3f(fx, floorY + 0.3f, fz), new Vector3f(3f, 0.5f, 3f), Renderer.TEX_CONCRETE);
        // Water (blue)
        r.drawCube(new Vector3f(fx, floorY + 0.55f, fz), new Vector3f(2.6f, 0.05f, 2.6f), Renderer.TEX_NEON_CYAN);
        // Center pillar
        r.drawCube(new Vector3f(fx, floorY + 0.8f, fz), new Vector3f(0.3f, 0.6f, 0.3f), Renderer.TEX_WHITE);
        // Top tier
        r.drawCube(new Vector3f(fx, floorY + 1.1f, fz), new Vector3f(1.2f, 0.15f, 1.2f), Renderer.TEX_WHITE);
        r.drawCube(new Vector3f(fx, floorY + 1.2f, fz), new Vector3f(0.8f, 0.05f, 0.8f), Renderer.TEX_NEON_CYAN);

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

    private void renderOutside(Renderer r, float floorY, float outZ) {
        // The expanded open world (Phase I) — ~300×250m of forest, lake,
        // mansion, hospital, factory, TOC tree, towns, shops, inventions.
        // Delegated to OutsideWorld; the sky dome is drawn by GameEngine, so
        // the old flat band-stack sky (which never meshed at the horizon) is
        // gone. Sun/moon/stars are billboarded against the sky dome here.
        outsideWorld.render(r, floorY, camX, camZ, time);
        renderCelestial(r, floorY, outZ);
    }

    /** Sun/moon/stars billboarded against the sky dome (follows the camera). */
    private void renderCelestial(Renderer r, float floorY, float outZ) {
        int hour = java.time.LocalTime.now().getHour();
        boolean night = hour < 6 || hour >= 20;
        boolean dusk = hour >= 18 && hour < 20;
        // Billboard the celestial body high in the sky, offset from the camera
        // so it stays visible as the player walks the big world.
        float skyY = floorY + 30f;
        float backZ = camZ - 40f;   // far behind the camera's view
        if (night) {
            r.drawCubeColor(new Vector3f(camX - 20f, skyY, backZ),
                new Vector3f(3f, 3f, 0.05f), 0.85f, 0.88f, 0.95f);
            for (int ring = 1; ring <= 3; ring++) {
                float a = 0.25f * (float) Math.cos(ring * 0.9f) + 0.25f;
                float s = 3f + ring * 1.2f;
                r.drawCubeColor(new Vector3f(camX - 20f, skyY, backZ - 0.01f * ring),
                    new Vector3f(s, s, 0.03f), 0.85f * a, 0.88f * a, 0.95f * a);
            }
            // Full star field + real named constellations (Big Dipper, Orion,
            // Cassiopeia, Cygnus, Leo, Scorpius, Taurus, Lyra) with twinkle.
            Constellation.render(r, camX, skyY, camZ, time);
        } else {
            float[] core = dusk ? new float[]{1.0f, 0.55f, 0.30f} : new float[]{1.0f, 0.85f, 0.35f};
            r.drawCubeColor(new Vector3f(camX, skyY, backZ),
                new Vector3f(3.5f, 3.5f, 0.05f), core[0], core[1], core[2]);
            for (int ring = 1; ring <= 4; ring++) {
                float a = 0.5f * (float) Math.cos(ring * 0.7f) + 0.5f;
                float s = 3.5f + ring * 1.4f;
                r.drawCubeColor(new Vector3f(camX, skyY, backZ - 0.01f * ring),
                    new Vector3f(s, s, 0.03f), core[0] * a, core[1] * a, core[2] * a);
            }
        }
    }

    /** Cosine-interpolated sky color at height t (0=top, 1=horizon). */
    private float[] skyColor(float t, boolean night, boolean dusk) {
        // Smooth cosine ramp between palette stops.
        float c = 0.5f - 0.5f * (float) Math.cos(t * (float) Math.PI); // 0→1 ease
        float[] top, mid, low;
        if (night)      { top = new float[]{0.02f,0.03f,0.10f}; mid = new float[]{0.04f,0.06f,0.16f}; low = new float[]{0.06f,0.08f,0.20f}; }
        else if (dusk)  { top = new float[]{0.20f,0.15f,0.40f}; mid = new float[]{0.55f,0.30f,0.55f}; low = new float[]{0.95f,0.55f,0.30f}; }
        else            { top = new float[]{0.10f,0.25f,0.55f}; mid = new float[]{0.35f,0.60f,0.85f}; low = new float[]{0.75f,0.85f,0.95f}; }
        float[] a = c < 0.5f ? lerp(top, mid, c * 2f) : lerp(mid, low, (c - 0.5f) * 2f);
        return a;
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
        int color = room.isPrivate() ? Renderer.TEX_NEON_PINK : Renderer.TEX_NEON_CYAN;
        float t = 0.06f; // trim thickness

        // Two vertical side posts
        r.drawCube(new Vector3f(wallX + offsetX, floorY + dh / 2f, doorZ - dw / 2f),
            new Vector3f(t, dh, t), color);
        r.drawCube(new Vector3f(wallX + offsetX, floorY + dh / 2f, doorZ + dw / 2f),
            new Vector3f(t, dh, t), color);
        // Top lintel
        r.drawCube(new Vector3f(wallX + offsetX, floorY + dh, doorZ),
            new Vector3f(t, t, dw), color);
        // Soft glow halo (static) behind the trim
        r.drawCube(new Vector3f(wallX + offsetX + (wallX > 0 ? -0.02f : 0.02f), floorY + dh / 2f, doorZ),
            new Vector3f(0.02f, dh + 0.2f, dw + 0.2f), Renderer.TEX_NEON_AMBER);
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
                // Poster (white)
                r.drawCube(new Vector3f(wallX + offsetX + (wallX > 0 ? -0.01f : 0.01f), py, pz),
                    new Vector3f(fd, fh, fw), Renderer.TEX_WHITE);
            }
        }
    }

    // ── Room ──

    private void renderRoom(Renderer r, Room room) {
        Vector3f c = room.getRoomCenter();
        float w = Room.ROOM_WIDTH, d = Room.ROOM_DEPTH, h = Room.ROOM_HEIGHT;
        float t = Room.WALL_THICKNESS;
        int side = room.getHallwaySide();

        // Per-room language accent — tint the whole room, then reset to neutral
        float[] tint = room.getTint();
        r.setTint(tint[0], tint[1], tint[2]);

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

        // Door panel — slides up when opened
        float doorSlide = room.getDoorOpenAmount() * dw;
        float doorPanelY = c.y - h / 2f + dw / 2f + doorSlide;
        float doorPanelH = dw * (1f - room.getDoorOpenAmount());
        if (doorPanelH > 0.01f) {
            r.drawCube(new Vector3f(c.x, doorPanelY, fz),
                new Vector3f(Room.DOOR_WIDTH - 0.04f, doorPanelH, t + 0.02f), Renderer.TEX_DOOR);
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
        float posterZ = side == 0 ? fz + 0.03f : fz - 0.03f;
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
            r.drawImageQuad(posterTex, new Vector3f(c.x, posterY, posterZ),
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

        // Bookcases on 3 walls
        renderBookcase(r, room, 0);
        renderBookcase(r, room, -1);
        renderBookcase(r, room, 1);

        r.setTint(1.0f, 1.0f, 1.0f); // reset to neutral
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
        try {
            tex = new Texture(path);
            posterTextures.put(path, tex);
            return tex;
        } catch (Exception e) {
            posterLoadFailed.add(path);
            System.err.println("[Poster] image load failed: " + path + " — " + e.getMessage());
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
