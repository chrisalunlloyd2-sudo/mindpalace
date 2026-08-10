package com.mindpalace.entity;

import com.mindpalace.engine.Input;
import com.mindpalace.render.Camera;
import com.mindpalace.world.WorldBuilder;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

/**
 * Player entity — first-person controller.
 * WASD movement, mouse look, collision with world.
 */
public class Player {
    private Camera camera;
    private Vector3f velocity;
    private boolean onGround = true;

    // Movement settings
    private float moveSpeed = 5.0f;
    private float sprintMultiplier = 1.8f;
    private float mouseSensitivity = 0.1f;
    private float gravity = -15.0f;
    private float jumpForce = 5.0f;
    private float playerHeight = 1.6f;
    private float playerRadius = 0.3f;

    // Head bob
    private float bobTimer = 0;
    private float bobAmount = 0.03f;
    private boolean isMoving = false;

    public Player() {
        camera = new Camera();
        camera.setPosition(0, playerHeight, 3);
        // Look down the hallway (+Z): yaw=90 means front=(0,0,1)
        camera.setYaw(90);
        velocity = new Vector3f(0, 0, 0);
    }

    public void update(double dt, Input input, WorldBuilder world) {
        float dtf = (float) dt;

        // Mouse look
        float dx = (float) input.getMouseDX();
        float dy = (float) input.getMouseDY();
        if (dx != 0 || dy != 0) {
            camera.rotate(dx, dy);
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
        if (!onGround) {
            velocity.y += gravity * dtf;
        }

        // Jump
        if (input.isKeyJustPressed(GLFW.GLFW_KEY_SPACE) && onGround) {
            velocity.y = jumpForce;
            onGround = false;
        }

        // Apply movement
        Vector3f pos = camera.getPosition();
        pos.add(moveDir);
        pos.y += velocity.y * dtf;

        // Ground collision
        if (pos.y <= playerHeight) {
            pos.y = playerHeight;
            velocity.y = 0;
            onGround = true;
        }

        // Head bob
        if (isMoving && onGround) {
            bobTimer += dtf * (sprint ? 12 : 8);
            float bob = (float) Math.sin(bobTimer) * bobAmount;
            pos.y += bob;
        }

        camera.setPosition(pos);
    }

    public Camera getCamera() { return camera; }
    public Vector3f getPosition() { return camera.getPosition(); }
    public Vector3f getLookDirection() { return camera.getFront(); }
}
