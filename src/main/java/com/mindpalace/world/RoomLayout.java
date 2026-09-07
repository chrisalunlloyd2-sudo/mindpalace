package com.mindpalace.world;

import java.util.List;
import org.joml.Vector3f;

/**
 * RoomLayout — a strategy for turning the room list into spatial coordinates.
 *
 * Contract (LAYOUT_ALGORITHMS.md): same input room list ⇒ identical
 * setRoomCenter outputs, every boot. Any layout that drifts breaks every
 * E2E waypoint; the selftest stability check is what enforces this.
 *
 * Each layout owns: hallway creation, floor assignment, door placement.
 * Rooms arrive pre-sorted (largest first) and deduplicated.
 */
public interface RoomLayout {

    /** Display name for the Options panel. */
    String name();

    /**
     * Assign floors, hallways, door positions and room centers.
     * Must set on every room: floor, hallwaySide, doorPosition, roomCenter,
     * doorRotation. Must add created hallways to the hallways list.
     */
    void layout(List<Room> rooms, List<Hallway> hallways);

    /**
     * CORRIDOR — the original stacked-corridor layout (bit-identical port
     * of WorldBuilder.layoutWorld's original body): ~17 rooms per floor,
     * one hallway per floor, rooms alternating sides along +Z, doors on
     * the side walls. This is the default; the world's shape depends on it.
     */
    RoomLayout CORRIDOR = new CorridorLayout();

    /** Radial campus — queued (step 114): rings around a central hall. */
}