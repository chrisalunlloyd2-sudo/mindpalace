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

    /**
     * MP-014: extra resident quorum voters. A 3-voter roster starves whenever
     * one model is cold or slow — ballots cascade to abstain-timeout and the
     * cycle returns real:0 (permanent NO_QUORUM). Five voters tolerate two
     * stalled ballots and still reach quorumMin=3. Both ship in the local
     * Ollama roster already — zero download; tool/critic lanes unchanged.
     */
    public static final String EXTRA_VOTER_A = "gemma2:2b";
    public static final String EXTRA_VOTER_B = "phi3:mini";

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
     * #134 (H43, step 103): flash tier — the cheapest lane, for trivial tasks.
     * Trivial-complexity tasks route here (ModelRouter.selectFlash) and are
     * NEVER escalated above flash: routing is a one-way decision per task, not
     * a retry-upgrade path. Same install as the critic model (qwen2.5:0.5b) —
     * already resident, zero extra VRAM/RAM cost.
     */
    public static final String FLASH_MODEL = "qwen2.5:0.5b";

    /** Flash token budget — trivial answers only. */
    public static final int FLASH_BUDGET = 500;

    /**
     * #135 (H44, step 104): quota firewall. false = local-only BY CONSTRUCTION:
     * every model string constant in this file is a local Ollama tag validated
     * below, and OllamaClient rejects anything that names a remote endpoint.
     * There is no cloud client in this repo; if one is ever added it must flip
     * this switch explicitly AND carry its own opt-in — the flash lane never
     * consults it (see OllamaClient.assertLocalFlash).
     */
    public static final boolean BURST_CLIENT_ENABLED = false;

    /** Token budgets (below each model's context ceiling). */
    public static final int TOOL_BUDGET = 4000;
    public static final int CRITIC_BUDGET = 1000;

    /** Drift threshold — cosine similarity below this = drifted. */
    public static final float DRIFT_THRESHOLD = 0.55f;

    /** Quorum needs at least this many distinct voters (WeightedQuorumVote quorumMin). */
    public static final int MIN_VOTERS = 3;

    /**
     * The quorum roster. Default: tool, critic, tie-breaker + the two MP-014 extras.
     * {@code -Dmindpalace.voters=a,b,c} replaces it (small boxes: 12 GB RAM, HDD,
     * server cap of 2 resident models). There the default rotates 5 distinct
     * models (~9 GB of blobs, more with chat) through 2 slots, so nearly every
     * ballot re-reads a model from the spinning disk and the whole machine
     * waits on the disk queue (2026-11-07: queue 33, 21 MB/s of page faults).
     * Three small voters (~2.8 GB) stay in the OS file cache, so their reloads
     * come from RAM. Fewer than MIN_VOTERS distinct entries, or a cloud model,
     * falls back to the default: the quorum must stay valid and local.
     *
     * <p>Without the flag, a machine under SMALL_BOX_BYTES of RAM gets
     * SMALL_BOX_VOTERS automatically, so a relaunch by any script (sync, release,
     * Hermes) cannot bring the 5-model rotation back. {@code -Dmindpalace.voters=all}
     * forces the full roster anywhere.</p>
     */
    public static java.util.List<String> voters() {
        java.util.List<String> def = java.util.List.of(TOOL_MODEL, CRITIC_MODEL, TIE_MODEL, EXTRA_VOTER_A, EXTRA_VOTER_B);
        String spec = System.getProperty("mindpalace.voters");
        if ((spec == null || spec.isBlank()) && smallBox()) return SMALL_BOX_VOTERS;
        return parseVoters(spec, def);   // "all" has < MIN_VOTERS entries -> def
    }

    /** Below this much physical RAM the small roster is the default (matches check_ollama_capacity SMALL_BOX_GB). */
    public static final long SMALL_BOX_BYTES = 16L << 30;
    /** Small-box roster: tool, critic, tie-breaker (~2.8 GB of blobs, stays in the OS file cache). */
    public static final java.util.List<String> SMALL_BOX_VOTERS = java.util.List.of(TOOL_MODEL, CRITIC_MODEL, TIE_MODEL);

    static boolean smallBox() {
        try {
            var os = (com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            long total = os.getTotalMemorySize();
            return total > 0 && total < SMALL_BOX_BYTES;
        } catch (Throwable t) {
            return false;   // unknown RAM: keep the default roster
        }
    }

    static java.util.List<String> parseVoters(String spec, java.util.List<String> def) {
        if (spec == null || spec.isBlank()) return def;
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (String s : spec.split(",")) {
            String m = s.trim();
            if (m.isEmpty()) continue;
            if (m.toLowerCase(java.util.Locale.ROOT).contains("cloud")) return def;   // LOCAL ONLY
            out.add(m);
        }
        return out.size() >= MIN_VOTERS ? java.util.List.copyOf(out) : def;
    }
}
