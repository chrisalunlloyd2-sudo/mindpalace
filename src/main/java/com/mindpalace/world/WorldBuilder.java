package com.mindpalace.world;

import com.mindpalace.render.Renderer;
import org.joml.Vector3f;
import java.util.ArrayList;
import java.util.List;

/**
 * Procedural world builder — hallways, rooms with doors, shelves.
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

        // Ground floor
        Hallway h0 = new Hallway(0);
        float len = perSide * DOOR_SPACING + HALLWAY_START_OFFSET * 2;
        h0.setStart(new Vector3f(0, 0, 0));
        h0.setEnd(new Vector3f(0, 0, len));
        h0.setWidth(HALLWAY_WIDTH);
        h0.setHeight(HALLWAY_HEIGHT);
        hallways.add(h0);

        // Upper floor
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

    public void render(Renderer r) {
        for (Hallway hw : hallways) renderHallway(r, hw);
        for (Room room : rooms) renderRoom(r, room);
    }

    private void renderHallway(Renderer r, Hallway hw) {
        Vector3f s = hw.getStart();
        float w = hw.getWidth(), h = hw.getHeight();
        float len = hw.getEnd().z - s.z;
        float cx = 0, cz = s.z + len / 2f;

        // Floor — dark stone
        r.drawCube(new Vector3f(cx, s.y, cz), new Vector3f(w, 0.15f, len), Renderer.TEX_FLOOR);
        // Ceiling — dark grey
        r.drawCube(new Vector3f(cx, s.y + h, cz), new Vector3f(w, 0.15f, len), Renderer.TEX_CEILING);
        // Left wall — brown stone
        r.drawCube(new Vector3f(-w / 2f, s.y + h / 2f, cz), new Vector3f(0.25f, h, len), Renderer.TEX_WALL);
        // Right wall
        r.drawCube(new Vector3f(w / 2f, s.y + h / 2f, cz), new Vector3f(0.25f, h, len), Renderer.TEX_WALL);
        // End wall
        r.drawCube(new Vector3f(cx, s.y + h / 2f, hw.getEnd().z), new Vector3f(w, h, 0.25f), Renderer.TEX_WALL);
        // Start wall
        r.drawCube(new Vector3f(cx, s.y + h / 2f, s.z), new Vector3f(w, h, 0.25f), Renderer.TEX_WALL);
    }

    private void renderRoom(Renderer r, Room room) {
        Vector3f c = room.getRoomCenter();
        float w = Room.ROOM_WIDTH, d = Room.ROOM_DEPTH, h = Room.ROOM_HEIGHT;
        float t = Room.WALL_THICKNESS;
        int side = room.getHallwaySide();

        // Floor
        r.drawCube(new Vector3f(c.x, c.y - h / 2f, c.z), new Vector3f(w, 0.1f, d), Renderer.TEX_FLOOR);
        // Ceiling
        r.drawCube(new Vector3f(c.x, c.y + h / 2f, c.z), new Vector3f(w, 0.1f, d), Renderer.TEX_CEILING);

        // Back wall
        float bz = side == 0 ? c.z + d / 2f : c.z - d / 2f;
        r.drawCube(new Vector3f(c.x, c.y, bz), new Vector3f(w, h, t), Renderer.TEX_WALL);

        // Side walls
        r.drawCube(new Vector3f(c.x - w / 2f, c.y, c.z), new Vector3f(t, h, d), Renderer.TEX_WALL);
        r.drawCube(new Vector3f(c.x + w / 2f, c.y, c.z), new Vector3f(t, h, d), Renderer.TEX_WALL);

        // Front wall with door gap
        float fz = side == 0 ? c.z - d / 2f : c.z + d / 2f;
        float dh = Room.DOOR_WIDTH / 2f;
        float dw = Room.DOOR_HEIGHT;

        // Left of door
        float leftW = (w - Room.DOOR_WIDTH) / 2f;
        r.drawCube(new Vector3f(c.x - w / 2f + leftW / 2f, c.y, fz),
            new Vector3f(leftW, h, t), Renderer.TEX_WALL);
        // Right of door
        r.drawCube(new Vector3f(c.x + w / 2f - leftW / 2f, c.y, fz),
            new Vector3f(leftW, h, t), Renderer.TEX_WALL);
        // Above door
        r.drawCube(new Vector3f(c.x, c.y + h / 2f - (h - dw) / 2f, fz),
            new Vector3f(Room.DOOR_WIDTH, h - dw, t), Renderer.TEX_WALL);

        // Door frame (vertical sides)
        float frameT = 0.08f;
        r.drawCube(new Vector3f(c.x - dh, c.y - h / 2f + dw / 2f, fz),
            new Vector3f(frameT, dw, frameT), Renderer.TEX_DOOR);
        r.drawCube(new Vector3f(c.x + dh, c.y - h / 2f + dw / 2f, fz),
            new Vector3f(frameT, dw, frameT), Renderer.TEX_DOOR);
        // Door top frame
        r.drawCube(new Vector3f(c.x, c.y - h / 2f + dw, fz),
            new Vector3f(Room.DOOR_WIDTH, frameT, frameT), Renderer.TEX_DOOR);

        // Door plaque above door
        r.drawCube(new Vector3f(c.x, c.y - h / 2f + dw + 0.15f, fz),
            new Vector3f(Room.DOOR_WIDTH * 0.8f, 0.15f, 0.05f), Renderer.TEX_PLAQUE);

        // Bookshelves
        renderShelves(r, room);
    }

    private void renderShelves(Renderer r, Room room) {
        Vector3f c = room.getRoomCenter();
        float sy = c.y - Room.ROOM_HEIGHT / 2f + 0.7f;
        float ss = 0.45f;
        int rows = 3;
        float bz = room.getHallwaySide() == 0 ? c.z + Room.ROOM_DEPTH / 2f - 0.3f
                                              : c.z - Room.ROOM_DEPTH / 2f + 0.3f;

        for (int row = 0; row < rows; row++) {
            float y = sy + row * ss;
            // Shelf board
            r.drawCube(new Vector3f(c.x, y, bz),
                new Vector3f(Room.ROOM_WIDTH - 0.6f, 0.04f, 0.35f), Renderer.TEX_SHELF);

            // Books
            List<Book> books = room.getBooks();
            int perShelf = Math.min(20, books.size() / rows);
            for (int b = 0; b < perShelf; b++) {
                int bi = row * perShelf + b;
                if (bi >= books.size()) break;
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
