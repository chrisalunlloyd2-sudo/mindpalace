package com.mindpalace.entity;

import com.mindpalace.engine.Input;
import com.mindpalace.render.Camera;
import com.mindpalace.world.WorldBuilder;
import com.mindpalace.world.Room;
import com.mindpalace.world.Hallway;
import com.mindpalace.audio.AudioEngine;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

/**
 * Player — FPS controller with acceleration, wall collision, door interaction.
 * No head bob. No ghost glide.
 */
public class Player {
    private Camera camera;
    private Vector3f velocity = new Vector3f();
    private boolean onGround = true;

    private static final float MOVE_SPEED = 6.0f;
    private static final float SPRINT_MULT = 1.6f;
    private static final float ACCEL = 40.0f;     // m/s² — snappy
    private static final float FRICTION = 12.0f;   // ground friction
    private static final float AIR_ACCEL = 8.0f;
    private static final float GRAVITY = -20.0f;
    private static final float JUMP_FORCE = 6.5f;
    private static final float EYE_HEIGHT = 1.6f;
    private static final float RADIUS = 0.3f;

    private Room currentRoom;
    private double interactCooldown;
    private AudioEngine audio;
    private boolean chatTyping;  // suppress door interaction while typing in chat
    private double teleportCooldown; // prevent pad re-trigger bounce
    private int padFloor = -1;      // floor of the pad the player is standing on (-1 = none)
    private boolean noclip = false;  // free-fly (no collision, no gravity) for testing

    public Player() {
        camera = new Camera();
        camera.setPosition(0, EYE_HEIGHT, 3);
        camera.setYaw(0); // looking +Z down hallway
    }

    public void setAudio(AudioEngine a) { this.audio = a; }
    public void setChatTyping(boolean t) { this.chatTyping = t; }
    public void setNoclip(boolean n) { this.noclip = n; }
    public boolean isNoclip() { return noclip; }

    public void update(double dt, Input input, WorldBuilder world) {
        float dtf = (float) dt;
        interactCooldown -= dt;

        // Mouse — pass raw deltas directly to camera
        float dx = (float) input.getMouseDX();
        float dy = (float) input.getMouseDY();
        if (dx != 0 || dy != 0) camera.rotate(dx, dy);

        // Desired movement direction
        Vector3f wishDir = new Vector3f();
        if (input.isKeyDown(GLFW.GLFW_KEY_W)) wishDir.add(camera.getFront());
        if (input.isKeyDown(GLFW.GLFW_KEY_S)) wishDir.sub(camera.getFront());
        if (input.isKeyDown(GLFW.GLFW_KEY_A)) wishDir.sub(camera.getRight());
        if (input.isKeyDown(GLFW.GLFW_KEY_D)) wishDir.add(camera.getRight());

        boolean moving = wishDir.lengthSquared() > 0.01f;
        if (moving && onGround && audio != null) audio.playFootstep();
        if (moving) {
            wishDir.normalize();
            float targetSpeed = MOVE_SPEED * (input.isKeyDown(GLFW.GLFW_KEY_LEFT_SHIFT) ? SPRINT_MULT : 1.0f);
            wishDir.mul(targetSpeed);
        }

        // Acceleration / friction
        float accel = onGround ? ACCEL : AIR_ACCEL;
        Vector3f wishVel = new Vector3f(wishDir);
        wishVel.y = velocity.y;

        // Smoothly blend current velocity toward wish velocity
        float blend = 1.0f - (float) Math.exp(-accel * dtf / MOVE_SPEED);
        velocity.x += (wishVel.x - velocity.x) * blend;
        velocity.z += (wishVel.z - velocity.z) * blend;

        // Friction when not pressing keys
        if (!moving && onGround) {
            float friction = 1.0f - (float) Math.exp(-FRICTION * dtf);
            velocity.x *= (1.0f - friction);
            velocity.z *= (1.0f - friction);
            if (Math.abs(velocity.x) < 0.01f) velocity.x = 0;
            if (Math.abs(velocity.z) < 0.01f) velocity.z = 0;
        }

        // Gravity (flat world only — planet mode applies radial gravity in
        // planetPhysics, since "down" points toward the planet center)
        if (!onGround && !noclip && !world.isPlanetActive()) velocity.y += GRAVITY * dtf;

        // Jump
        if (input.wasKeyPressed(GLFW.GLFW_KEY_SPACE) && onGround && !noclip) {
            velocity.y = JUMP_FORCE;
            onGround = false;
        }

        // Noclip: free-fly — Space/Shift fly up/down, no collision or gravity
        if (noclip) {
            if (input.isKeyDown(GLFW.GLFW_KEY_SPACE)) velocity.y = MOVE_SPEED;
            else if (input.isKeyDown(GLFW.GLFW_KEY_LEFT_SHIFT)) velocity.y = -MOVE_SPEED;
            else velocity.y = 0;
        }

        // Apply movement with collision
        Vector3f pos = camera.getPosition();
        Vector3f newPos = new Vector3f(pos);
        newPos.x += velocity.x * dtf;
        newPos.y += velocity.y * dtf;
        newPos.z += velocity.z * dtf;

        if (!noclip) {
            if (world.isPlanetActive()) {
                newPos = planetPhysics(pos, newPos, world, dtf);
            } else {
                newPos = collide(pos, newPos, world);

                // Ground — use the world's ground height (walkable stairs between floors)
                float groundY = world.getGroundHeight(newPos.x, newPos.z) + EYE_HEIGHT;
                if (newPos.y <= groundY) {
                    newPos.y = groundY;
                    velocity.y = 0;
                    onGround = true;
                }
            }
        }

        camera.setPosition(newPos);

        // Teleport pad — detect standing on a pad (destination chosen in GameEngine)
        teleportCooldown -= dt;
        padFloor = -1;
        if (currentRoom == null && teleportCooldown <= 0 && world.getHallways().size() > 1) {
            Vector3f p = camera.getPosition();
            for (Hallway hw : world.getHallways()) {
                if (hw.getFloor() >= world.getHallways().size() - 1) continue; // no pad on top floor
                float padZ = hw.getEnd().z - 1.0f;
                if (Math.abs(p.x) < 1.05f && Math.abs(p.z - padZ) < 1.05f
                        && Math.abs(p.y - (hw.getStart().y + EYE_HEIGHT)) < 0.75f) {
                    padFloor = hw.getFloor();
                    break;
                }
            }
        }

        // Door interaction — Enter key (suppressed while typing in chat)
        if (input.wasKeyPressed(GLFW.GLFW_KEY_ENTER) && interactCooldown <= 0 && !chatTyping) {
            if (currentRoom == null) {
                Room target = findDoor(world);
                if (target != null) { enterRoom(target); interactCooldown = 0.5; }
            } else {
                exitRoom(); interactCooldown = 0.5;
            }
        }
    }

