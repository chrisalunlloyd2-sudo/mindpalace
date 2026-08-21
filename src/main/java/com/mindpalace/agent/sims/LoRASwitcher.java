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

    public LoRASwitcher() {
        for (AdapterType t : AdapterType.values()) {
            adapters.put(t, new LoRAAdapter(t));
            circular.offer(t);
        }
        current = adapters.get(AdapterType.CHAT);
    }

    /** Switch to a specific adapter. Returns timing info. */
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
        return new SwitchResult(true, ms, old + " → " + type);
    }

    /** Rotate to the next adapter in the circular buffer. */
    public SwitchResult switchToNext() {
        circular.offer(circular.poll());
        return switchAdapter(circular.peek());
    }

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
