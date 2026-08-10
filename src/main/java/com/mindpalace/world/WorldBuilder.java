package com.mindpalace.world;

import com.mindpalace.render.Camera;
import com.mindpalace.render.Renderer;
import org.joml.Vector3f;
import java.util.*;

/**
 * Procedural world builder — hallways with door cutouts, rooms with wooden bookcases.
 * Books grouped by file type, color-coded, protruding from walls.
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
    private static final int MAX_BOOKS_PER_WALL = 40;

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

        // Wooden bookcases on 3 walls
        renderBookcase(r, room, 0);  // back
        renderBookcase(r, room, -1); // left
        renderBookcase(r, room, 1);  // right
    }

    /** Render a full wooden bookcase on one wall, books grouped by language. */
    private void renderBookcase(Renderer r, Room room, int wallDir) {
        Vector3f c = room.getRoomCenter();
        float w = Room.ROOM_WIDTH, d = Room.ROOM_DEPTH, h = Room.ROOM_HEIGHT;
        int side = room.getHallwaySide();

        // Bookcase dimensions — protrudes 0.5m into room
        float caseDepth = 0.5f;
        float caseWidth, caseX, caseZ;
        float inset = 0.3f; // gap from wall edges

        if (wallDir == 0) {
            caseWidth = w - inset * 2;
            caseX = c.x;
            caseZ = side == 0 ? c.z + d / 2f - caseDepth / 2f - 0.1f
                              : c.z - d / 2f + caseDepth / 2f + 0.1f;
        } else if (wallDir == -1) {
            caseWidth = d - inset * 2;
            caseX = c.x - w / 2f + caseDepth / 2f + 0.1f;
            caseZ = c.z;
        } else {
            caseWidth = d - inset * 2;
            caseX = c.x + w / 2f - caseDepth / 2f - 0.1f;
            caseZ = c.z;
        }

        float caseBottom = c.y - h / 2f + 0.1f;
        float caseTop = c.y + h / 2f - 0.1f;
        float caseHeight = caseTop - caseBottom;
        float caseMidY = (caseBottom + caseTop) / 2f;

        // Bookcase back panel (thin)
        float backX = caseX, backZ = caseZ;
        if (wallDir == 0) {
            backZ = side == 0 ? caseZ + caseDepth / 2f - 0.05f : caseZ - caseDepth / 2f + 0.05f;
        } else if (wallDir == -1) {
            backX = caseX - caseDepth / 2f + 0.05f;
        } else {
            backX = caseX + caseDepth / 2f - 0.05f;
        }
        if (wallDir == 0) {
            r.drawCube(new Vector3f(backX, caseMidY, backZ),
                new Vector3f(caseWidth, caseHeight, 0.05f), Renderer.TEX_SHELF);
        } else {
            r.drawCube(new Vector3f(backX, caseMidY, backZ),
                new Vector3f(0.05f, caseHeight, caseWidth), Renderer.TEX_SHELF);
        }

        // Side panels
        float panelT = 0.06f;
        if (wallDir == 0) {
            r.drawCube(new Vector3f(caseX - caseWidth / 2f, caseMidY, caseZ),
                new Vector3f(panelT, caseHeight, caseDepth), Renderer.TEX_DOOR);
            r.drawCube(new Vector3f(caseX + caseWidth / 2f, caseMidY, caseZ),
                new Vector3f(panelT, caseHeight, caseDepth), Renderer.TEX_DOOR);
        } else {
            r.drawCube(new Vector3f(caseX, caseMidY, caseZ - caseWidth / 2f),
                new Vector3f(caseDepth, caseHeight, panelT), Renderer.TEX_DOOR);
            r.drawCube(new Vector3f(caseX, caseMidY, caseZ + caseWidth / 2f),
                new Vector3f(caseDepth, caseHeight, panelT), Renderer.TEX_DOOR);
        }

        // Top + bottom panels
        if (wallDir == 0) {
            r.drawCube(new Vector3f(caseX, caseBottom, caseZ),
                new Vector3f(caseWidth, panelT, caseDepth), Renderer.TEX_DOOR);
            r.drawCube(new Vector3f(caseX, caseTop, caseZ),
                new Vector3f(caseWidth, panelT, caseDepth), Renderer.TEX_DOOR);
        } else {
            r.drawCube(new Vector3f(caseX, caseBottom, caseZ),
                new Vector3f(caseDepth, panelT, caseWidth), Renderer.TEX_DOOR);
            r.drawCube(new Vector3f(caseX, caseTop, caseZ),
                new Vector3f(caseDepth, panelT, caseWidth), Renderer.TEX_DOOR);
        }

        // Group books by language
        List<Book> allBooks = room.getBooks();
        Map<String, List<Book>> byLang = new LinkedHashMap<>();
        for (Book bk : allBooks) {
            String lang = bk.getLanguage() != null ? bk.getLanguage() : "Other";
            byLang.computeIfAbsent(lang, k -> new ArrayList<>()).add(bk);
        }

        // Flatten groups into shelf layout
        List<List<Book>> groups = new ArrayList<>(byLang.values());
        int totalBooks = 0;
        for (List<Book> g : groups) totalBooks += Math.min(g.size(), 15);
        if (totalBooks == 0) return;

        int rows = 3;
        float shelfSpacing = (caseHeight - panelT * 2) / rows;
        float shelfY0 = caseBottom + panelT + shelfSpacing / 2f;
        float bookH = shelfSpacing * 0.75f;
        float bookD = caseDepth * 0.6f;

        // Distribute groups across shelves
        int groupIdx = 0;
        int bookInGroup = 0;
        List<Book> currentGroup = groups.isEmpty() ? new ArrayList<>() : groups.get(0);

        for (int row = 0; row < rows; row++) {
            float sy = shelfY0 + row * shelfSpacing;

            // Shelf board
            if (wallDir == 0) {
                r.drawCube(new Vector3f(caseX, sy - bookH / 2f - 0.02f, caseZ),
                    new Vector3f(caseWidth - panelT, 0.03f, caseDepth - 0.05f), Renderer.TEX_SHELF);
            } else {
                r.drawCube(new Vector3f(caseX, sy - bookH / 2f - 0.02f, caseZ),
                    new Vector3f(caseDepth - 0.05f, 0.03f, caseWidth - panelT), Renderer.TEX_SHELF);
            }

            // Books on this shelf
            float usableWidth = caseWidth - panelT * 2 - 0.2f;
            float bookW = 0.10f;
            float bookGap = 0.02f;
            int maxBooks = (int) (usableWidth / (bookW + bookGap));
            int booksPlaced = 0;

            while (booksPlaced < maxBooks && groupIdx < groups.size()) {
                if (bookInGroup >= currentGroup.size() || bookInGroup >= 15) {
                    groupIdx++;
                    bookInGroup = 0;
                    if (groupIdx >= groups.size()) break;
                    currentGroup = groups.get(groupIdx);
                    // Divider between groups
                    float divX, divZ;
                    float divOffset = -usableWidth / 2f + booksPlaced * (bookW + bookGap);
                    if (wallDir == 0) {
                        divX = caseX + divOffset;
                        divZ = caseZ;
                        r.drawCube(new Vector3f(divX, sy, divZ),
                            new Vector3f(0.02f, bookH, bookD), Renderer.TEX_DOOR);
                    } else {
                        divX = caseX;
                        divZ = caseZ + divOffset;
                        r.drawCube(new Vector3f(divX, sy, divZ),
                            new Vector3f(bookD, bookH, 0.02f), Renderer.TEX_DOOR);
                    }
                    booksPlaced++;
                    continue;
                }

                Book book = currentGroup.get(bookInGroup);
                float offset = -usableWidth / 2f + booksPlaced * (bookW + bookGap) + bookW / 2f;
                float bx, bz;

                if (wallDir == 0) {
                    bx = caseX + offset;
                    bz = caseZ;
                    r.drawCube(new Vector3f(bx, sy, bz),
                        new Vector3f(bookW, bookH, bookD), book.getTextureId());
                } else {
                    bx = caseX;
                    bz = caseZ + offset;
                    r.drawCube(new Vector3f(bx, sy, bz),
                        new Vector3f(bookD, bookH, bookW), book.getTextureId());
                }

                bookInGroup++;
                booksPlaced++;
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
