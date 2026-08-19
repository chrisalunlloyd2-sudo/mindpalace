package com.mindpalace.agent;

/**
 * Central model configuration — single source of truth for which local SLMs
 * drive the agents. Tune here, not scattered across the codebase.
 *
 * Chosen for coherence-per-token on CPU: llama3.2:3b (tool) and gemma2:2b
 * (critic) hold a conversation thread far better than phi3:mini/tinyllama:1.1b,
 * while still running comfortably on a local machine.
 */
public final class ModelConfig {
    private ModelConfig() {}

    /** Tool agent — proposes edits, reads/writes files. */
    public static final String TOOL_MODEL = "llama3.2:1b";

    /** Critic agent — reviews the tool agent's proposals. */
    public static final String CRITIC_MODEL = "qwen2.5:0.5b";

    /** Code-suggestion model for the in-game book editor (small + fast). */
    public static final String CODE_MODEL = "qwen2.5:0.5b";

    /**
     * Direct conversational model for user chat. A mid-size model that answers
     * the player promptly (immediate path, no 5-min spacing) and holds a thread.
     */
    public static final String CHAT_MODEL = "llama3.2:3b";

    /** Embedding model for drift detection + RAG memory. */
    public static final String EMBED_MODEL = "nomic-embed-text";

    /** Token budgets (below each model's context ceiling). */
    public static final int TOOL_BUDGET = 2000;
    public static final int CRITIC_BUDGET = 1000;

    /** Drift threshold — cosine similarity below this = drifted. */
    public static final float DRIFT_THRESHOLD = 0.55f;
}
