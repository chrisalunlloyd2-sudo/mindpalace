package com.mindpalace.agent.sims;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;

/**
 * LoRASwitcher — circular adapter tree for instant weight switching.
 * Ported from SIMS1337 (no slf4j). Models stay warm; only the active LoRA
 * adapter changes (<100ms). Each adapter carries a per-type context map so
 * the tool agent can pin language/domain state per switch.
 */
public class LoRASwitcher {

    private final Map<AdapterType, LoRAAdapter> adapters = new HashMap<>();
    private final Queue<AdapterType> circular = new ArrayDeque<>();
    private LoRAAdapter current;
    private long switchCount;
    private long totalSwitchTimeMs;
    private long weightApplies;

    public LoRASwitcher() {
        for (AdapterType t : AdapterType.values()) {
            adapters.put(t, new LoRAAdapter(t));
            circular.offer(t);
        }
        current = adapters.get(AdapterType.CHAT);
    }

    /** Switch to a specific adapter. Returns timing info. */
    /** #354: whoever owns telemetry can hear about adapter state changes. */
    private static volatile java.util.function.Consumer<String> notifier;

    /** #354: set the sink that records adapter changes (telemetry/log). */
    public static void setNotifier(java.util.function.Consumer<String> sink) { notifier = sink; }

    /** #354: the honest description of what an "adapter switch" currently does. */
    public static String simulationNote(AdapterType type, String detail) {
        return "[LoRA] adapter " + type + " " + detail
            + " (simulation only — no live Ollama model swap yet)";
    }

    private static void announce(String line) {
        System.out.println(line);
        java.util.function.Consumer<String> sink = notifier;
        if (sink != null) {
            try { sink.accept(line); } catch (RuntimeException ignored) { /* telemetry must never break a switch */ }
        }
    }

    public SwitchResult switchAdapter(AdapterType type) {
        long start = System.nanoTime();
        LoRAAdapter next = adapters.get(type);
        if (next == null) return new SwitchResult(false, -1, "unknown adapter");
        if (next == current) return new SwitchResult(true, 0, "already active");

        current.unload();
        next.load();
        AdapterType old = current.getType();
        current = next;
        switchCount++;

        long ms = (System.nanoTime() - start) / 1_000_000;
        totalSwitchTimeMs += ms;
        announce(simulationNote(type, "switch " + old + " -> " + type));
        return new SwitchResult(true, ms, old + " → " + type);
    }

    /** Rotate to the next adapter in the circular buffer. */
    public SwitchResult switchToNext() {
        circular.offer(circular.poll());
        return switchAdapter(circular.peek());
    }

    /**
     * Install an elected weight-set on one adapter (AdapterRegistry.electAndApply). If that adapter is the active one
     * it is reloaded now, so a win changes the live weights immediately; otherwise the new set loads on the next
     * switch to it. Same id + same weights is a no-op (no reload, no count). A null id restores the base weights.
     * Returns true when weights changed.
     */
    public synchronized boolean applyWeights(AdapterType type, String id, Map<String, Double> weights) {
        LoRAAdapter a = adapters.get(type);
        if (a == null) return false;
        Map<String, Double> w = (id == null || weights == null) ? Map.of() : Map.copyOf(weights);
        if (java.util.Objects.equals(id, a.weightsId) && w.equals(a.weights)) return false;
        a.weightsId = id;
        a.weights = w;
        weightApplies++;
        if (a == current) { a.unload(); a.load(); }
        announce(simulationNote(type, "weights=" + (id == null ? "base" : id)));
        return true;
    }

    /** Elected weight-set id an adapter carries, or null for its base weights. */
    public String weightsId(AdapterType type) { LoRAAdapter a = adapters.get(type); return a == null ? null : a.weightsId; }
    public Map<String, Double> weights(AdapterType type) { LoRAAdapter a = adapters.get(type); return a == null ? Map.of() : a.weights; }
    /** The weight-set id active right now (the current adapter's). */
    public String currentWeightsId() { return current.weightsId; }
    public long getWeightApplies() { return weightApplies; }

    public AdapterType currentType() { return current.getType(); }

    public void setContext(String key, Object value) { current.setContext(key, value); }
    @SuppressWarnings("unchecked")
    public <T> T getContext(String key) { return (T) current.getContext(key); }

    public long getSwitchCount() { return switchCount; }
    public double getAvgSwitchMs() { return switchCount == 0 ? 0 : (double) totalSwitchTimeMs / switchCount; }

    /** A single LoRA weight set. */
    public static final class LoRAAdapter {
        private final AdapterType type;
        private boolean loaded;
        private final Map<String, Object> context = new HashMap<>();
        /** Elected weight-set (AdapterRegistry winner); null id = base weights. */
        String weightsId;
        Map<String, Double> weights = Map.of();

        LoRAAdapter(AdapterType type) { this.type = type; }

        void load()   { loaded = true; }   // production: mmap weights
        void unload() { loaded = false; }  // production: munmap weights

        public boolean isLoaded() { return loaded; }
        public AdapterType getType() { return type; }
        void setContext(String k, Object v) { context.put(k, v); }
        Object getContext(String k) { return context.get(k); }
    }

    /** Switch result with timing. */
    public static final class SwitchResult {
        public final boolean success;
        public final long switchTimeMs;
        public final String message;
        SwitchResult(boolean s, long ms, String m) { success = s; switchTimeMs = ms; message = m; }
        @Override public String toString() { return "Switch{" + success + ", " + switchTimeMs + "ms, " + message + "}"; }
    }
}
