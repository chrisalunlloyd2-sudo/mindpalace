package com.mindpalace.agent.sims;

/**
 * ModelRouter — selects the right local SLM for a task by complexity.
 * Ported from SIMS1337, adapted to MindPalace (returns model-name strings,
 * no SLMAgent wrapper, no slf4j). The 4 tiers map to the installed Ollama
 * models: qwen2.5:0.5b (fast) → tinyllama:1.1b (balanced) → phi:latest
 * (reasoning) → phi3:mini (deep).
 *
 * <p>Fallback/degradation (SIMS1337 parity, task t_8452f017): SIMS1337's
 * ModelPool.tryModel() walks candidates and falls back to the smallest known
 * model (qwen2.5:0.5b). The MindPalace equivalent lives on
 * {@link ModelPool#selectAvailable(Complexity)} — probe once with
 * listModels(), then primary → tier ladder → FAST. {@link #select(Complexity)}
 * keeps the exact 1:1 SIMS1337 mapping (selftest check 19 + AgentManager pin
 * it); the availability-aware path is {@link #selectAvailable(Complexity)}
 * and never changes what {@link #select} returns.
 */
public class ModelRouter {

    // Tier model names (all confirmed installed via `ollama list`).
    public static final String FAST     = "qwen2.5:0.5b";
    public static final String BALANCED = "tinyllama:1.1b";
    public static final String REASONING = "phi:latest";
    public static final String DEEP     = "phi3:mini";

    /** Optional availability-aware pool (null = legacy pure mapping). */
    private final ModelPool pool;

    /** Legacy constructor — 1:1 SIMS1337 mapping, no availability probing. */
    public ModelRouter() { this.pool = null; }

    /** Pool-backed router: {@link #selectAvailable} consults pool fallbacks. */
    public ModelRouter(ModelPool pool) { this.pool = pool; }

    /** The pool backing this router, or null for the legacy mapping-only form. */
    public ModelPool pool() { return pool; }

    /** Select the model tier for a complexity level (SIMS1337 1:1 mapping). */
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

    /**
     * Availability-aware selection: primary tier model when installed, else
     * the tier's fallback ladder, else FAST. Without a pool (or before any
     * probe) this equals {@link #select(Complexity)}.
     */
    public String selectAvailable(Complexity c) {
        if (pool == null) return select(c);
        return pool.selectAvailable(c);
    }

    /** Tier for a complexity level (from the pool when present, else a fresh mapping). */
    public ModelPool.Tier tierFor(Complexity c) {
        return pool != null ? pool.tierFor(c) : new ModelPool().tierFor(c);
    }

    /** Select with a latency ceiling (ms). Fastest model wins under pressure. */
    public String selectWithLatency(Complexity c, long maxLatencyMs) {
        if (maxLatencyMs < 200)  return FAST;
        if (maxLatencyMs < 1000) return BALANCED;
        return select(c);
    }

    /**
     * Availability-aware + latency-capped selection: the latency caps win
     * first (SIMS1337 order: caps force FAST/BALANCED regardless of tier),
     * then the pool's fallback ladder.
     */
    public String selectAvailableWithLatency(Complexity c, long maxLatencyMs) {
        if (maxLatencyMs < 200)  return FAST;
        if (maxLatencyMs < 1000) return BALANCED;
        return selectAvailable(c);
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

    /**
     * Reason for a FALLBACK-routed decision (the pool picked a non-primary). */
    public String fallbackReason(Complexity c, String chosen) {
        String primary = select(c);
        if (chosen.equals(primary)) return reason(c);
        // SPEC NOTE: SIMS1337's ModelRouter has no fallback-reason string — its
        // tryModel lives in ModelPool and only logs. This wording is the
        // MindPalace addition; flagged, not a deviation from a spec'd string.
        return "degraded: " + primary + " unavailable → " + chosen
            + " (" + reason(c) + ")";
    }
}