    private Vector3f collide(Vector3f old, Vector3f next, WorldBuilder world) {
        float r = RADIUS;
        float hw = WorldBuilder.HALLWAY_WIDTH / 2f - 0.1f;

        if (currentRoom == null) {
            if (next.x < -hw + r) next.x = -hw + r;
            if (next.x > hw - r) next.x = hw - r;
            // Walkable in front of the palace (outside area) down to -55.
            if (next.z < -55f) next.z = -55f;
            // Clamp forward to the end of the hallway at the player's current
            // floor level. (Previously clamped to the TOP floor's end, so the
            // player could walk off the end of a lower floor into the void.)
            float maxZ = -55f;
            for (Hallway hall : world.getHallways()) {
                if (Math.abs(old.y - (hall.getStart().y + EYE_HEIGHT)) < 1.5f) {
                    maxZ = hall.getEnd().z;
                    break;
                }
            }
            if (next.z > maxZ - r) next.z = maxZ - r;
        } else {
            Vector3f c = currentRoom.getRoomCenter();
            float rw = Room.ROOM_WIDTH / 2f - r;
            float rd = Room.ROOM_DEPTH / 2f - r;
            if (next.x < c.x - rw) next.x = c.x - rw;
            if (next.x > c.x + rw) next.x = c.x + rw;
            if (next.z < c.z - rd) next.z = c.z - rd;
            if (next.z > c.z + rd) next.z = c.z + rd;
        }
        return next;
    }

    /**
     * Planet physics — radial gravity. "Down" points toward the planet center,
     * so the player sticks to the curved surface and can walk all the way
     * around. The camera's up vector tracks the local surface normal.
     */
    private Vector3f planetPhysics(Vector3f old, Vector3f next, WorldBuilder world, float dtf) {
        Vector3f c = world.getPlanetCenter();
        float R = world.getPlanetRadius();

        // Radial vector from planet center to the player
        Vector3f radial = new Vector3f(next).sub(c);
        float dist = radial.length();
        if (dist < 0.001f) dist = 0.001f;
        Vector3f normal = radial.div(dist, new Vector3f());  // outward unit normal

        // Clamp to the surface (player eye sits EYE_HEIGHT above the ground)
        float targetDist = R + EYE_HEIGHT;
        if (dist < targetDist) {
            // Below surface — push out and kill inward velocity
            next.set(c).add(normal.mul(targetDist, new Vector3f()));
            // Remove the inward component of velocity
            float vn = velocity.dot(normal);
            if (vn < 0) velocity.sub(normal.mul(vn, new Vector3f()));
            onGround = true;
        } else {
            // Above surface — apply radial gravity toward the center
            Vector3f grav = normal.mul(GRAVITY * dtf, new Vector3f());
            velocity.add(grav);
            onGround = false;
        }

        // Track the local up vector so the camera stays upright on the sphere
        camera.setUpOverride(normal);

        return next;
    }

    private Room findDoor(WorldBuilder world) {
        // Nearest door within reach — no aim requirement. Doors sit on the side
        // walls while the player walks facing +Z, so requiring a precise look
        // angle made most doors unopenable ("some rooms work, most don't").
        Vector3f origin = camera.getPosition();
        Room best = null;
        float bestDist = 3.0f;
        for (Room room : world.getRooms()) {
            Vector3f dp = room.getDoorPosition();
            if (dp == null) continue;
            float d = origin.distance(dp);
            if (d < bestDist) { bestDist = d; best = room; }
        }
        return best;
    }

