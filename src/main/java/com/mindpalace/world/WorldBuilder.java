package com.mindpalace.world;

import com.mindpalace.render.Renderer;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Procedural world builder — generates hallways, rooms, stairs.
 * 
 * Layout:
 *   Ground floor: long hallway, 25 doors each side (50 rooms)
 *   Stairs at end → Upper floor: second hallway, 25 doors each side (50 rooms)
 *   ViperAI_Notes gets a special room at the end of upper floor
 *   Total: ~100 rooms + special rooms
 */
public class WorldBuilder {
    private List<Room> rooms = new ArrayList<>();
    private List<Hallway> hallways = new ArrayList<>();

    // World dimensions
    public static final float HALLWAY_WIDTH = 3.0f;
    public static final float HALLWAY_HEIGHT = 4.0f;
    public static final float DOOR_SPACING = 5.0f;  // space between doors
    public static final float HALLWAY_START_OFFSET = 3.0f; // space before first door

    private RepoMapper repoMapper;
    private RoomPopulator populator;

    public WorldBuilder() {
        repoMapper = new RepoMapper();
        populator = new RoomPopulator();
    }

    public void build() {
        System.out.println("[WorldBuilder] Building MindPalace world...");

        // Step 1: Map repos to rooms
        repoMapper.scanRepos(rooms);
        System.out.println("[WorldBuilder] Mapped " + rooms.size() + " repos to rooms");

        // Step 2: Layout hallways and position rooms
        layoutWorld();

        // Step 3: Populate rooms with books (files)
        for (Room room : rooms) {
            populator.populateRoom(room);
        }

        System.out.println("[WorldBuilder] World built: " + hallways.size() + " hallways, " + rooms.size() + " rooms");
    }

    private void layoutWorld() {
        int totalRooms = rooms.size();
        int roomsPerFloor = (totalRooms + 1) / 2; // split across 2 floors
        int roomsPerSide = (roomsPerFloor + 1) / 2; // split across 2 sides of hallway

        // Ground floor hallway
        Hallway groundHallway = new Hallway(0);
        float hallwayLength = roomsPerSide * DOOR_SPACING + HALLWAY_START_OFFSET * 2;
        groundHallway.setStart(new Vector3f(0, 0, 0));
        groundHallway.setEnd(new Vector3f(0, 0, hallwayLength));
        groundHallway.setWidth(HALLWAY_WIDTH);
        groundHallway.setHeight(HALLWAY_HEIGHT);
        hallways.add(groundHallway);

        // Upper floor hallway (offset + stairs)
        Hallway upperHallway = new Hallway(1);
        upperHallway.setStart(new Vector3f(0, HALLWAY_HEIGHT + 1.0f, hallwayLength + 4.0f));
        upperHallway.setEnd(new Vector3f(0, HALLWAY_HEIGHT + 1.0f, hallwayLength + 4.0f + hallwayLength));
        upperHallway.setWidth(HALLWAY_WIDTH);
        upperHallway.setHeight(HALLWAY_HEIGHT);
        hallways.add(upperHallway);

        // Position rooms along hallways
        int roomIndex = 0;
        for (int floor = 0; floor < 2 && roomIndex < totalRooms; floor++) {
            Hallway hw = hallways.get(floor);
            float hwZ = hw.getStart().z;
            float hwY = hw.getStart().y;

            for (int side = 0; side < 2 && roomIndex < totalRooms; side++) {
                for (int i = 0; i < roomsPerSide && roomIndex < totalRooms; i++) {
                    Room room = rooms.get(roomIndex);
                    room.setFloor(floor);
                    room.setHallwaySide(side);

                    float doorZ = hwZ + HALLWAY_START_OFFSET + i * DOOR_SPACING;
                    float doorX = (side == 0) ? -HALLWAY_WIDTH / 2 : HALLWAY_WIDTH / 2;
                    float roomCenterX = (side == 0) ? -HALLWAY_WIDTH / 2 - Room.ROOM_DEPTH / 2 - Room.WALL_THICKNESS
                                                   : HALLWAY_WIDTH / 2 + Room.ROOM_DEPTH / 2 + Room.WALL_THICKNESS;

                    room.setDoorPosition(new Vector3f(doorX, hwY + 1.0f, doorZ));
                    room.setRoomCenter(new Vector3f(roomCenterX, hwY + Room.ROOM_HEIGHT / 2, doorZ));
                    room.setDoorRotation(side == 0 ? 90 : -90);

                    roomIndex++;
                }
            }
        }
    }

    public void render(Renderer renderer) {
        // Draw hallways
        for (Hallway hw : hallways) {
            renderHallway(renderer, hw);
        }

        // Draw rooms
        for (Room room : rooms) {
            renderRoom(renderer, room);
        }
    }

