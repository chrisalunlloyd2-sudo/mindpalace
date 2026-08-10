package com.mindpalace.world;

import com.mindpalace.render.Camera;
import com.mindpalace.render.Renderer;
import org.joml.Vector3f;
import java.util.ArrayList;
import java.util.List;

/**
 * Procedural world builder — hallways, rooms with doors, shelves.
 * Frustum + distance culling for performance.
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

    // Culling
    private static final float CULL_DISTANCE = 40.0f;
    private static final float ROOM_CULL_DISTANCE = 25.0f;
    private static final int MAX_VISIBLE_BOOKS = 20;

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

        // Render hallways (always visible — cheap, 6 cubes each)
        for (Hallway hw : hallways) {
            if (isHallwayVisible(hw, camPos)) renderHallway(r, hw);
        }

        // Render only nearby rooms
        for (Room room : rooms) {
            Vector3f c = room.getRoomCenter();
            float dist = camPos.distance(c);
            if (dist > ROOM_CULL_DISTANCE) continue;
            // Frustum check: is room roughly in front of camera?
            if (!isInFront(camPos, camFront, c, dist)) continue;
            renderRoom(r, room);
        }
    }

    private boolean isHallwayVisible(Hallway hw, Vector3f camPos) {
        float hz = hw.getStart().z;
        float len = hw.getEnd().z - hz;
        float midZ = hz + len / 2f;
        float dx = camPos.x;
        float dz = camPos.z - midZ;
        return Math.abs(dx) < HALLWAY_WIDTH + 5 && Math.abs(dz) < len / 2f + CULL_DISTANCE;
    }

    private boolean isInFront(Vector3f camPos, Vector3f camFront, Vector3f target, float dist) {
        // Simple dot-product check: is target within ~120° FOV?
        float dx = target.x - camPos.x;
        float dz = target.z - camPos.z;
        float len = (float) Math.sqrt(dx * dx + dz * dz);
        if (len < 0.01f) return true;
        float dot = (dx / len) * camFront.x + (dz / len) * camFront.z;
        return dot > -0.3f || dist < 8.0f; // behind but close = still render
    }

    private void renderHallway(Renderer r, Hallway hw) {
        Vector3f s = hw.getStart();
        float w = hw.getWidth(), h = hw.getHeight();
        float len = hw.getEnd().z - s.z;
        float cx = 0, cz = s.z + len / 2f;

        r.drawCube(new Vector3f(cx, s.y, cz), new Vector3f(w, 0.15f, len), Renderer.TEX_FLOOR);
        r.drawCube(new Vector3f(cx, s.y + h, cz), new Vector3f(w, 0.15f, len), Renderer.TEX_CEILING);
        r.drawCube(new Vector3f(-w / 2f, s.y + h / 2f, cz), new Vector3f(0.25f, h, len), Renderer.TEX_WALL);
        r.drawCube(new Vector3f(w / 2f, s.y + h / 2f, cz), new Vector3f(0.25f, h, len), Renderer.TEX_WALL);
        r.drawCube(new Vector3f(cx, s.y + h / 2f, hw.getEnd().z), new Vector3f(w, h, 0.25f), Renderer.TEX_WALL);
        r.drawCube(new Vector3f(cx, s.y + h / 2f, s.z), new Vector3f(w, h, 0.25f), Renderer.TEX_WALL);
    }

    private void renderRoom(Renderer r, Room room) {
        Vector3f c = room.getRoomCenter();
        float w = Room.ROOM_WIDTH, d = Room.ROOM_DEPTH, h = Room.ROOM_HEIGHT;
        float t = Room.WALL_THICKNESS;
        int side = room.getHallwaySide();

        r.drawCube(new Vector3f(c.x, c.y - h / 2f, c.z), new Vector3f(w, 0.1f, d), Renderer.TEX_FLOOR);
        r.drawCube(new Vector3f(c.x, c.y + h / 2f, c.z), new Vector3f(w, 0.1f, d), Renderer.TEX_CEILING);

        float bz = side == 0 ? c.z + d / 2f : c.z - d / 2f;
        r.drawCube(new Vector3f(c.x, c.y, bz), new Vector3f(w, h, t), Renderer.TEX_WALL);
        r.drawCube(new Vector3f(c.x - w / 2f, c.y, c.z), new Vector3f(t, h, d), Renderer.TEX_WALL);
        r.drawCube(new Vector3f(c.x + w / 2f, c.y, c.z), new Vector3f(t, h, d), Renderer.TEX_WALL);

        float fz = side == 0 ? c.z - d / 2f : c.z + d / 2f;
        float dh = Room.DOOR_WIDTH / 2f;
        float dw = Room.DOOR_HEIGHT;
        float leftW = (w - Room.DOOR_WIDTH) / 2f;

        r.drawCube(new Vector3f(c.x - w / 2f + leftW / 2f, c.y, fz), new Vector3f(leftW, h, t), Renderer.TEX_WALL);
        r.drawCube(new Vector3f(c.x + w / 2f - leftW / 2f, c.y, fz), new Vector3f(leftW, h, t), Renderer.TEX_WALL);
        r.drawCube(new Vector3f(c.x, c.y + h / 2f - (h - dw) / 2f, fz), new Vector3f(Room.DOOR_WIDTH, h - dw, t), Renderer.TEX_WALL);

        float frameT = 0.08f;
        r.drawCube(new Vector3f(c.x - dh, c.y - h / 2f + dw / 2f, fz), new Vector3f(frameT, dw, frameT), Renderer.TEX_DOOR);
        r.drawCube(new Vector3f(c.x + dh, c.y - h / 2f + dw / 2f, fz), new Vector3f(frameT, dw, frameT), Renderer.TEX_DOOR);
        r.drawCube(new Vector3f(c.x, c.y - h / 2f + dw, fz), new Vector3f(Room.DOOR_WIDTH, frameT, frameT), Renderer.TEX_DOOR);
        r.drawCube(new Vector3f(c.x, c.y - h / 2f + dw + 0.15f, fz), new Vector3f(Room.DOOR_WIDTH * 0.8f, 0.15f, 0.05f), Renderer.TEX_PLAQUE);

        renderShelves(r, room);
    }

    private void renderShelves(Renderer r, Room room) {
        Vector3f c = room.getRoomCenter();
        float sy = c.y - Room.ROOM_HEIGHT / 2f + 0.7f;
        float ss = 0.45f;
        int rows = 3;
        float bz = room.getHallwaySide() == 0 ? c.z + Room.ROOM_DEPTH / 2f - 0.3f
                                              : c.z - Room.ROOM_DEPTH / 2f + 0.3f;

        List<Book> books = room.getBooks();
        int totalBooks = Math.min(books.size(), MAX_VISIBLE_BOOKS);
        int perShelf = Math.max(1, totalBooks / rows);

        for (int row = 0; row < rows; row++) {
            float y = sy + row * ss;
            r.drawCube(new Vector3f(c.x, y, bz),
                new Vector3f(Room.ROOM_WIDTH - 0.6f, 0.04f, 0.35f), Renderer.TEX_SHELF);

            for (int b = 0; b < perShelf; b++) {
                int bi = row * perShelf + b;
                if (bi >= totalBooks) break;
                Book book = books.get(bi);
                float bx = c.x - (Room.ROOM_WIDTH - 0.6f) / 2f + 0.1f + b * 0.15f;
                r.drawCube(new Vector3f(bx, y + 0.15f, bz),
                    new Vector3f(book.getThickness(), 0.28f, 0.22f), Renderer.TEX_BOOK);
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
