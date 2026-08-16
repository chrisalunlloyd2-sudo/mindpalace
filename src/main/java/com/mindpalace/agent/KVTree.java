package com.mindpalace.agent;

import java.util.*;

/**
 * Key-Value tree — an agent's "brain" state. Determines what it notices,
 * where it walks, what it modifies, and how it talks.
 *
 * Deterministic: seeded from the agent's role so the same role always
 * behaves the same way. Values are floats in [0,1] unless noted.
 */
public class KVTree {
    private final Map<String, Float> values = new HashMap<>();
    private final Random rand;

    public KVTree(String role, long seed) {
        this.rand = new Random(seed);
        // Base personality traits, seeded deterministically per role
        set("curiosity", 0.5f + rand.nextFloat() * 0.5f);   // what it notices
        set("riskTolerance", rand.nextFloat());              // what it modifies
        set("verbosity", 0.3f + rand.nextFloat() * 0.7f);    // how it talks
        set("wanderlust", 0.4f + rand.nextFloat() * 0.6f);   // where it walks
        set("gossip", rand.nextFloat());                     // gossip frequency
        set("focus", 0.3f + rand.nextFloat() * 0.7f);        // stay vs move on
    }

    public void set(String key, float v) { values.put(key, v); }
    public float get(String key) { return values.getOrDefault(key, 0.5f); }
    public float get(String key, float def) { return values.getOrDefault(key, def); }

    /** Weighted coin flip driven by a KV value. */
    public boolean roll(String key) { return rand.nextFloat() < get(key); }

    /** Pick a random index weighted by a KV value (higher = more likely to act). */
    public boolean shouldAct(String key) { return rand.nextFloat() < get(key); }

    public Map<String, Float> snapshot() { return new HashMap<>(values); }
}
