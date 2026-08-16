package com.mindpalace.world;

import org.joml.Vector3f;

/**
 * A lab device — represents a test file in a repo (schema: Test → Lab device).
 * Glow color encodes test status: green = passing, red = failing, amber = unknown.
 * Lives in the room's "test lab" corner.
 */
public class LabDevice {
    public enum Status { PASSING, FAILING, UNKNOWN }

    private final String testName;   // test file name
    private final String repoName;
    private final Status status;
    private Vector3f position;

    public LabDevice(String testName, String repoName, Status status) {
        this.testName = testName;
        this.repoName = repoName;
        this.status = status;
    }

    /** Glow texture — green/red/amber by status. */
    public int getGlowTexture() {
        switch (status) {
            case PASSING: return com.mindpalace.render.Renderer.TEX_NEON_GREEN;
            case FAILING: return com.mindpalace.render.Renderer.TEX_BOOK_RED;
            default:      return com.mindpalace.render.Renderer.TEX_NEON_AMBER;
        }
    }

    public String getTestName() { return testName; }
    public String getRepoName() { return repoName; }
    public Status getStatus() { return status; }
    public Vector3f getPosition() { return position; }
    public void setPosition(Vector3f p) { this.position = p; }

    /** Detect if a file is a test file. */
    public static boolean isTestFile(String filename) {
        String n = filename.toLowerCase();
        return n.contains("test") || n.contains("spec")
            || n.startsWith("test_") || n.endsWith("_test")
            || n.contains(".test.") || n.contains(".spec.");
    }

    /** Guess test status from filename/content hints (deterministic). */
    public static Status guessStatus(String filename) {
        String n = filename.toLowerCase();
        if (n.contains("fail") || n.contains("broken") || n.contains("todo")) return Status.FAILING;
        if (n.contains("pass") || n.contains("ok")) return Status.PASSING;
        return Status.UNKNOWN;
    }
}
