package com.mindpalace.entity;

import com.mindpalace.engine.Input;
import com.mindpalace.render.Camera;
import com.mindpalace.world.WorldBuilder;
import com.mindpalace.world.Room;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

/**
 * Player entity — FPS controller with wall collision and door interaction.
 */
public class Player {
    private Camera camera;
    private Vector3f velocity;
    private boolean onGround = true;

    private float moveSpeed = 5.0f;
    private float sprintMultiplier = 1.8f;
    private float gravity = -15.0f;
    private float jumpForce = 5.0f;
    private float playerHeight = 1.6f;
    private float playerRadius = 0.3f;

    private float bobTimer;
    private boolean isMoving;

    // Door interaction
    private Room currentRoom; // null = in hallway
    private boolean justEnteredRoom;
    private double interactCooldown;

    public Player() {
        camera = new Camera();
        camera.setPosition(0, playerHeight, 3);
        camera.setYaw(90);
        velocity = new Vector3f(0, 0, 0);
    }

    public void update(double dt, Input input, WorldBuilder world) {
        float dtf = (float) dt;
        interactCooldown -= dt;

        // Mouse look
        float dx = (float) input.getMouseDX();
        float dy = (float) input.getMouseDY();
        if (dx != 0 || dy != 0) {
            camera.rotate(dx * 0.15f, dy * 0.15f);
        }

        // Movement
        Vector3f moveDir = new Vector3f(0, 0, 0);
        boolean sprint = input.isKeyDown(GLFW.GLFW_KEY_LEFT_SHIFT);

        if (input.isKeyDown(GLFW.GLFW_KEY_W)) moveDir.add(camera.getFront());
        if (input.isKeyDown(GLFW.GLFW_KEY_S)) moveDir.sub(camera.getFront());
        if (input.isKeyDown(GLFW.GLFW_KEY_A)) moveDir.sub(camera.getRight());
        if (input.isKeyDown(GLFW.GLFW_KEY_D)) moveDir.add(camera.getRight());

        isMoving = moveDir.lengthSquared() > 0.01f;

        if (isMoving) {
            moveDir.normalize();
            float speed = moveSpeed * (sprint ? sprintMultiplier : 1.0f);
            moveDir.mul(speed * dtf);
        }

        // Gravity
        if (!onGround) velocity.y += gravity * dtf;

        // Jump
        if (input.isKeyJustPressed(GLFW.GLFW_KEY_SPACE) && onGround) {
            velocity.y = jumpForce;
            onGround = false;
        }

        // Apply movement with collision
        Vector3f pos = camera.getPosition();
        Vector3f newPos = new Vector3f(pos);
        newPos.add(moveDir);
        newPos.y += velocity.y * dtf;

        // Wall collision
        newPos = collideWithWorld(pos, newPos, world);

        // Ground
        if (newPos.y <= playerHeight) {
            newPos.y = playerHeight;
            velocity.y = 0;
            onGround = true;
        }

        // Head bob
        if (isMoving && onGround) {
            bobTimer += dtf * (sprint ? 12 : 8);
            newPos.y += Math.sin(bobTimer) * 0.03f;
        }

        camera.setPosition(newPos);

        // Door interaction
        if (input.isKeyJustPressed(GLFW.GLFW_KEY_E) && interactCooldown <= 0) {
            if (currentRoom == null) {
                // Try to enter a room
                Room target = findDoorInFront(world);
                if (target != null) {
                    enterRoom(target);
                    interactCooldown = 0.5;
                }
            } else {
                // Exit room back to hallway
                exitRoom();
                interactCooldown = 0.5;
            }
        }
    }

    private Vector3f collideWithWorld(Vector3f oldPos, Vector3f newPos, WorldBuilder world) {
        float r = playerRadius;
        float hw = WorldBuilder.HALLWAY_WIDTH / 2 - 0.1f;

        // Hallway walls
        if (currentRoom == null) {
            // Left wall
            if (newPos.x < -hw + r) newPos.x = -hw + r;
            // Right wall
            if (newPos.x > hw - r) newPos.x = hw - r;

            // Start wall
            if (newPos.z < 0.1f) newPos.z = 0.1f;
            // End wall (first hallway)
            if (world.getHallways().size() > 0) {
                float endZ = world.getHallways().get(0).getEnd().z;
                if (newPos.z > endZ - r) newPos.z = endZ - r;
            }
        } else {
            // Room walls
            Vector3f c = currentRoom.getRoomCenter();
            float rw = Room.ROOM_WIDTH / 2 - r;
            float rd = Room.ROOM_DEPTH / 2 - r;

            if (newPos.x < c.x - rw) newPos.x = c.x - rw;
            if (newPos.x > c.x + rw) newPos.x = c.x + rw;
            if (newPos.z < c.z - rd) newPos.z = c.z - rd;
            if (newPos.z > c.z + rd) newPos.z = c.z + rd;
        }

        return newPos;
    }

    private Room findDoorInFront(WorldBuilder world) {
        Vector3f lookDir = camera.getFront();
        Vector3f origin = camera.getPosition();

        for (Room room : world.getRooms()) {
            Vector3f doorPos = room.getDoorPosition();
            if (doorPos == null) continue;

            float dist = origin.distance(doorPos);
            if (dist < 2.5f) {
                Vector3f toDoor = new Vector3f(doorPos).sub(origin).normalize();
                if (lookDir.dot(toDoor) > 0.7f) {
                    return room;
                }
            }
        }
        return null;
    }

    private void enterRoom(Room room) {
        currentRoom = room;
        justEnteredRoom = true;
        Vector3f c = room.getRoomCenter();
        // Place player inside room, facing the back wall
        float entryZ = room.getHallwaySide() == 0 ? c.z + Room.ROOM_DEPTH / 2 - 1.0f
                                                   : c.z - Room.ROOM_DEPTH / 2 + 1.0f;
        camera.setPosition(c.x, playerHeight, entryZ);
        // Face into the room
        camera.setYaw(room.getHallwaySide() == 0 ? -90 : 90);
        System.out.println("[ENTER] " + room.getDisplayLabel());
    }

    private void exitRoom() {
        if (currentRoom == null) return;
        Room room = currentRoom;
        currentRoom = null;
        // Place player back in hallway in front of the door
        Vector3f doorPos = room.getDoorPosition();
        float exitX = room.getHallwaySide() == 0 ? doorPos.x + 1.0f : doorPos.x - 1.0f;
        camera.setPosition(exitX, playerHeight, doorPos.z);
        camera.setYaw(room.getHallwaySide() == 0 ? 90 : -90);
        System.out.println("[EXIT] " + room.getDisplayLabel());
    }

    public Camera getCamera() { return camera; }
    public Vector3f getPosition() { return camera.getPosition(); }
    public Vector3f getLookDirection() { return camera.getFront(); }
    public Room getCurrentRoom() { return currentRoom; }
    public boolean justEnteredRoom() { return justEnteredRoom; }
    public void clearJustEnteredRoom() { justEnteredRoom = false; }
}
