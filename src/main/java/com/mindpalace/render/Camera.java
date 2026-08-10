package com.mindpalace.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * First-person camera. Yaw=0 looks +Z, yaw=90 looks +X, yaw=-90 looks -X.
 */
public class Camera {
    private Vector3f position;
    private float yaw, pitch;
    private float fov = 70.0f;
    private float near = 0.1f, far = 200.0f;

    private Vector3f front, up, right;
    private final Vector3f worldUp = new Vector3f(0, 1, 0);
    private Matrix4f viewMatrix = new Matrix4f();
    private Matrix4f projectionMatrix = new Matrix4f();
    private boolean dirty = true;

    public Camera() {
        position = new Vector3f(0, 1.6f, 0);
        yaw = 0;   // 0 = looking +Z
        pitch = 0;
        front = new Vector3f(0, 0, 1);
        up = new Vector3f();
        right = new Vector3f();
        updateVectors();
    }

    private void updateVectors() {
        float yr = (float) Math.toRadians(yaw);
        float pr = (float) Math.toRadians(pitch);
        front.x = (float) (Math.sin(yr) * Math.cos(pr));
        front.y = (float) Math.sin(pr);
        front.z = (float) (Math.cos(yr) * Math.cos(pr));
        front.normalize();
        front.cross(worldUp, right);
        right.normalize();
        right.cross(front, up);
        up.normalize();
        dirty = true;
    }

    public Matrix4f getViewMatrix() {
        if (dirty) {
            viewMatrix.identity().lookAt(position,
                new Vector3f(position).add(front), up);
            dirty = false;
        }
        return viewMatrix;
    }

    public Matrix4f getProjectionMatrix(float aspect) {
        return projectionMatrix.identity()
            .perspective((float) Math.toRadians(fov), aspect, near, far);
    }

    /** Raw mouse input — dx/dy are pixel deltas. Sensitivity applied here. */
    public void rotate(float dx, float dy) {
        float sens = 0.08f; // single source of truth
        yaw += dx * sens;
        pitch -= dy * sens;
        if (pitch > 89) pitch = 89;
        if (pitch < -89) pitch = -89;
        updateVectors();
    }

    public void setPosition(Vector3f pos) { position.set(pos); dirty = true; }
    public void setPosition(float x, float y, float z) { position.set(x, y, z); dirty = true; }
    public Vector3f getPosition() { return position; }
    public Vector3f getFront() { return front; }
    public Vector3f getRight() { return right; }
    public Vector3f getUp() { return up; }
    public float getYaw() { return yaw; }
    public void setYaw(float y) { yaw = y; updateVectors(); }
    public float getPitch() { return pitch; }
    public float getFov() { return fov; }
    public void setFov(float f) { fov = f; }
}
