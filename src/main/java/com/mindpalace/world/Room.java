package com.mindpalace.world;

import org.joml.Vector3f;
import java.util.ArrayList;
import java.util.List;

/**
 * A room in the MindPalace — represents one GitHub repo.
 * Each room has a door, shelves with books (files), and a back room for comments/gists.
 */
public class Room {
    private String repoName;
    private String repoDescription;
    private String language;
    private boolean isPrivate;
    private int starCount;
    private String localPath;
    private String remoteUrl;
    private String lastCommit;  // most recent commit message + date

    // Position in world
    private Vector3f doorPosition;  // center of doorway
    private Vector3f roomCenter;    // center of room
    private float doorRotation;     // which way the door faces (0=N, 90=E, 180=S, 270=W)
    private int hallwaySide;        // 0=left, 1=right
    private int floor;              // 0=ground, 1=upper

    // Contents
    private List<Book> books = new ArrayList<>();
    private Room backRoom;  // second room for comments/gists/archive

    // Door animation
    private float doorOpenAmount = 0f;  // 0=closed, 1=fully open (slid up)
    private float doorAnimTarget = 0f;
    private static final float DOOR_ANIM_SPEED = 3.0f;  // units per second

    // Room dimensions
    public static final float ROOM_WIDTH = 6.0f;
    public static final float ROOM_DEPTH = 6.0f;
    public static final float ROOM_HEIGHT = 3.5f;
    public static final float WALL_THICKNESS = 0.2f;
    public static final float DOOR_WIDTH = 1.2f;
    public static final float DOOR_HEIGHT = 2.4f;

    public Room(String repoName) {
        this.repoName = repoName;
    }

    // Getters/setters
    public String getRepoName() { return repoName; }
    public void setRepoName(String name) { this.repoName = name; }
    public String getRepoDescription() { return repoDescription; }
    public void setRepoDescription(String desc) { this.repoDescription = desc; }
    public String getLanguage() { return language; }
    public void setLanguage(String lang) { this.language = lang; }
    public boolean isPrivate() { return isPrivate; }
    public void setPrivate(boolean p) { isPrivate = p; }
    public int getStarCount() { return starCount; }
    public void setStarCount(int s) { starCount = s; }
    public String getLocalPath() { return localPath; }
    public void setLocalPath(String path) { this.localPath = path; }
    public String getRemoteUrl() { return remoteUrl; }
    public void setRemoteUrl(String url) { this.remoteUrl = url; }
    public String getLastCommit() { return lastCommit; }
    public void setLastCommit(String c) { this.lastCommit = c; }

    public Vector3f getDoorPosition() { return doorPosition; }
    public void setDoorPosition(Vector3f pos) { this.doorPosition = pos; }
    public Vector3f getRoomCenter() { return roomCenter; }
    public void setRoomCenter(Vector3f center) { this.roomCenter = center; }
    public float getDoorRotation() { return doorRotation; }
    public void setDoorRotation(float rot) { this.doorRotation = rot; }
    public int getHallwaySide() { return hallwaySide; }
    public void setHallwaySide(int side) { this.hallwaySide = side; }
    public int getFloor() { return floor; }
    public void setFloor(int f) { this.floor = f; }

    public List<Book> getBooks() { return books; }
    public void addBook(Book book) { books.add(book); }
    public Room getBackRoom() { return backRoom; }
    public void setBackRoom(Room room) { this.backRoom = room; }

    // Door animation
    public float getDoorOpenAmount() { return doorOpenAmount; }
    public void openDoor() { doorAnimTarget = 1f; }
    public void closeDoor() { doorAnimTarget = 0f; }
    public void updateDoorAnimation(float dt) {
        if (doorOpenAmount < doorAnimTarget) {
            doorOpenAmount = Math.min(doorAnimTarget, doorOpenAmount + DOOR_ANIM_SPEED * dt);
        } else if (doorOpenAmount > doorAnimTarget) {
            doorOpenAmount = Math.max(doorAnimTarget, doorOpenAmount - DOOR_ANIM_SPEED * dt);
        }
    }

    /**
     * Get display label for the door plaque.
     */
    public String getDisplayLabel() {
        StringBuilder sb = new StringBuilder();
        sb.append(isPrivate ? "[PRIVATE] " : "[PUBLIC] ");
        sb.append(repoName);
        if (language != null && !language.equals("none")) {
            sb.append(" (").append(language).append(")");
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "Room{" + repoName + " @ " + roomCenter + "}";
    }
}
