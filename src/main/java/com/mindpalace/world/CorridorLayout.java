package com.mindpalace.world;

import java.util.List;
import org.joml.Vector3f;

/**
 * CorridorLayout — the original stacked-corridor arrangement, extracted
 * verbatim from WorldBuilder.layoutWorld (pure refactor, step 113).
 * ~17 rooms per floor, one hallway per floor, rooms alternating sides.
 */
public final class CorridorLayout implements RoomLayout {

    private static final int MAX_PER_FLOOR = 17;

    @Override public String name() { return "Corridor (classic)"; }

    @Override
    public void layout(List<Room> rooms, List<Hallway> hallways) {
        int total = rooms.size();
        int floors = Math.max(4, (total + 16) / 17); // ~17 rooms per floor
        int perFloor = (total + floors - 1) / floors;
        int perSide = (perFloor + 1) / 2;

        float len = perSide * WorldBuilder.DOOR_SPACING + WorldBuilder.HALLWAY_START_OFFSET * 2;
        float floorGap = WorldBuilder.HALLWAY_HEIGHT + 1.0f;
        float zOffset = len + 4.0f;

        for (int f = 0; f < floors; f++) {
            Hallway hw = new Hallway(f);
            hw.setStart(new Vector3f(0, f * floorGap, f * zOffset));
            hw.setEnd(new Vector3f(0, f * floorGap, f * zOffset + len));
            hw.setWidth(WorldBuilder.HALLWAY_WIDTH);
            hw.setHeight(WorldBuilder.HALLWAY_HEIGHT);
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
                    float doorZ = hz + WorldBuilder.HALLWAY_START_OFFSET + i * WorldBuilder.DOOR_SPACING;
                    float doorX = side == 0 ? -WorldBuilder.HALLWAY_WIDTH / 2f : WorldBuilder.HALLWAY_WIDTH / 2f;
                    float cx = side == 0 ? -WorldBuilder.HALLWAY_WIDTH / 2f - Room.ROOM_DEPTH / 2f - Room.WALL_THICKNESS
                                         : WorldBuilder.HALLWAY_WIDTH / 2f + Room.ROOM_DEPTH / 2f + Room.WALL_THICKNESS;
                    room.setDoorPosition(new Vector3f(doorX, hy + 1.0f, doorZ));
                    room.setRoomCenter(new Vector3f(cx, hy + Room.ROOM_HEIGHT / 2f, doorZ));
                    room.setDoorRotation(side == 0 ? 90 : -90);
                    idx++;
                }
            }
        }
    }
}