    private void enterRoom(Room room) {
        currentRoom = room;
        room.openDoor();
        if (audio != null) audio.playDoorOpen();
        Vector3f c = room.getRoomCenter();
        float ez = room.getHallwaySide() == 0 ? c.z + Room.ROOM_DEPTH / 2f - 1.2f
                                              : c.z - Room.ROOM_DEPTH / 2f + 1.2f;
        camera.setPosition(c.x, EYE_HEIGHT, ez);
        camera.setYaw(room.getHallwaySide() == 0 ? 180 : 0);
        System.out.println("[ENTER] " + room.getDisplayLabel());
    }

    private void exitRoom() {
        if (currentRoom == null) return;
        Room room = currentRoom;
        // Doors open on Enter and STAY open — no auto-close cycle (Architect's
        // spec: "the doors don't need to cycle open and closed, just open when
        // I press enter"). So we deliberately do NOT call room.closeDoor() here.
        currentRoom = null;
        Vector3f dp = room.getDoorPosition();
        float ex = room.getHallwaySide() == 0 ? dp.x + 1.2f : dp.x - 1.2f;
        camera.setPosition(ex, EYE_HEIGHT, dp.z);
        camera.setYaw(room.getHallwaySide() == 0 ? 0 : 180);
        System.out.println("[EXIT] " + room.getDisplayLabel());
    }

    public Camera getCamera() { return camera; }
    public Vector3f getPosition() { return camera.getPosition(); }
    public Vector3f getLookDirection() { return camera.getFront(); }
    public Room getCurrentRoom() { return currentRoom; }

    /** Teleport into a room (self-test / teleporter destination). No door anim. */
    public void teleportIntoRoom(Room room) {
        currentRoom = room;
        Vector3f c = room.getRoomCenter();
        float ez = room.getHallwaySide() == 0 ? c.z + Room.ROOM_DEPTH / 2f - 1.2f
                                              : c.z - Room.ROOM_DEPTH / 2f + 1.2f;
        camera.setPosition(c.x, EYE_HEIGHT, ez);
        camera.setYaw(room.getHallwaySide() == 0 ? 180 : 0);
        camera.setPitch(0);
    }

    /** Floor of the teleporter pad the player is standing on, or -1. */
    public int getPadFloor() { return padFloor; }

    /** Teleport to a hallway floor (teleporter destination). */
    public void teleportToFloor(int floor, WorldBuilder world) {
        if (floor < 0 || floor >= world.getHallways().size()) return;
        Hallway hw = world.getHallways().get(floor);
        currentRoom = null;
        camera.setPosition(0f, hw.getStart().y + EYE_HEIGHT, hw.getStart().z + 1.5f);
        camera.setYaw(0);
        camera.setPitch(0);
        velocity.set(0, 0, 0);
        onGround = true;
        teleportCooldown = 1.0;
        if (audio != null) audio.playDoorOpen();
        System.out.println("[TELEPORT] -> Floor " + (floor + 1));
    }

    /** Teleport to the outside area (floor 0, in front of the palace). */
    public void teleportOutside(WorldBuilder world) {
        if (world.getHallways().isEmpty()) return;
        Hallway hw = world.getHallways().get(0);
        currentRoom = null;
        // Outside sits at frontZ - 48 on floor 0 (see WorldBuilder.renderOutside)
        float outZ = hw.getStart().z - 2.0f - 48.0f + 12.0f;
        camera.setPosition(0f, hw.getStart().y + EYE_HEIGHT, outZ);
        camera.setYaw(0);
        camera.setPitch(0);
        velocity.set(0, 0, 0);
        onGround = true;
        teleportCooldown = 1.0;
        if (audio != null) audio.playDoorOpen();
        System.out.println("[TELEPORT] -> Outside");
    }

    /** Teleport onto the planet surface (radial gravity open world). */
    public void teleportToPlanet(WorldBuilder world) {
        currentRoom = null;
        world.setPlanetActive(true);
        Vector3f c = world.getPlanetCenter();
        float R = world.getPlanetRadius();
        // Land on the +Y pole (top of the planet), facing outward
        Vector3f pos = new Vector3f(c.x, c.y + R + EYE_HEIGHT, c.z);
        camera.setPosition(pos);
        camera.setUpOverride(new Vector3f(0, 1, 0));
        camera.setYaw(0);
        camera.setPitch(0);
        velocity.set(0, 0, 0);
        onGround = true;
        teleportCooldown = 1.0;
        if (audio != null) audio.playDoorOpen();
        System.out.println("[TELEPORT] -> Planet");
    }

    /** Return to the flat palace (clears planet gravity). */
    public void teleportToPalace(WorldBuilder world) {
        world.setPlanetActive(false);
        camera.setUpOverride(null);
        teleportToFloor(0, world);
    }
}
