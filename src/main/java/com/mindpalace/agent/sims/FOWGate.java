package com.mindpalace.agent.sims;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FOWGate — fog-of-war visibility gate. Ported from SIMS1337 (no slf4j).
 * Determines whether a model (via its assigned agent) can see a given hex.
 * This is the "organized pattern" the SLMs follow: each agent is pinned to a
 * hex, each model is assigned to an agent, and visibility is hop-limited.
 */
public class FOWGate {
    public static final int DEFAULT_HOP = 1;

    private final Map<String, String> agentHex = new ConcurrentHashMap<>();   // agent → "q,r"
    private final Map<String, String> modelAgent = new ConcurrentHashMap<>(); // model → agent
    private final int hopLimit;
    private boolean enabled = true;

    public FOWGate() { this(DEFAULT_HOP); }
    public FOWGate(int hopLimit) { this.hopLimit = hopLimit; }

    /** Pin an agent to a hex coordinate. */
    public void pinAgent(String agent, HexCoord hex) { agentHex.put(agent, hex.key()); }

    /** Assign a model to an agent for FOV purposes. */
    public void assignModel(String model, String agent) { modelAgent.put(model, agent); }

    /** Is a hex visible to a model? Unassigned models see everything. */
    public boolean isVisible(HexCoord target, String model) {
        if (!enabled) return true;
        String agent = modelAgent.get(model);
        if (agent == null) return true;
        String key = agentHex.get(agent);
        if (key == null) return true;
        return HexCoord.fromString(key).distanceTo(target) <= hopLimit;
    }

    /** Slice a global coord to the model's visible neighborhood (null if hidden). */
    public HexCoord sliceLocal(HexCoord global, String model) {
        return isVisible(global, model) ? global : null;
    }

    public void setEnabled(boolean e) { enabled = e; }
    public boolean isEnabled() { return enabled; }
    public int getHopLimit() { return hopLimit; }
    public int agentCount() { return agentHex.size(); }
    public int modelCount() { return modelAgent.size(); }
    public Set<String> modelNames() { return modelAgent.keySet(); }
    public String agentFor(String model) { return modelAgent.get(model); }
    public HexCoord hexFor(String agent) {
        String key = agentHex.get(agent);
        return key != null ? HexCoord.fromString(key) : null;
    }
}
