package com.mindpalace.world;

import com.mindpalace.render.Camera;
import com.mindpalace.render.Renderer;
import org.joml.Vector3f;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Procedural world builder — hallways with door cutouts, rooms with 3-wall bookshelves.
 * Frustum + distance culling. Books organized by language.
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
    private static final int MAX_BOOKS_PER_WALL = 30;

    public WorldBuilder() {
        repoMapper = new RepoMapper();
        populator = new RoomPopulator();
    }

    public void build() {
        System.out.println("[WorldBuilder] Building MindPalace world...");
        repoMapper.scanRepos(rooms);
        System.out.println("[WorldBuilder] Mapped " + rooms.size() + " repos to rooms");
        layoutWorld();
        for (Room room : rooms) populator.populateRoom(room);
        System.out.println("[WorldBuilder] World built: " + hallways.size() + " hallways, " + rooms.size() + " rooms");
    }

    private void layoutWorld() {
        int total = rooms.size();
        int perFloor = (total + 1) / 2;
        int perSide = (perFloor + 1) / 2;

        Hallway h0 = new Hallway(0);
        float len = perSide * DOOR_SPACING + HALLWAY_START_OFFSET * 2;
        h0.setStart(new Vector3f(0, 0, 0));
        h0.setEnd(new Vector3f(0, 0, len));
        h0.setWidth(HALLWAY_WIDTH);
        h0.setHeight(HALLWAY_HEIGHT);
        hallways.add(h0);

        Hallway h1 = new Hallway(1);
        h1.setStart(new Vector3f(0, HALLWAY_HEIGHT + 1.0f, len + 4.0f));
        h1.setEnd(new Vector3f(0, HALLWAY_HEIGHT + 1.0f, len + 4.0f + len));
        h1.setWidth(HALLWAY_WIDTH);
        h1.setHeight(HALLWAY_HEIGHT);
        hallways.add(h1);

        int idx = 0;
        for (int floor = 0; floor < 2 && idx < total; floor++) {
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
        float dx = target.x - camPos.x;
        float dz = target.z - camPos.z;
        float len = (float) Math.sqrt(dx * dx + dz * dz);
        if (len < 0.01f) return true;
        float dot = (dx / len) * camFront.x + (dz / len) * camFront.z;
        return dot > -0.3f || dist < 8.0f;
    }

    private void renderHallway(Renderer r, Hallway hw) {
        Vector3f s = hw.getStart();
        float w = hw.getWidth(), h = hw.getHeight();
        float len = hw.getEnd().z - s.z;
        float cx = 0, cz = s.z + len / 2f;
        float wallT = 0.25f;

        r.drawCube(new Vector3f(cx, s.y, cz), new Vector3f(w, 0.15f, len), Renderer.TEX_FLOOR);
        r.drawCube(new Vector3f(cx, s.y + h, cz), new Vector3f(w, 0.15f, len), Renderer.TEX_CEILING);
        r.drawCube(new Vector3f(cx, s.y + h / 2f, hw.getEnd().z), new Vector3f(w, h, wallT), Renderer.TEX_WALL);
        r.drawCube(new Vector3f(cx, s.y + h / 2f, s.z), new Vector3f(w, h, wallT), Renderer.TEX_WALL);
        renderWallWithDoors(r, s, hw, -1);
        renderWallWithDoors(r, s, hw, 1);
    }

    private void renderWallWithDoors(Renderer r, Vector3f s, Hallway hw, int side) {
        float h = hw.getHeight();
        float len = hw.getEnd().z - s.z;
        float wallX = side * hw.getWidth() / 2f;
        float wallT = 0.25f;
        float dw = Room.DOOR_WIDTH;
        float dh = Room.DOOR_HEIGHT;

        List<Float> doorZs = new ArrayList<>();
        for (Room room : rooms) {
            if (room.getHallwaySide() == (side == -1 ? 0 : 1) && room.getFloor() == hw.getFloor()) {
                Vector3f dp = room.getDoorPosition();
                if (dp != null) doorZs.add(dp.z);
            }
        }
        doorZs.sort(Float::compare);

        float prevZ = s.z;
        for (float dz : doorZs) {
            float segStart = prevZ;
            float segEnd = dz - dw / 2f;
            if (segEnd > segStart) {
                float segCz = (segStart + segEnd) / 2f;
                float segLen = segEnd - segStart;
                r.drawCube(new Vector3f(wallX, s.y + h / 2f, segCz),
                    new Vector3f(wallT, h, segLen), Renderer.TEX_WALL);
            }
            float aboveH = h - dh;
            if (aboveH > 0) {
                r.drawCube(new Vector3f(wallX, s.y + dh + aboveH / 2f, dz),
                    new Vector3f(wallT, aboveH, dw), Renderer.TEX_WALL);
            }
            prevZ = dz + dw / 2f;
        }
        if (prevZ < s.z + len) {
            float segCz = (prevZ + s.z + len) / 2f;
            float segLen = s.z + len - prevZ;
            r.drawCube(new Vector3f(wallX, s.y + h / 2f, segCz),
                new Vector3f(wallT, h, segLen), Renderer.TEX_WALL);
        }
    }

    private void renderRoom(Renderer r, Room room) {
        Vector3f c = room.getRoomCenter();
        float w = Room.ROOM_WIDTH, d = Room.ROOM_DEPTH, h = Room.ROOM_HEIGHT;
        float t = Room.WALL_THICKNESS;
        int side = room.getHallwaySide();

        // Floor + ceiling
        r.drawCube(new Vector3f(c.x, c.y - h / 2f, c.z), new Vector3f(w, 0.1f, d), Renderer.TEX_FLOOR);
        r.drawCube(new Vector3f(c.x, c.y + h / 2f, c.z), new Vector3f(w, 0.1f, d), Renderer.TEX_CEILING);

        // Back wall (solid — shelves go here)
        float bz = side == 0 ? c.z + d / 2f : c.z - d / 2f;
        r.drawCube(new Vector3f(c.x, c.y, bz), new Vector3f(w, h, t), Renderer.TEX_WALL);

        // Side walls (solid — shelves go here)
        r.drawCube(new Vector3f(c.x - w / 2f, c.y, c.z), new Vector3f(t, h, d), Renderer.TEX_WALL);
        r.drawCube(new Vector3f(c.x + w / 2f, c.y, c.z), new Vector3f(t, h, d), Renderer.TEX_WALL);

        // Front wall with door gap
        float fz = side == 0 ? c.z - d / 2f : c.z + d / 2f;
        float dh = Room.DOOR_WIDTH / 2f;
        float dw = Room.DOOR_HEIGHT;
        float leftW = (w - Room.DOOR_WIDTH) / 2f;

        r.drawCube(new Vector3f(c.x - w / 2f + leftW / 2f, c.y, fz), new Vector3f(leftW, h, t), Renderer.TEX_WALL);
        r.drawCube(new Vector3f(c.x + w / 2f - leftW / 2f, c.y, fz), new Vector3f(leftW, h, t), Renderer.TEX_WALL);
        r.drawCube(new Vector3f(c.x, c.y + h / 2f - (h - dw) / 2f, fz), new Vector3f(Room.DOOR_WIDTH, h - dw, t), Renderer.TEX_WALL);

        // Door frame
        float frameT = 0.08f;
        r.drawCube(new Vector3f(c.x - dh, c.y - h / 2f + dw / 2f, fz), new Vector3f(frameT, dw, frameT), Renderer.TEX_DOOR);
        r.drawCube(new Vector3f(c.x + dh, c.y - h / 2f + dw / 2f, fz), new Vector3f(frameT, dw, frameT), Renderer.TEX_DOOR);
        r.drawCube(new Vector3f(c.x, c.y - h / 2f + dw, fz), new Vector3f(Room.DOOR_WIDTH, frameT, frameT), Renderer.TEX_DOOR);
        r.drawCube(new Vector3f(c.x, c.y - h / 2f + dw + 0.15f, fz), new Vector3f(Room.DOOR_WIDTH * 0.8f, 0.15f, 0.05f), Renderer.TEX_PLAQUE);

        // Bookshelves on 3 walls
        renderShelvesOnWall(r, room, 0); // back wall
        renderShelvesOnWall(r, room, -1); // left wall
        renderShelvesOnWall(r, room, 1);  // right wall
    }

    /** wallDir: 0=back, -1=left, 1=right */
    private void renderShelvesOnWall(Renderer r, Room room, int wallDir) {
        Vector3f c = room.getRoomCenter();
        float w = Room.ROOM_WIDTH, d = Room.ROOM_DEPTH, h = Room.ROOM_HEIGHT;
        int side = room.getHallwaySide();
        float shelfY = c.y - h / 2f + 0.6f;
        float shelfSpacing = 0.42f;
        int rows = 3;
        float shelfDepth = 0.35f;
        float shelfThick = 0.04f;

        // Wall position and shelf width
        float wallX, wallZ, shelfWidth;
        if (wallDir == 0) {
            // Back wall
            wallZ = side == 0 ? c.z + d / 2f - 0.25f : c.z - d / 2f + 0.25f;
            wallX = c.x;
            shelfWidth = w - 0.6f;
        } else if (wallDir == -1) {
            // Left wall
            wallX = c.x - w / 2f + 0.25f;
            wallZ = c.z;
            shelfWidth = d - 0.6f;
        } else {
            // Right wall
            wallX = c.x + w / 2f - 0.25f;
            wallZ = c.z;
            shelfWidth = d - 0.6f;
        }

        // Get books for this wall (partition by index)
        List<Book> allBooks = room.getBooks();
        int wallIndex = wallDir == 0 ? 0 : (wallDir == -1 ? 1 : 2);
        int totalWalls = 3;
        int perWall = Math.min(MAX_BOOKS_PER_WALL, allBooks.size() / totalWalls);
        int startIdx = wallIndex * perWall;
        int endIdx = Math.min(startIdx + perWall, allBooks.size());
        if (startIdx >= allBooks.size()) return;

        int perShelf = Math.max(1, (endIdx - startIdx) / rows);

        for (int row = 0; row < rows; row++) {
            float y = shelfY + row * shelfSpacing;

            // Shelf board
            if (wallDir == 0) {
                r.drawCube(new Vector3f(wallX, y, wallZ),
                    new Vector3f(shelfWidth, shelfThick, shelfDepth), Renderer.TEX_SHELF);
            } else {
                r.drawCube(new Vector3f(wallX, y, wallZ),
                    new Vector3f(shelfDepth, shelfThick, shelfWidth), Renderer.TEX_SHELF);
            }

            // Books on this shelf row
            for (int b = 0; b < perShelf; b++) {
                int bi = startIdx + row * perShelf + b;
                if (bi >= endIdx) break;
                Book book = allBooks.get(bi);

                float offset = b * 0.14f - (perShelf - 1) * 0.07f;
                float bx, bz;
                if (wallDir == 0) {
                    bx = wallX - shelfWidth / 2f + 0.1f + b * 0.14f;
                    bz = wallZ;
                    r.drawCube(new Vector3f(bx, y + 0.14f, bz),
                        new Vector3f(book.getThickness(), 0.26f, 0.20f), book.getTextureId());
                } else if (wallDir == -1) {
                    bx = wallX;
                    bz = wallZ - shelfWidth / 2f + 0.1f + b * 0.14f;
                    r.drawCube(new Vector3f(bx, y + 0.14f, bz),
                        new Vector3f(0.20f, 0.26f, book.getThickness()), book.getTextureId());
                } else {
                    bx = wallX;
                    bz = wallZ - shelfWidth / 2f + 0.1f + b * 0.14f;
                    r.drawCube(new Vector3f(bx, y + 0.14f, bz),
                        new Vector3f(0.20f, 0.26f, book.getThickness()), book.getTextureId());
                }
            }
        }
    }

    public List<Room> getRooms() { return rooms; }
    public List<Hallway> getHallways() { return hallways; }

    public Room findRoomAt(Vector3f pos) {
        for (Room room : rooms) {
            Vector3f c = room.getRoomCenter();
            if (Math.abs(pos.x - c.x) < Room.ROOM_WIDTH / 2f &&
                Math.abs(pos.z - c.z) < Room.ROOM_DEPTH / 2f) return room;
        }
        return null;
    }
}
