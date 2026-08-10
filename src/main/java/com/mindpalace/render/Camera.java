package com.mindpalace.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * First-person camera with perspective projection.
 */
public class Camera {
    private Vector3f position;
    private float yaw;   // horizontal rotation (degrees)
    private float pitch; // vertical rotation (degrees)
    private float fov = 70.0f;
    private float near = 0.1f;
    private float far = 200.0f;

    private Vector3f front;
    private Vector3f up;
    private Vector3f right;
    private Vector3f worldUp;

    private Matrix4f viewMatrix;
    private Matrix4f projectionMatrix;
    private boolean dirty = true;

    public Camera() {
        position = new Vector3f(0, 1.6f, 0); // eye height
        yaw = -90.0f;
        pitch = 0.0f;
        worldUp = new Vector3f(0, 1, 0);
        front = new Vector3f(0, 0, -1);
        up = new Vector3f();
        right = new Vector3f();
        viewMatrix = new Matrix4f();
        projectionMatrix = new Matrix4f();
        updateVectors();
    }

    private void updateVectors() {
        float yawRad = (float) Math.toRadians(yaw);
        float pitchRad = (float) Math.toRadians(pitch);

        front.x = (float) (Math.cos(yawRad) * Math.cos(pitchRad));
        front.y = (float) Math.sin(pitchRad);
        front.z = (float) (Math.sin(yawRad) * Math.cos(pitchRad));
        front.normalize();

        front.cross(worldUp, right);
        right.normalize();

        right.cross(front, up);
        up.normalize();

        dirty = true;
    }

    public Matrix4f getViewMatrix() {
        if (dirty) {
            Vector3f center = new Vector3f(position).add(front);
            viewMatrix.identity().lookAt(position, center, up);
            dirty = false;
        }
        return viewMatrix;
    }

    public Matrix4f getProjectionMatrix(float aspect) {
        projectionMatrix.identity().perspective(
            (float) Math.toRadians(fov), aspect, near, far);
        return projectionMatrix;
    }

    public void rotate(float dx, float dy) {
        float sensitivity = 0.1f;
        yaw += dx * sensitivity;
        pitch -= dy * sensitivity;

        // Clamp pitch
        if (pitch > 89.0f) pitch = 89.0f;
        if (pitch < -89.0f) pitch = -89.0f;

        updateVectors();
    }

    public void setPosition(Vector3f pos) {
        this.position.set(pos);
        dirty = true;
    }

    public void setPosition(float x, float y, float z) {
        this.position.set(x, y, z);
        dirty = true;
    }

    public Vector3f getPosition() { return position; }
    public Vector3f getFront() { return front; }
    public Vector3f getRight() { return right; }
    public Vector3f getUp() { return up; }
    public float getYaw() { return yaw; }
    public void setYaw(float yaw) { this.yaw = yaw; updateVectors(); }
    public float getPitch() { return pitch; }
    public float getFov() { return fov; }
    public void setFov(float fov) { this.fov = fov; }
}
