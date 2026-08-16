package com.mindpalace.world;

import org.joml.Vector3f;

/**
 * A TODO crystal — represents a TODO/FIXME/HACK comment found in a repo.
 * Hexagonal crystal; height encodes complexity (more words = taller).
 * Agents pick these up and carry them to other rooms (task handoff).
 */
public class TodoCrystal {
    private final String text;       // the TODO comment text
    private final String repoName;   // repo it came from
    private final String filePath;   // file it was found in
    private final int complexity;    // word count → height
    private Vector3f position;       // world position
    private boolean carried;         // being carried by an agent
    private String carriedBy;        // agent name

    public TodoCrystal(String text, String repoName, String filePath) {
        this.text = text;
        this.repoName = repoName;
        this.filePath = filePath;
        this.complexity = Math.max(1, text.split("\\s+").length);
    }

    /** Crystal height in world units — complexity mapped to [0.15, 0.6]. */
    public float getHeight() {
        return Math.min(0.6f, 0.15f + complexity * 0.02f);
    }

    public String getText() { return text; }
    public String getRepoName() { return repoName; }
    public String getFilePath() { return filePath; }
    public int getComplexity() { return complexity; }
    public Vector3f getPosition() { return position; }
    public void setPosition(Vector3f p) { this.position = p; }
    public boolean isCarried() { return carried; }
    public void setCarried(boolean c, String by) { this.carried = c; this.carriedBy = by; }
    public String getCarriedBy() { return carriedBy; }

    /** Short label for rendering. */
    public String getLabel() {
        String t = text.trim();
        if (t.length() > 24) t = t.substring(0, 22) + "..";
        return t;
    }
}
