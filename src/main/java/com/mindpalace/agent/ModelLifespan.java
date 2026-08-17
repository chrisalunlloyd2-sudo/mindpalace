package com.mindpalace.agent;

import java.util.*;
import java.util.concurrent.*;

/**
 * Model lifespan manager — keeps small local models (phi3:mini, tinyllama:1.1b)
 * performing perfectly over long sessions by managing their context window,
 * detecting semantic drift, and applying correctors.
 *
 * The advanced approach (state of the art for small-model longevity):
 *
 *   1. TOKEN BUDGET — each model has a hard context ceiling. History is
 *      trimmed to a sliding window + a rolling summary, so the model never
 *      degrades from context bloat (the #1 cause of small-model collapse).
 *
 *   2. DRIFT DETECTION — every assistant reply is embedded (nomic-embed-text).
 *      We track a moving centroid of recent replies. When a new reply's cosine
 *      similarity to the centroid drops below a threshold, the model has
 *      "drifted" (started rambling, repeating, or going off-task).
 *
 *   3. CORRECTORS — on drift, we inject a corrective nudge: re-anchor the
 *      system prompt, drop the most recent (poisoned) turns, and retrieve the
 *      most relevant memory to pull the model back on task.
 *
 *   4. MEMORY RETRIEVAL — important turns are stored as (embedding, text)
 *      pairs. On drift or on each new query, the top-k most similar memories
 *      are injected as grounding context (lightweight RAG).
 */
public class ModelLifespan {
    private final OllamaClient ollama;
    private final String model;
    private final int tokenBudget;      // hard ceiling (tokens)
    private final float driftThreshold; // cosine below this = drifted

    // Rolling history (bounded)
    private final Deque<Map<String, String>> history = new ArrayDeque<>();
    private String systemPrompt;

    // Drift tracking
    private final Deque<float[]> recentEmbeddings = new ArrayDeque<>();
    private static final int DRIFT_WINDOW = 8;   // look at last N replies
    private int driftCount;
    private int turnCounter;   // sample embeddings, don't embed every turn

    // Memory (embedding -> text), lightweight RAG
    private final List<float[]> memoryVecs = new ArrayList<>();
    private final List<String> memoryTexts = new ArrayList<>();
    private static final int MEMORY_CAP = 200;

    // Rolling summary (compaction)
    private String summary = "";
    private String lastQuery = "";

    // Repetition detection — catch verbatim/near-verbatim loops
    private final Deque<String> recentReplies = new ArrayDeque<>();
    private static final int REPEAT_WINDOW = 6;
    private int repeatCount;

    public ModelLifespan(OllamaClient ollama, String model, int tokenBudget, float driftThreshold) {
        this.ollama = ollama;
        this.model = model;
        this.tokenBudget = tokenBudget;
        this.driftThreshold = driftThreshold;
    }

    public void setSystemPrompt(String prompt) {
        this.systemPrompt = prompt;
    }

    /** Add a turn and return the (possibly corrected) message list to send. */
    public synchronized List<Map<String, String>> addTurn(String role, String content) {
        history.addLast(Map.of("role", role, "content", content));
        if (role.equals("user")) lastQuery = content;

        // Detect drift on assistant replies
        if (role.equals("assistant") && content != null && !content.isEmpty()) {
            detectDrift(content);
            detectRepetition(content);
        }

        // Store as memory if it's substantive
        if (content != null && content.length() > 40) {
            remember(content);
        }

        // Trim to token budget
        trimToBudget();

        return buildMessages();
    }

    /**
     * Full round-trip: add a user turn, send to the model, record the reply.
     * Returns the assistant's response (or null on failure).
     */
    public synchronized String chat(String userMessage) {
        addTurn("user", userMessage);
        String resp = ollama.chat(model, buildMessages(), null);
        if (resp != null && !resp.isEmpty()) {
            addTurn("assistant", resp);
        }
        return resp;
    }

    /** Build the message list: system + summary + sliding window + retrieved memory. */
    private List<Map<String, String>> buildMessages() {
        List<Map<String, String>> out = new ArrayList<>();
        if (systemPrompt != null) out.add(Map.of("role", "system", "content", systemPrompt));
        if (!summary.isEmpty()) {
            out.add(Map.of("role", "system", "content", "Conversation summary so far:\n" + summary));
        }
        // Ground the next call with the top-k memories most similar to the last
        // user query (the lightweight RAG — was stored but never retrieved).
        if (!lastQuery.isEmpty()) {
            List<String> mem = retrieve(lastQuery, 3);
            if (!mem.isEmpty()) {
                out.add(Map.of("role", "system", "content", "Relevant memory:\n- " + String.join("\n- ", mem)));
            }
        }
        out.addAll(history);
        return out;
    }

    /** Detect verbatim/near-verbatim repetition (the "stuck loop" failure mode). */
    private void detectRepetition(String reply) {
        recentReplies.addLast(reply);
        while (recentReplies.size() > REPEAT_WINDOW) recentReplies.removeFirst();
        if (recentReplies.size() < 3) return;

        // Compare the newest reply against recent ones (normalized)
        String norm = normalize(reply);
        int matches = 0;
        for (String r : recentReplies) {
            if (r == reply) continue; // skip self
            if (normalize(r).equals(norm)) matches++;
        }
        if (matches >= 2) {
            repeatCount++;
            System.out.println("[Lifespan] REPETITION detected on " + model
                + " (count=" + repeatCount + ")");
            correct();
        }
    }

