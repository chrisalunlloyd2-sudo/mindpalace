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

    /** Set the KG neighborhood (called when the room changes / FOW lifts). */
    public void setKgNodes(List<String> nodes) {
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
    public List<String> kgNodes() { return Collections.unmodifiableList(kgNodes); }
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
        if (!kgNodes.isEmpty()) {
            sb.append(" [kg ");
            for (int i = 0; i < Math.min(kgNodes.size(), 5); i++) {
                if (i > 0) sb.append(',');
                sb.append(kgNodes.get(i));
            }
            if (kgNodes.size() > 5) sb.append("…+").append(kgNodes.size() - 5);
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
}