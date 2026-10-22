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
    private volatile String commitLedger; // H37 (#122): last 10 commits "sha subject" (\n-separated, newest first) for the door engraving
    private boolean fogged;     // fog-of-war: hidden until explored (private/remote repos)

    // Position in world
    private Vector3f doorPosition;  // center of doorway
    private Vector3f roomCenter;    // center of room
    private float doorRotation;     // which way the door faces (0=N, 90=E, 180=S, 270=W)
    private int hallwaySide;        // 0=left, 1=right
    private int floor;              // 0=ground, 1=upper

    // Contents
    // Thread-safe (issue #12): books are iterated by the render thread, the
    // LiveUpdateManager poller thread and agent NPCs while BookEditor and
    // BookViewer remove entries through the live getBooks() reference.
    // CopyOnWriteArrayList turns every iteration into an immutable snapshot.
    private List<Book> books = new java.util.concurrent.CopyOnWriteArrayList<>();
    private List<LabDevice> labDevices = new ArrayList<>();
    private Room backRoom;  // second room for comments/gists/archive
    private String posterImagePath;  // a repo image (png/jpg) to render on the poster

    // Door animation
    private float doorOpenAmount = 0f;  // 0=closed, 1=fully open (slid up)
    private float doorAnimTarget = 0f;
    public static final float DOOR_ANIM_SPEED = 3.0f;  // units per second

    // Room dimensions
    public static final float ROOM_WIDTH = 6.0f;
    public static final float ROOM_DEPTH = 6.0f;
    public static final float ROOM_HEIGHT = 3.5f;
    public static final float WALL_THICKNESS = 0.2f;
    /** The front wall is a cube WALL_THICKNESS thick centred on the door line, so anything mounted on its room-facing
     *  surface (poster board, bars, image, text) must start this far in front of the centre line or it is buried in the wall. */
    public static final float WALL_FACE = WALL_THICKNESS / 2f;
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
    public String getCommitLedger() { return commitLedger; }
    public void setCommitLedger(String l) { this.commitLedger = l; }
    public boolean isFogged() { return fogged; }
    public void setFogged(boolean f) { this.fogged = f; }

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
    public List<LabDevice> getLabDevices() { return labDevices; }
    public void addLabDevice(LabDevice d) { labDevices.add(d); }
    public Room getBackRoom() { return backRoom; }
    public void setBackRoom(Room room) { this.backRoom = room; }
    public String getPosterImagePath() { return posterImagePath; }
    // Heatmap activity (M3 step 123): commits in the last 30 days (0 if unknown)
    private int activity30d = 0;
    public int getActivity30d() { return activity30d; }
    public void setActivity30d(int a) { this.activity30d = a; }

    // H36 (#125): repo-health rollup, 0..100 (higher = healthier). Computed by
    // RepoScanner/RepoMapper from inputs the scan already has: activity30d,
    // commit presence, TODO density in scanned books. Rooms start neutral
    // (unknown = 50) so rooms only glow-shift once a scan has actually run.
    private volatile int healthScore = 50;
    public int getHealthScore() { return healthScore; }
    public void setHealthScore(int s) { this.healthScore = Math.max(0, Math.min(100, s)); }

    public void setPosterImagePath(String p) { this.posterImagePath = p; }

    // Door animation
    public float getDoorOpenAmount() { return doorOpenAmount; }
    public float getDoorAnimTarget() { return doorAnimTarget; }
    public void openDoor() { doorAnimTarget = 1f; }
    public void closeDoor() { doorAnimTarget = 0f; }
    public void updateDoorAnimation(float dt) {
        if (doorOpenAmount < doorAnimTarget) {
            doorOpenAmount = Math.min(doorAnimTarget, doorOpenAmount + DOOR_ANIM_SPEED * dt);
        } else if (doorOpenAmount > doorAnimTarget) {
            doorOpenAmount = Math.max(doorAnimTarget, doorOpenAmount - DOOR_ANIM_SPEED * dt);
        }
        // QUAL-5: advance the dissolve clock alongside the slide. It starts
        // the moment the door begins opening and never rewinds while the
        // door stays open (no auto-close). Closing resets the clock so a
        // later open-dissolve plays again from the start.
        if (doorAnimTarget > 0f && doorOpenAmount > 0f) {
            portalDissolveSec += dt;
        } else if (doorAnimTarget == 0f) {
            portalDissolveSec = 0f;
        }
    }

    // ── QUAL-5 portal dissolve (flag-gated; GL-free state — all math lives
    //    here so selftests can drive it without a GL context) ──────────────
    /** QUAL-5: the dissolve's own clock, seconds since this door started
     *  opening. 0 = closed, rises through the ~0.5 s dissolve as the door
     *  leaf crossfades into the portal quad, keeps counting while fully
     *  open. Set from doorOpenAmount's climb; NOT reset while open (doors
     *  stay open — no auto-close cycle). */
    private volatile float portalDissolveSec = 0f;

    /** QUAL-5 dissolve length: ~0.5 s from Enter to fully-open portal. */
    public static final float PORTAL_DISSOLVE_SECONDS = 0.5f;
    /** QUAL-5: fraction of the dissolve still revealing panel pixels.
     *  1.0 = solid door panel, 0.0 = panel fully dissolved into the portal
     *  quad. Linear (1 - dissolve/length) so the census math stays exact. */
    public float getPortalPanelAlpha() {
        if (dissolveDone()) return 0f;
        return Math.max(0f, 1f - portalDissolveSec / PORTAL_DISSOLVE_SECONDS);
    }
    /** QUAL-5: portal appearance amount (crossfade partner of panel alpha
     *  — reads as 1 - alpha but clamped past the dissolve end). */
    public float getPortalAlpha() {
        if (dissolveDone()) return doorOpenAmount < 1f ? 0f : 1f;
        return Math.min(1f, portalDissolveSec / PORTAL_DISSOLVE_SECONDS);
    }
    /** QUAL-5: dissolve finished (panel gone, pure portal). */
    public boolean dissolveDone() {
        return portalDissolveSec >= PORTAL_DISSOLVE_SECONDS;
    }
    /** QUAL-5: advance the dissolve clock with the same dt as the slide
     *  (called from updateDoorAnimation so a single tick path drives both). */
    public float getPortalDissolveSec() { return portalDissolveSec; }
    /** QUAL-5 (e2e strip + selftest only): snap the dissolve clock to an
     *  exact second count without an animation tick — deterministic frames
     *  for the 30-34 label series. */
    public void snapPortalDissolve(float sec) {
        portalDissolveSec = Math.max(0f, sec);
    }
    /** QUAL-5 (e2e strip only): pin the slide amount directly, so a strip
     *  waypoint re-derives the exact open-path state (min(1, 3·t)) every
     *  frame instead of feeding more ticks into updateDoorAnimation — the
     *  coupled accumulate-then-snap was the waypoint drift vector. */
    public void snapDoorOpenAmount(float open) {
        doorOpenAmount = Math.max(0f, Math.min(1f, open));
    }
    /** QUAL-5: snapshot for the strip census / probe — one line, GL-free. */
    public String portalProbeString() {
        return String.format("doorOpen=%.3f dissolve=%.3fs panelA=%.3f portalA=%.3f done=%b",
            doorOpenAmount, portalDissolveSec, getPortalPanelAlpha(), getPortalAlpha(),
            dissolveDone());
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

    /**
     * Per-room wall/floor accent tint (RGB, 1.0 = neutral) keyed by the repo's
     * dominant language. Gives each room a distinct "personality" at a glance.
     */
    public float[] getTint() {
        if (language == null) return new float[]{1.0f, 1.0f, 1.0f};
        switch (language) {
            case "Java":       return new float[]{0.85f, 1.0f, 0.85f};  // green
            case "Python":     return new float[]{0.85f, 0.9f, 1.0f};   // blue
            case "JavaScript": return new float[]{1.0f, 0.95f, 0.7f};   // yellow
            case "TypeScript": return new float[]{0.8f, 0.9f, 1.0f};    // steel blue
            case "C++":        return new float[]{1.0f, 0.85f, 0.85f};  // red
            case "C#":         return new float[]{0.85f, 0.85f, 1.0f};  // violet
            case "Rust":       return new float[]{1.0f, 0.8f, 0.6f};    // rust orange
            case "Go":         return new float[]{0.7f, 0.95f, 1.0f};   // cyan
            case "Ruby":       return new float[]{1.0f, 0.7f, 0.75f};   // ruby
            case "PHP":        return new float[]{0.85f, 0.8f, 1.0f};   // indigo
            case "Kotlin":     return new float[]{0.9f, 0.7f, 1.0f};    // purple
            case "Shell":      return new float[]{0.9f, 0.9f, 0.9f};    // grey
            case "HTML":       return new float[]{1.0f, 0.8f, 0.5f};    // orange
            case "CSS":        return new float[]{0.8f, 0.85f, 1.0f};   // light blue
            case "Markdown":   return new float[]{1.0f, 1.0f, 0.9f};    // cream
            case "SQL":        return new float[]{0.8f, 0.9f, 0.85f};   // teal
            default:           return new float[]{1.0f, 1.0f, 1.0f};
        }
    }

    @Override
    public String toString() {
        return "Room{" + repoName + " @ " + roomCenter + "}";
    }
}