    private void renderHallway(Renderer renderer, Hallway hw) {
        Vector3f start = hw.getStart();
        Vector3f end = hw.getEnd();
        float w = hw.getWidth();
        float h = hw.getHeight();
        float length = end.z - start.z;

        // Floor
        renderer.drawCube(
            new Vector3f(0, start.y, start.z + length / 2),
            new Vector3f(w, 0.1f, length),
            null);

        // Ceiling
        renderer.drawCube(
            new Vector3f(0, start.y + h, start.z + length / 2),
            new Vector3f(w, 0.1f, length),
            null);

        // Left wall
        renderer.drawCube(
            new Vector3f(-w / 2, start.y + h / 2, start.z + length / 2),
            new Vector3f(0.2f, h, length),
            null);

        // Right wall
        renderer.drawCube(
            new Vector3f(w / 2, start.y + h / 2, start.z + length / 2),
            new Vector3f(0.2f, h, length),
            null);

        // End wall
        renderer.drawCube(
            new Vector3f(0, start.y + h / 2, end.z),
            new Vector3f(w, h, 0.2f),
            null);

        // Start wall (behind player at spawn)
        renderer.drawCube(
            new Vector3f(0, start.y + h / 2, start.z),
            new Vector3f(w, h, 0.2f),
            null);
    }

    private void renderRoom(Renderer renderer, Room room) {
        Vector3f c = room.getRoomCenter();
        float w = Room.ROOM_WIDTH;
        float d = Room.ROOM_DEPTH;
        float h = Room.ROOM_HEIGHT;
        float t = Room.WALL_THICKNESS;

        // Floor
        renderer.drawCube(
            new Vector3f(c.x, c.y - h / 2, c.z),
            new Vector3f(w, 0.1f, d),
            null);

        // Ceiling
        renderer.drawCube(
            new Vector3f(c.x, c.y + h / 2, c.z),
            new Vector3f(w, 0.1f, d),
            null);

        // Back wall (opposite door)
        float backZ = room.getHallwaySide() == 0 ? c.z + d / 2 : c.z - d / 2;
        renderer.drawCube(
            new Vector3f(c.x, c.y, backZ),
            new Vector3f(w, h, t),
            null);

        // Side walls
        renderer.drawCube(
            new Vector3f(c.x - w / 2, c.y, c.z),
            new Vector3f(t, h, d),
            null);
        renderer.drawCube(
            new Vector3f(c.x + w / 2, c.y, c.z),
            new Vector3f(t, h, d),
            null);

        // Front wall (with door gap)
        float frontZ = room.getHallwaySide() == 0 ? c.z - d / 2 : c.z + d / 2;
        float doorX = room.getDoorPosition().x;
        float doorHalf = Room.DOOR_WIDTH / 2;

        // Left of door
        renderer.drawCube(
            new Vector3f(c.x - w / 4 - doorHalf / 2, c.y, frontZ),
            new Vector3f(w / 2 - doorHalf, h, t),
            null);
        // Right of door
        renderer.drawCube(
            new Vector3f(c.x + w / 4 + doorHalf / 2, c.y, frontZ),
            new Vector3f(w / 2 - doorHalf, h, t),
            null);
        // Above door
        renderer.drawCube(
            new Vector3f(c.x, c.y + h / 2 - (h - Room.DOOR_HEIGHT) / 2, frontZ),
            new Vector3f(Room.DOOR_WIDTH, h - Room.DOOR_HEIGHT, t),
            null);

        // Render books on shelves
        renderShelves(renderer, room);
    }

    private void renderShelves(Renderer renderer, Room room) {
        Vector3f c = room.getRoomCenter();
        float shelfY = c.y - Room.ROOM_HEIGHT / 2 + 0.8f;
        float shelfSpacing = 0.4f;
        int shelvesPerWall = 3;

        // Shelves on back wall
        float backZ = room.getHallwaySide() == 0 ? c.z + Room.ROOM_DEPTH / 2 - 0.3f : c.z - Room.ROOM_DEPTH / 2 + 0.3f;

        for (int s = 0; s < shelvesPerWall; s++) {
            float y = shelfY + s * shelfSpacing;
            // Shelf board
            renderer.drawCube(
                new Vector3f(c.x, y, backZ),
                new Vector3f(Room.ROOM_WIDTH - 0.6f, 0.05f, 0.3f),
                null);

            // Books on shelf
            List<Book> books = room.getBooks();
            int booksPerShelf = Math.min(20, books.size() / shelvesPerWall);
            for (int b = 0; b < booksPerShelf; b++) {
                int bookIdx = s * booksPerShelf + b;
                if (bookIdx >= books.size()) break;
                Book book = books.get(bookIdx);

                float bx = c.x - (Room.ROOM_WIDTH - 0.6f) / 2 + 0.1f + b * 0.15f;
                renderer.drawCube(
                    new Vector3f(bx, y + 0.15f, backZ),
                    new Vector3f(book.getThickness(), 0.3f, 0.2f),
                    null);
            }
        }
    }

    public List<Room> getRooms() { return rooms; }
    public List<Hallway> getHallways() { return hallways; }

    /**
     * Find which room the player is looking at / near.
     */
    public Room findRoomAt(Vector3f position) {
        for (Room room : rooms) {
            Vector3f c = room.getRoomCenter();
            float dx = Math.abs(position.x - c.x);
            float dz = Math.abs(position.z - c.z);
            if (dx < Room.ROOM_WIDTH / 2 && dz < Room.ROOM_DEPTH / 2) {
                return room;
            }
        }
        return null;
    }
}
