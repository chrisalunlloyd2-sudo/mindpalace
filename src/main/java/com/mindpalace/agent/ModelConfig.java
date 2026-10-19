package com.mindpalace.agent;

/**
 * Central model configuration — single source of truth for which local SLMs
 * drive the agents. Tune here, not scattered across the codebase.
 *
 * Chosen for coherence-per-token on CPU: llama3.2:1b (tool) and
 * qwen2.5:0.5b (critic) hold a conversation thread far better than
 * phi3:mini/tinyllama:1.1b, while still running comfortably on a local
 * machine. llama3.2:3b serves direct user chat.
 */
public final class ModelConfig {
    private ModelConfig() {}

    /** Tool agent — proposes edits, reads/writes files. */
    public static final String TOOL_MODEL = "llama3.2:1b";

    /** Critic agent — reviews the tool agent's proposals. */
    public static final String CRITIC_MODEL = "qwen2.5:0.5b";

    /** Tie-breaker voter — breaks 1-1 ties in quorum votes (1.1GB, cold-shot). */
    public static final String TIE_MODEL = "deepseek-r1:1.5b";

    /** Code-suggestion model for the in-game book editor (small + fast). */
    public static final String CODE_MODEL = "qwen2.5:0.5b";

    /**
     * Direct conversational model for user chat. A mid-size model that answers
     * the player promptly (immediate path, no 5-min spacing) and holds a thread.
     */
    public static final String CHAT_MODEL = "llama3.2:3b";

    /** Embedding model for drift detection + RAG memory. */
    public static final String EMBED_MODEL = "nomic-embed-text";

    /**
     * Agent-loop Ollama decoder threads default (PERF-A4, card t_7c3d3102;
     * Architect OK 2026-10-19). 9-arm × 10-run matrix on this box: tok/s scales
     * with num_thread only — 1→7.9, 2→12.5 (+55%), 3→14.0 (+75%); t2→t3 buys
     * +13% for +50% threads, and frame stats (p50/p95) are flat across the
     * whole predict/thread grid, so 2 is the sweet spot. num_predict measured
     * no effect (32..128 identical pacing) and is NOT shipped here. Applied in
     * OllamaClient.applyOptions() to every chat/tool call; override with
     * -Dmindpalace.ollama.numThread or env MP_OLLAMA_NUM_THREAD (0 = omit the
     * option entirely, restoring the daemon default).
     */
    public static final int OLLAMA_NUM_THREAD = 2;

    /** Token budgets (below each model's context ceiling). */
    public static final int TOOL_BUDGET = 4000;
    public static final int CRITIC_BUDGET = 1000;

    /** Drift threshold — cosine similarity below this = drifted. */
    public static final float DRIFT_THRESHOLD = 0.55f;
}
