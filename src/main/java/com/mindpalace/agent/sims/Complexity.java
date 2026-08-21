package com.mindpalace.agent.sims;

/**
 * Task complexity levels for model routing. Ported from SIMS1337.
 * The multiplier scales estimated latency; the level picks the model tier.
 */
public enum Complexity {
    VERY_LOW(0.1),
    LOW(0.3),
    MEDIUM(0.6),
    HIGH(0.8),
    VERY_HIGH(0.95),
    CRITICAL(1.0);

    private final double multiplier;

    Complexity(double multiplier) { this.multiplier = multiplier; }

    public double getMultiplier() { return multiplier; }

    /** Heuristic: score a prompt's complexity from length + signal words. */
    public static Complexity estimate(String prompt) {
        if (prompt == null || prompt.isEmpty()) return LOW;
        int len = prompt.length();
        String lower = prompt.toLowerCase();
        int signals = 0;
        for (String w : new String[]{"refactor", "architecture", "design", "optimize",
                "security", "concurrency", "deadlock", "race", "migrate", "rewrite",
                "algorithm", "complex", "critical", "urgent", "bug", "fix", "review"}) {
            if (lower.contains(w)) signals++;
        }
        double score = Math.min(1.0, (len / 2000.0) * 0.5 + signals * 0.15);
        if (score < 0.3) return LOW;
        if (score < 0.6) return MEDIUM;
        if (score < 0.8) return HIGH;
        if (score < 0.95) return VERY_HIGH;
        return CRITICAL;
    }
}
