package com.mindpalace.agent.sims;

/**
 * Deterministic proposal-type rotation for the quorum engine (TASK_0128 redo).
 *
 * Rotates through the five proposal types defined by the archived TASK_0128
 * spec — system_health_check → feature_iteration → performance_optimization
 * → code_quality → agent_training → repeat — using a monotonically
 * increasing cycle counter. Deterministic on purpose: same call-sequence
 * position => same type, so analysis scripts can reproduce and verify the
 * sequence from the ledger alone.
 */
public final class ProposalRotator {
    /** The five proposal types from the TASK_0128 spec, in rotation order. */
    public static final String[] PROPOSAL_TYPES = {
        "system_health_check",
        "feature_iteration",
        "performance_optimization",
        "code_quality",
        "agent_training"
    };

    private long cycleCounter = 0;

    /** Next proposal type in the rotation and bump the counter. */
    public synchronized String next() {
        String type = PROPOSAL_TYPES[(int) (cycleCounter % PROPOSAL_TYPES.length)];
        cycleCounter++;
        return type;
    }

    /** Peek at the next type without consuming it (for tests and HUD). */
    public synchronized String peek() {
        return PROPOSAL_TYPES[(int) (cycleCounter % PROPOSAL_TYPES.length)];
    }

    /** Monotonic cycle counter (number of types already consumed). */
    public synchronized long cycles() {
        return cycleCounter;
    }
}