package com.mindpalace.agent;

import java.util.*;

/**
 * ContextKit — the per-model context wrapper. Each model gets its OWN kit;
 * the kits never mix. Every turn, the model's kit rides the call: the LoRA
 * adapter state, the KG neighborhood for the current room, and the model's
 * KV store all travel together as one renderable bundle.
 *
 * The cold-shot doctrine: models are loaded, given their kit, and unloaded.
 * The kit is the ONLY thing that persists between turns — it carries the
 * model's identity, memory, and world-slice so a freshly-loaded model picks
 * up exactly where it left off. Slow but smart.
 *
 * Modular by construction: one model = one kit = one wrapper.
 */
public final class ContextKit {

    public final String model;
    private final com.mindpalace.agent.sims.AdapterType loraAdapter;
    private final KVTree kv;
    private final List<String> kgNodes = new ArrayList<>();   // room/repo neighborhood
    private final Map<String, Object> loraContext = new LinkedHashMap<>();
    private long turns = 0;

    public ContextKit(String model, com.mindpalace.agent.sims.AdapterType loraAdapter, long kvSeed) {
        this.model = model;
        this.loraAdapter = loraAdapter;
        this.kv = new KVTree(model, kvSeed);
    }

    /** Set the KG neighborhood (called when the room changes / FOW lifts).
     *  Synchronized (#106): setKgNodes (clear→addAll) races render()/kgNodes()
     *  from worker threads — clear-during-iterate would corrupt the render. */
    public synchronized void setKgNodes(List<String> nodes) {
        kgNodes.clear();
        if (nodes != null) kgNodes.addAll(nodes);
    }

    /** Remember a fact in this model's KV store (its private memory). */
    public void remember(String key, float v) { kv.set(key, v); }

    /** Probabilistic gate — should this model act on key? (per-model policy) */
    public boolean shouldAct(String key) { return kv.shouldAct(key); }

    /** Store a LoRA context value (rides with the adapter state). */
    public void putLoraContext(String key, Object v) { loraContext.put(key, v); }

    @SuppressWarnings("unchecked")
    public <T> T getLoraContext(String key) { return (T) loraContext.get(key); }

    /** Snapshot of the KV store (for telemetry/debug). */
    public Map<String, Float> kvSnapshot() { return kv.snapshot(); }

    public com.mindpalace.agent.sims.AdapterType loraAdapter() { return loraAdapter; }
    /** Snapshot of the KG neighborhood (#106): callers must not iterate the
     *  live list while another thread calls setKgNodes. */
    public synchronized List<String> kgNodes() { return new ArrayList<>(kgNodes); }
    public long turns() { return turns; }

    /**
     * Render the kit as a compact system-prompt prefix. This is what rides
     * EVERY model call — the model sees its own adapter role, its KG
     * neighborhood, and its KV-gated personality in ~5 lines.
     */
    public String render() {
        turns++;
        StringBuilder sb = new StringBuilder(256);
        sb.append("[ctx model=").append(model)
          .append(" lora=").append(loraAdapter).append(']');
        List<String> nodes = kgNodes();
        if (!nodes.isEmpty()) {
            sb.append(" [kg ");
            for (int i = 0; i < Math.min(nodes.size(), 5); i++) {
                if (i > 0) sb.append(',');
                sb.append(nodes.get(i));
            }
            if (nodes.size() > 5) sb.append("…+").append(nodes.size() - 5);
            sb.append(']');
        }
        Map<String, Float> snap = kv.snapshot();
        if (!snap.isEmpty()) {
            sb.append(" [kv");
            int i = 0;
            for (Map.Entry<String, Float> e : snap.entrySet()) {
                if (i++ >= 4) break;
                sb.append(' ').append(e.getKey()).append('=')
                  .append(String.format("%.2f", e.getValue()));
            }
            sb.append(']');
        }
        return sb.toString();
    }

    /** Trait phrases for renderVoice: key, then high (>= 0.66), mid (>= 0.34), low. Fixed order, so a prompt never reshuffles. */
    private static final String[][] VOICE = {
        {"curiosity", "You are very curious.", "You are a little curious.", "You are not very curious."},
        {"wanderlust", "You love to wander.", "You wander now and then.", "You like to stay put."},
        {"riskTolerance", "You love taking risks.", "You take some risks.", "You avoid risks."},
        {"verbosity", "You like to talk.", "You say enough and no more.", "You keep it short."},
        {"gossip", "You love to share news.", "You share news now and then.", "You keep news to yourself."},
        {"focus", "You stick with one thing until it is done.", "You stay on a task for a while.", "You move on quickly."},
    };

    /**
     * The kit for a model that talks to the player: the same KV and KG, said in plain English. Small models read
     * render()'s tags as content and refuse ("I can't continue this conversation as it's in a language that I don't
     * understand"); measured 2026-11-05 in the box, qwen2.5:0.5b, 20 replies each, refusals: tags 15, role line plus
     * tags 5, role line plus these words 0. The adapter tag is left out: chat is not CODE work.
     */
    public String renderVoice() {
        turns++;
        StringBuilder sb = new StringBuilder(256);
        Map<String, Float> snap = kv.snapshot();
        for (String[] t : VOICE) {
            Float v = snap.get(t[0]);
            if (v == null) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(v >= 0.66f ? t[1] : v >= 0.34f ? t[2] : t[3]);
        }
        List<String> nodes = kgNodes();
        if (!nodes.isEmpty()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append("Rooms near you: ").append(String.join(", ", nodes.subList(0, Math.min(nodes.size(), 5))));
            if (nodes.size() > 5) sb.append(" and ").append(nodes.size() - 5).append(" more");
            sb.append('.');
        }
        return sb.toString();
    }
}