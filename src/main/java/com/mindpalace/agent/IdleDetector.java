package com.mindpalace.agent;

/**
 * Idle detection + agent pacing.
 *
 * Senses whether a human is actively using the computer:
 *   - If idle (no input for a while), agents work HARDER (faster cycles, more actions).
 *   - If the user is playing/typing, agents QUIET DOWN (slower cycles, fewer actions).
 *
 * This keeps the SLMs from degrading performance while the user is present,
 * and lets them do more work when nobody's watching.
 */
public class IdleDetector {
    private volatile long lastActivity = System.currentTimeMillis();
    private volatile boolean idle;
    private static final long IDLE_THRESHOLD_MS = 60_000; // 60s of no input = idle

    /** Call whenever the user does something (key, mouse, chat). */
    public void markActivity() {
        lastActivity = System.currentTimeMillis();
        idle = false;
    }

    /** Update idle state (call each frame). */
    public void update() {
        idle = (System.currentTimeMillis() - lastActivity) > IDLE_THRESHOLD_MS;
    }

    public boolean isIdle() { return idle; }

    /**
     * Agent pacing multiplier.
     * Idle → 1.0 (full speed). Active → 0.3 (quiet down).
     */
    public float getPacingMultiplier() {
        return idle ? 1.0f : 0.3f;
    }

    /** Cycle interval in ms, scaled by pacing. */
    public long getCycleInterval(long baseMs) {
        return (long) (baseMs / getPacingMultiplier());
    }

    /**
     * Model spacing in ms. Idle → 5 min floor (agents work). Active/playing →
     * 15 min (quiet down, low resources). Never below the 5-min hard floor.
     */
    public long getSpacingMs() {
        return idle ? 5 * 60 * 1000L : 15 * 60 * 1000L;
    }
}
