# Adding a New Layout Algorithm

How the 143 repos become rooms along 9 hallways — and how to add your own
spatial arrangement (radial campus, spiral tower, force-directed districts...).

## How layout works today

`WorldBuilder.layoutWorld()` is the single place where repos become
coordinates. Current algorithm: **stacked corridor** —

1. `floors = max(4, ceil(total / 17))` — ~17 rooms per floor.
2. Each floor gets one `Hallway` (start/end/width/height).
3. Rooms alternate sides (`side` 0/1) at fixed `DOOR_SPACING` along the
   hallway's +Z axis; `room.setDoorPosition(...)` + `setRoomCenter(...)`
   place each room so its door faces the hallway wall.
4. Dedupe happens before layout (`dedupeRepos`); sorting by size happens
   before that, so the world is deterministic per repo set.

Determinism contract: **same repo list → same world, every boot** (E2E
waypoints depend on it). Any new algorithm must keep this.

## Adding your own (the clean path)

1. **Add an enum**: `public enum LayoutStyle { CORRIDOR, RADIAL, ... }`
   in `WorldBuilder` (or a new `world/layout/` package once there are 3+).
2. **Extract the current body** of `layoutWorld()` into
   `layoutCorridor(...)` unchanged — the default stays bit-identical.
3. **Write your algorithm** as `layoutRadial(...)` etc. Rules:
   - every room gets `setHallwaySide`, `setDoorPosition`, `setRoomCenter`
     (the door must face its hallway/entrance or `findDoor` can't reach it);
   - respect building margins (`HALLWAY_WIDTH`, `Room.WALL_THICKNESS`);
   - no randomness without `DeterministicSeed` (fixed recipe string);
   - collision: rooms are boxes — keep them non-overlapping so the player's
     `resolveAxis` doesn't wedge.
4. **Register it** in `layoutWorld()`'s dispatch + an Options-panel entry
   (`GameEngine` settings menu, copy the bloom-knob pattern).
5. **Prove stability**: a selftest check asserting
   `same repo list ⇒ same centers` across two `WorldBuilder` builds
   (copy the DePIN selftest block's shape).
6. **Ship through the cascade** (CONTRIBUTING.md): build → selftest →
   E2E → commit → push → step-log.

## Interface sketch (when there are 3+)

    public interface RoomLayout {
        String name();                       // for the options panel
        void layout(List<Room> rooms, List<Hallway> halls);
        // Stability: same input list ⇒ identical setRoomCenter outputs.
    }

`RADIAL` sketch: rooms on concentric rings around the main hall, doors
facing inward, hallways become radial spokes; keep the teleporter pads at
fixed angles so floor transitions stay predictable.

## Stability testing (the point of the module)

The layout module exists so experiments are safe: two `WorldBuilder`
instances built from the same repo JSON must produce identical room
centers (assert per-room, epsilon 0). A layout that drifts breaks every
E2E waypoint — that's the test that catches it.

## Status

Layout-isolation is item 5 of the Architect's community list; the corridor
extraction + enum is queued as a NEXT_100_STEPS entry (step ~113). This
doc fixes the contract first so the extraction lands as pure refactor.
