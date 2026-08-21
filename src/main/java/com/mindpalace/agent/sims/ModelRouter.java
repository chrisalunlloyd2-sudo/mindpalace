package com.mindpalace.agent.sims;

/**
 * ModelRouter — selects the right local SLM for a task by complexity.
 * Ported from SIMS1337, adapted to MindPalace (returns model-name strings,
 * no SLMAgent wrapper, no slf4j). The 4 tiers map to the installed Ollama
 * models: qwen2.5:0.5b (fast) → tinyllama:1.1b (balanced) → phi:latest
 * (reasoning) → phi3:mini (deep).
 */
public class ModelRouter {

    // Tier model names (all confirmed installed via `ollama list`).
    public static final String FAST     = "qwen2.5:0.5b";
    public static final String BALANCED = "tinyllama:1.1b";
    public static final String REASONING = "phi:latest";
    public static final String DEEP     = "phi3:mini";

    /** Select the model tier for a complexity level. */
    public String select(Complexity c) {
        switch (c) {
            case VERY_LOW:
            case LOW:      return FAST;
            case MEDIUM:   return BALANCED;
            case HIGH:     return REASONING;
            case VERY_HIGH:
            case CRITICAL: return DEEP;
            default:       return BALANCED;
        }
    }

    /** Select with a latency ceiling (ms). Fastest model wins under pressure. */
    public String selectWithLatency(Complexity c, long maxLatencyMs) {
        if (maxLatencyMs < 200)  return FAST;
        if (maxLatencyMs < 1000) return BALANCED;
        return select(c);
    }

    /** Estimate latency (ms) for a complexity level on its routed tier. */
    public long estimateLatency(Complexity c) {
        long base;
        switch (select(c)) {
            case FAST:      base = 50;   break;
            case BALANCED:  base = 500;  break;
            case REASONING: base = 2000; break;
            case DEEP:      base = 5000; break;
            default:        base = 1000;
        }
        return (long) (base * c.getMultiplier());
    }

    /** Human-readable reason for a routing decision. */
    public String reason(Complexity c) {
        switch (c) {
            case VERY_LOW:
            case LOW:      return "simple task → fastest model (qwen2.5:0.5b)";
            case MEDIUM:   return "medium complexity → balanced model (tinyllama:1.1b)";
            case HIGH:     return "complex task → reasoning model (phi:latest)";
            case VERY_HIGH:
            case CRITICAL: return "critical task → deep reasoning model (phi3:mini)";
            default:       return "default → balanced model";
        }
    }
}