    private String normalize(String s) {
        // Collapse whitespace + lowercase for comparison
        return s.replaceAll("\\s+", " ").trim().toLowerCase();
    }

    /** Detect semantic drift via embedding centroid (sampled, not every turn). */
    private void detectDrift(String reply) {
        // Sample: only embed every 3rd reply to cut inference cost
        turnCounter++;
        if (turnCounter % 3 != 0) return;

        float[] vec = ollama.embed(reply);
        if (vec == null) return; // embedding model unavailable — skip

        recentEmbeddings.addLast(vec);
        while (recentEmbeddings.size() > DRIFT_WINDOW) recentEmbeddings.removeFirst();

        if (recentEmbeddings.size() < 3) return; // not enough history yet

        // Centroid of prior replies (exclude the newest)
        float[] centroid = new float[vec.length];
        int n = 0;
        int i = 0;
        for (float[] e : recentEmbeddings) {
            if (i == recentEmbeddings.size() - 1) break; // skip newest
            for (int d = 0; d < centroid.length; d++) centroid[d] += e[d];
            n++;
            i++;
        }
        if (n == 0) return;
        for (int d = 0; d < centroid.length; d++) centroid[d] /= n;

        float sim = OllamaClient.cosine(vec, centroid);
        if (sim < driftThreshold) {
            driftCount++;
            System.out.println("[Lifespan] DRIFT detected on " + model
                + " (cos=" + String.format("%.3f", sim) + ", count=" + driftCount + ")");
            correct();
        }
    }

    /** Apply a corrector: drop poisoned turns + re-anchor. */
    private void correct() {
        // Drop the last 2 turns (the drifted reply + its trigger)
        for (int i = 0; i < 2 && !history.isEmpty(); i++) history.removeLast();

        // Re-anchor: append a corrective system nudge
        history.addFirst(Map.of("role", "system", "content",
            "You drifted off-task. Refocus on the user's actual request. Be concise and specific."));

        // Compact: fold old history into a summary to free budget
        compact();
    }

    /** Fold the oldest turns into a rolling summary (compaction). */
    private void compact() {
        if (history.size() < 6) return;
        // Take the oldest few turns and summarize them
        StringBuilder old = new StringBuilder();
        int toRemove = Math.min(4, history.size() / 2);
        for (int i = 0; i < toRemove && !history.isEmpty(); i++) {
            Map<String, String> m = history.removeFirst();
            old.append(m.get("role")).append(": ").append(m.get("content")).append("\n");
        }
        // Ask the model to summarize (cheap, keeps context small)
        String s = ollama.chat(model, "Summarize this conversation excerpt in 2-3 sentences, preserving key facts and decisions:\n" + old, "");
        if (s != null && !s.isEmpty()) {
            summary = (summary.isEmpty() ? "" : summary + " ") + s;
            if (summary.length() > 2000) summary = summary.substring(summary.length() - 2000);
        }
    }

    /** Store a substantive turn as retrievable memory (sampled to cut cost). */
    private void remember(String text) {
        // Only embed every 3rd substantive turn — memory doesn't need every line
        if (turnCounter % 3 != 0) return;
        float[] vec = ollama.embed(text);
        if (vec == null) return;
        memoryVecs.add(vec);
        memoryTexts.add(text);
        while (memoryVecs.size() > MEMORY_CAP) {
            memoryVecs.remove(0);
            memoryTexts.remove(0);
        }
    }

    /** Retrieve top-k memories most similar to a query (lightweight RAG). */
    public List<String> retrieve(String query, int k) {
        float[] q = ollama.embed(query);
        if (q == null || memoryVecs.isEmpty()) return List.of();
        // Score all memories
        List<int[]> scored = new ArrayList<>();
        for (int i = 0; i < memoryVecs.size(); i++) {
            scored.add(new int[]{i, (int) (OllamaClient.cosine(q, memoryVecs.get(i)) * 1000)});
        }
        scored.sort((a, b) -> b[1] - a[1]);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(k, scored.size()); i++) {
            out.add(memoryTexts.get(scored.get(i)[0]));
        }
        return out;
    }

    /** Trim history to the token budget (rough: 4 chars ≈ 1 token). */
    private void trimToBudget() {
        int tokens = estimateTokens();
        while (tokens > tokenBudget && history.size() > 2) {
            // Fold oldest into summary instead of hard-dropping
            Map<String, String> m = history.removeFirst();
            if (m.get("role").equals("assistant") && m.get("content").length() > 60) {
                summary = (summary.isEmpty() ? "" : summary + " ") + m.get("content");
                if (summary.length() > 2000) summary = summary.substring(summary.length() - 2000);
            }
            tokens = estimateTokens();
        }
    }

    private int estimateTokens() {
        int total = systemPrompt != null ? systemPrompt.length() / 4 : 0;
        total += summary.length() / 4;
        for (Map<String, String> m : history) total += m.get("content").length() / 4;
        return total;
    }

    public int getDriftCount() { return driftCount; }
    public int getRepeatCount() { return repeatCount; }
    public int getHistorySize() { return history.size(); }
    public int getMemorySize() { return memoryTexts.size(); }
    public String getSummary() { return summary; }
}
