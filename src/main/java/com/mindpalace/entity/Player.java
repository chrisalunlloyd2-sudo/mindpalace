package com.mindpalace.entity;

import com.mindpalace.engine.Input;
import com.mindpalace.render.Camera;
import com.mindpalace.world.WorldBuilder;
import com.mindpalace.world.Room;
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

    public Player() {
        camera = new Camera();
        camera.setPosition(0, EYE_HEIGHT, 3);
        camera.setYaw(0); // looking +Z down hallway
    }

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

        // Gravity
        if (!onGround) velocity.y += GRAVITY * dtf;

        // Jump
        if (input.isKeyJustPressed(GLFW.GLFW_KEY_SPACE) && onGround) {
            velocity.y = JUMP_FORCE;
            onGround = false;
        }

        // Apply movement with collision
        Vector3f pos = camera.getPosition();
        Vector3f newPos = new Vector3f(pos);
        newPos.x += velocity.x * dtf;
        newPos.y += velocity.y * dtf;
        newPos.z += velocity.z * dtf;

        newPos = collide(pos, newPos, world);

        // Ground
        if (newPos.y <= EYE_HEIGHT) {
            newPos.y = EYE_HEIGHT;
            velocity.y = 0;
            onGround = true;
        }

        camera.setPosition(newPos);

        // Door interaction — Enter key
        if (input.isKeyJustPressed(GLFW.GLFW_KEY_ENTER) && interactCooldown <= 0) {
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
            if (next.z < 0.1f) next.z = 0.1f;
            if (!world.getHallways().isEmpty()) {
                float endZ = world.getHallways().get(0).getEnd().z;
                if (next.z > endZ - r) next.z = endZ - r;
            }
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

    private Room findDoor(WorldBuilder world) {
        Vector3f look = camera.getFront();
        Vector3f origin = camera.getPosition();
        for (Room room : world.getRooms()) {
            Vector3f dp = room.getDoorPosition();
            if (dp == null) continue;
            if (origin.distance(dp) < 2.5f) {
                Vector3f to = new Vector3f(dp).sub(origin).normalize();
                if (look.dot(to) > 0.7f) return room;
            }
        }
        return null;
    }

    private void enterRoom(Room room) {
        currentRoom = room;
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
}
