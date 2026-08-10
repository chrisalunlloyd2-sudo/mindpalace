package com.mindpalace.world;

import org.joml.Vector3f;

/**
 * A hallway segment connecting rooms.
 */
public class Hallway {
    private int floor;
    private Vector3f start;
    private Vector3f end;
    private float width;
    private float height;

    public Hallway(int floor) {
        this.floor = floor;
    }

    public int getFloor() { return floor; }
    public Vector3f getStart() { return start; }
    public void setStart(Vector3f s) { this.start = s; }
    public Vector3f getEnd() { return end; }
    public void setEnd(Vector3f e) { this.end = e; }
    public float getWidth() { return width; }
    public void setWidth(float w) { this.width = w; }
    public float getHeight() { return height; }
    public void setHeight(float h) { this.height = h; }
}
