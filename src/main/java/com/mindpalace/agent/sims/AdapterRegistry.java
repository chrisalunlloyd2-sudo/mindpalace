package com.mindpalace.agent.sims;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AdapterRegistry (issue #71) — the last SIMS1337-parity gap.
 * LoRA adapter weights are proposed and voted on by the agent population;
 * the best-scoring weight-set per AdapterType wins and becomes the default
 * the {@link LoRASwitcher} loads on next switch.
 *
 * Reuses the community's own weighting mechanics conceptually: each ballot is
 * a (voter, weight) vote recorded with a reason, and an election is a
 * deterministic weighted tally — no randomness, so a fixed ballot set always
 * elects the same winner (selftest-assertable).
 *
 * ADD-ONLY: nothing existing is modified; wiring into AgentManager stays a
 * follow-up (registry is standalone + selftest-checked today).
 */
public class AdapterRegistry {

    /** A voted weight-set for one adapter type. */
    public static final class AdapterWeights {
        public final AdapterType type;
        /** Per-reason weights, e.g. "latency": 0.8, "quality": 1.2 */
        public final Map<String, Double> weights;
        /** Sum of weighted approvals received from voters. */
        double score;

        AdapterWeights(AdapterType type, Map<String, Double> weights) {
            this.type = type;
            this.weights = weights;
        }
    }

    /** One voter's ballot on a candidate weight-set. */
    public record Ballot(String voter, AdapterType type, String candidateId,
                         boolean approve, double voterWeight, String reason) {}

    private final Map<String, AdapterWeights> candidates = new LinkedHashMap<>();
    private final List<Ballot> ballots = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Map<AdapterType, String> elected = new LinkedHashMap<>();

    /**
     * Register (or overwrite) a candidate weight-set for a type. Candidate id
     * is caller-chosen (e.g. "latency-lean-1"); null/blank ids get an
     * auto-generated stable fallback so a null id can't NPE later tallies.
     */
    public synchronized String registerCandidate(AdapterType type, String id, Map<String, Double> weights) {
        if (type == null) throw new IllegalArgumentException("type");
        if (id == null || id.trim().isEmpty()) {
            id = "adapter-auto-" + System.nanoTime();
        }
        if (weights == null) weights = Map.of();
        AdapterWeights aw = new AdapterWeights(type, new LinkedHashMap<>(weights));
        candidates.put(type.name() + "/" + id, aw);
        return id;
    }

    /** Record one ballot. Unknown votes (null intent) count as BLANK — ignored. */
    public synchronized void castBallot(Ballot ballot) {
        if (ballot == null || ballot.candidateId() == null
                || !candidates.containsKey(ballot.type().name() + "/" + ballot.candidateId())) {
            return; // degrade, don't crash — matches package-wide null-fence style
        }
        ballots.add(ballot);
    }

    /**
     * Deterministic election per type: weighted approve − weighted reject.
     * Ties break on insertion order (LinkedHashMap), never randomness.
     * Returns the winning candidate id, or null if no candidates exist.
     */
    public synchronized String electBest(AdapterType type) {
        // reset scores each election so repeated calls are idempotent
        Map<String, Double> tally = new LinkedHashMap<>();
        for (Map.Entry<String, AdapterWeights> e : candidates.entrySet()) {
            if (e.getValue().type == type) tally.put(e.getKey(), 0.0);
        }
        if (tally.isEmpty()) { elected.remove(type); return null; }

        for (Ballot b : ballots) {
            String key = b.type().name() + "/" + b.candidateId();
            if (!tally.containsKey(key)) continue;
            double w = Math.max(0.0, b.voterWeight()); // negative weights fenced to 0
            tally.put(key, tally.get(key) + (b.approve() ? w : -w));
        }

        String best = null; double bestScore = Double.NEGATIVE_INFINITY;
        for (Map.Entry<String, Double> e : tally.entrySet()) {
            if (e.getValue() > bestScore) { bestScore = e.getValue(); best = e.getKey(); }
        }
        candidates.get(best).score = bestScore;
        elected.put(type, best);
        return best.substring(best.indexOf('/') + 1);
    }

    /** The currently elected candidate id for a type (post-{@link #electBest}). */
    public synchronized String elected(AdapterType type) {
        String k = elected.get(type);
        return k == null ? null : k.substring(k.indexOf('/') + 1);
    }

    /** The winning weight-set to hand to LoRASwitcher switching logic. */
    public synchronized Map<String, Double> electedWeights(AdapterType type) {
        String k = elected.get(type);
        return k == null ? Map.of() : java.util.Collections.unmodifiableMap(candidates.get(k).weights);
    }
}