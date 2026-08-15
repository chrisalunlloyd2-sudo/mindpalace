package com.mindpalace.world;

import com.mindpalace.render.Camera;
import com.mindpalace.render.Renderer;
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

    private RepoMapper repoMapper;
    private RoomPopulator populator;

    private static final float ROOM_CULL_DISTANCE = 30.0f;

    public WorldBuilder() {
        repoMapper = new RepoMapper();
        populator = new RoomPopulator();
    }

    public void build() {
        System.out.println("[WorldBuilder] Building MindPalace world...");
        repoMapper.scanRepos(rooms);
        // Populate first so we can sort by book count
        for (Room room : rooms) populator.populateRoom(room);
        // Sort by book count (proxy for repo size) — largest repos first
        rooms.sort((a, b) -> Integer.compare(b.getBooks().size(), a.getBooks().size()));
        System.out.println("[WorldBuilder] Mapped " + rooms.size() + " repos to rooms (sorted by size)");
        layoutWorld();
        System.out.println("[WorldBuilder] World built: " + hallways.size() + " hallways, " + rooms.size() + " rooms");
    }

    private void layoutWorld() {
        int total = rooms.size();
        int floors = 4;
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

        for (Hallway hw : hallways) {
            if (isHallwayNear(hw, camPos)) renderHallway(r, hw);
        }

        for (Room room : rooms) {
            Vector3f c = room.getRoomCenter();
            float dist = camPos.distance(c);
            if (dist > ROOM_CULL_DISTANCE) continue;
            if (!isInFront(camPos, camFront, c, dist)) continue;
            renderRoom(r, room);
        }
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
        // End walls
        r.drawCube(new Vector3f(cx, s.y + h / 2f, hw.getEnd().z), new Vector3f(w, h, wallT), Renderer.TEX_WALLPAPER);
        r.drawCube(new Vector3f(cx, s.y + h / 2f, s.z), new Vector3f(w, h, wallT), Renderer.TEX_WALLPAPER);
        // Side walls with doors
        renderWallWithDoors(r, s, hw, -1);
        renderWallWithDoors(r, s, hw, 1);

        // Floor indicator sign at hallway start
        float signX = 0, signY = s.y + h - 0.5f, signZ = s.z + 1.5f;
        r.drawCube(new Vector3f(signX, signY, signZ),
            new Vector3f(1.5f, 0.4f, 0.08f), Renderer.TEX_NEON_GREEN);
        // Teleport pad at hallway end (to next floor)
        if (hw.getFloor() < hallways.size() - 1) {
            float padZ = hw.getEnd().z - 1.0f;
            r.drawCube(new Vector3f(0, s.y + 0.06f, padZ),
                new Vector3f(1.5f, 0.06f, 1.5f), Renderer.TEX_NEON_CYAN);
        }

        // Poster frames on walls between doors
        renderPosters(r, s, hw);

        // Stairwell between floors
        if (hw.getFloor() < hallways.size() - 1) {
            float stairZ = hw.getEnd().z + 2.0f;
            renderStairwell(r, s.y, stairZ);
        }
        // Special areas only on floor 0
        if (hw.getFloor() == 0) {
            float stairZ = hw.getEnd().z + 2.0f;
            renderLaboratory(r, s.y, stairZ + 6.0f);
            renderCourtyard(r, s.y, stairZ + 18.0f);
            renderOutside(r, s.y, stairZ + 34.0f);
        }
    }

    private void renderStairwell(Renderer r, float floorY, float stairZ) {
        float sw = 1.5f, sh = 0.2f, sd = 0.5f;
        int steps = 8;
        float totalRise = HALLWAY_HEIGHT + 1.0f;
        float stepRise = totalRise / steps;
        float stepRun = 0.6f;

        for (int i = 0; i < steps; i++) {
            float sy = floorY + i * stepRise + sh / 2f;
            float sz = stairZ + i * stepRun;
            r.drawCube(new Vector3f(0, sy, sz), new Vector3f(sw, sh, sd), Renderer.TEX_HARDWOOD);
        }

        // Railings
        float railH = 0.9f;
        for (int i = 0; i <= steps; i++) {
            float ry = floorY + i * stepRise + railH / 2f;
            float rz = stairZ + i * stepRun;
            r.drawCube(new Vector3f(-sw / 2f - 0.1f, ry, rz), new Vector3f(0.05f, railH, 0.05f), Renderer.TEX_DOOR);
            r.drawCube(new Vector3f(sw / 2f + 0.1f, ry, rz), new Vector3f(0.05f, railH, 0.05f), Renderer.TEX_DOOR);
        }
        // Top rail
        float topY = floorY + totalRise + railH;
        float topZ = stairZ + steps * stepRun;
        r.drawCube(new Vector3f(-sw / 2f - 0.1f, topY, stairZ + steps * stepRun / 2f),
            new Vector3f(0.05f, 0.05f, steps * stepRun), Renderer.TEX_DOOR);
        r.drawCube(new Vector3f(sw / 2f + 0.1f, topY, stairZ + steps * stepRun / 2f),
            new Vector3f(0.05f, 0.05f, steps * stepRun), Renderer.TEX_DOOR);
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
        float ow = 30f, od = 25f;
        float cx = 0, cz = outZ + od / 2f;

        // Grass ground
        r.drawCube(new Vector3f(cx, floorY - 0.1f, cz), new Vector3f(ow, 0.2f, od), Renderer.TEX_GRASS);

        // Sun (yellow sphere approximation — large flat disc on back wall)
        r.drawCube(new Vector3f(cx, floorY + 8f, cz + od / 2f - 0.1f),
            new Vector3f(3f, 3f, 0.1f), Renderer.TEX_NEON_AMBER);

        // Trees (trunk + canopy)
        float[][] treePos = {{-10, 5}, {-6, 12}, {8, 8}, {12, 15}, {-12, 18}, {10, 20}};
        for (float[] tp : treePos) {
            float tx = tp[0], tz = outZ + tp[1];
            // Trunk
            r.drawCube(new Vector3f(tx, floorY + 1.5f, tz), new Vector3f(0.3f, 3f, 0.3f), Renderer.TEX_WOOD);
            // Canopy (3 layers)
            r.drawCube(new Vector3f(tx, floorY + 3.5f, tz), new Vector3f(2.5f, 1.5f, 2.5f), Renderer.TEX_GRASS);
            r.drawCube(new Vector3f(tx, floorY + 4.5f, tz), new Vector3f(1.8f, 1.2f, 1.8f), Renderer.TEX_GRASS);
            r.drawCube(new Vector3f(tx, floorY + 5.3f, tz), new Vector3f(1.0f, 0.8f, 1.0f), Renderer.TEX_GRASS);
        }

        // Lake
        float lz = outZ + od - 3f;
        r.drawCube(new Vector3f(cx - 5f, floorY + 0.05f, lz), new Vector3f(10f, 0.1f, 6f), Renderer.TEX_WATER);

        // Lakehouse
        float lhx = cx + 8f, lhz = outZ + od - 6f;
        // Floor
        r.drawCube(new Vector3f(lhx, floorY + 0.1f, lhz), new Vector3f(5f, 0.15f, 4f), Renderer.TEX_WOOD);
        // Walls
        r.drawCube(new Vector3f(lhx, floorY + 1.5f, lhz - 2f), new Vector3f(5f, 3f, 0.2f), Renderer.TEX_WOOD);
        r.drawCube(new Vector3f(lhx, floorY + 1.5f, lhz + 2f), new Vector3f(5f, 3f, 0.2f), Renderer.TEX_WOOD);
        r.drawCube(new Vector3f(lhx - 2.5f, floorY + 1.5f, lhz), new Vector3f(0.2f, 3f, 4f), Renderer.TEX_WOOD);
        r.drawCube(new Vector3f(lhx + 2.5f, floorY + 1.5f, lhz), new Vector3f(0.2f, 3f, 4f), Renderer.TEX_WOOD);
        // Roof (A-frame)
        r.drawCube(new Vector3f(lhx, floorY + 3.2f, lhz), new Vector3f(5.5f, 0.15f, 4.5f), Renderer.TEX_DOOR);
        // Door
        r.drawCube(new Vector3f(lhx, floorY + 1.0f, lhz - 2.1f), new Vector3f(1.2f, 2f, 0.1f), Renderer.TEX_DOOR);
        // Windows
        r.drawCube(new Vector3f(lhx - 1.5f, floorY + 1.8f, lhz - 2.1f), new Vector3f(0.8f, 0.8f, 0.05f), Renderer.TEX_NEON_CYAN);
        r.drawCube(new Vector3f(lhx + 1.5f, floorY + 1.8f, lhz - 2.1f), new Vector3f(0.8f, 0.8f, 0.05f), Renderer.TEX_NEON_CYAN);
    }

    private void renderWallWithDoors(Renderer r, Vector3f s, Hallway hw, int side) {
        float h = hw.getHeight(), len = hw.getEnd().z - s.z;
        float wallX = side * hw.getWidth() / 2f, wallT = 0.25f;
        float dw = Room.DOOR_WIDTH, dh = Room.DOOR_HEIGHT;

        List<Float> doorZs = new ArrayList<>();
        for (Room room : rooms)
            if (room.getHallwaySide() == (side == -1 ? 0 : 1) && room.getFloor() == hw.getFloor())
                if (room.getDoorPosition() != null) doorZs.add(room.getDoorPosition().z);
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
                renderNeonSign(r, wallX, s.y + h - 0.3f, dp.z, room);
                renderExitSign(r, wallX, s.y + h - 0.1f, dp.z);
            }
        }
    }

    private void renderNeonSign(Renderer r, float wallX, float signY, float signZ, Room room) {
        float signW = 2.0f, signH = 0.35f, signD = 0.06f;
        float glowW = signW + 0.15f, glowH = signH + 0.15f, glowD = 0.03f;
        float offsetX = wallX > 0 ? -0.15f : 0.15f;
        int neonColor = room.isPrivate() ? Renderer.TEX_NEON_PINK : Renderer.TEX_NEON_CYAN;

        r.drawCube(new Vector3f(wallX + offsetX, signY, signZ),
            new Vector3f(glowD, glowH, glowW), Renderer.TEX_NEON_AMBER);
        r.drawCube(new Vector3f(wallX + offsetX + (wallX > 0 ? -0.02f : 0.02f), signY, signZ),
            new Vector3f(signD, signH, signW), neonColor);
        float bw = 0.04f;
        r.drawCube(new Vector3f(wallX + offsetX, signY + signH / 2f + 0.06f, signZ),
            new Vector3f(glowD + 0.02f, 0.06f, bw), Renderer.TEX_DOOR);
        r.drawCube(new Vector3f(wallX + offsetX, signY - signH / 2f - 0.06f, signZ),
            new Vector3f(glowD + 0.02f, 0.06f, bw), Renderer.TEX_DOOR);
    }

    private void renderExitSign(Renderer r, float wallX, float signY, float signZ) {
        float esw = 0.5f, esh = 0.2f, esd = 0.04f;
        float offsetX = wallX > 0 ? -0.15f : 0.15f;
        r.drawCube(new Vector3f(wallX + offsetX, signY, signZ),
            new Vector3f(esd, esh, esw), Renderer.TEX_NEON_GREEN);
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

        // Doorknob
        float knobX = c.x + dh - 0.1f;
        float knobY = c.y - h / 2f + dw / 2f;
        float knobZ = side == 0 ? fz + 0.06f : fz - 0.06f;
        r.drawCube(new Vector3f(knobX, knobY, knobZ), new Vector3f(0.06f, 0.06f, 0.06f), Renderer.TEX_PLAQUE);

        // Table + chairs in center
        renderFurniture(r, c, w, d, h);

        // Ornaments — potted plant + lamp
        renderOrnaments(r, c, w, d, h, side);

        // Bookcases on 3 walls
        renderBookcase(r, room, 0);
        renderBookcase(r, room, -1);
        renderBookcase(r, room, 1);
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

        // Books grouped by language
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
                float offset = -usableWidth / 2f + placed * (bookW + bookGap) + bookW / 2f;
                if (wallDir == 0) {
                    r.drawCube(new Vector3f(caseX + offset, sy, caseZ), new Vector3f(bookW, bookH, bookD), book.getTextureId());
                    book.setWorldPosition(caseX + offset, sy, caseZ);
                } else {
                    r.drawCube(new Vector3f(caseX, sy, caseZ + offset), new Vector3f(bookD, bookH, bookW), book.getTextureId());
                    book.setWorldPosition(caseX, sy, caseZ + offset);
                }
                bookInGroup++; placed++;
            }
        }
    }

    public List<Room> getRooms() { return rooms; }
    public List<Hallway> getHallways() { return hallways; }

    public Room findRoomAt(Vector3f pos) {
        for (Room room : rooms) {
            Vector3f c = room.getRoomCenter();
            if (Math.abs(pos.x - c.x) < Room.ROOM_WIDTH / 2f && Math.abs(pos.z - c.z) < Room.ROOM_DEPTH / 2f) return room;
        }
        return null;
    }
}
