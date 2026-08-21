package com.mindpalace.agent.sims;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * WeightedQuorumVote — FOW-gated quorum voting with a time pulse.
 * Ported from SIMS1337 (adapted to 2D hex; the world is a 2D room grid).
 *
 * Each model has a hex position and a pulse phase (0.0–1.0) that oscillates
 * with its cycle. Vote weight = 1.0 + resonance bonus (models in-phase with
 * the proposal's time slot get up to 1.5×). Proposals are anchored to hexes;
 * only models within FOW hop can see/vote — the rest cast BLIND.
 *
 * Quorum: visible votes ≥ quorumMin AND weighted approve ≥ approveMin.
 */
public class WeightedQuorumVote {
    public enum Vote { APPROVE, REJECT, BLIND }

    public static final class ModelPosition {
        public final String name;
        public final HexCoord hex;
        public volatile double pulsePhase; // 0.0–1.0, oscillates

        ModelPosition(String name, HexCoord hex) { this.name = name; this.hex = hex; this.pulsePhase = 0.0; }
    }

    public static final class Proposal {
        public final String id;
        public final String text;
        public final HexCoord hex;
        public final double timeSlot; // preferred pulse phase 0.0–1.0
        public final Map<String, Vote> votes = new LinkedHashMap<>();

        Proposal(String id, String text, HexCoord hex, double timeSlot) {
            this.id = id; this.text = text; this.hex = hex; this.timeSlot = timeSlot;
        }
        Proposal(String id, String text, HexCoord hex) { this(id, text, hex, 0.5); }

        public int approveCount() { return (int) votes.values().stream().filter(v -> v == Vote.APPROVE).count(); }
        public int rejectCount()  { return (int) votes.values().stream().filter(v -> v == Vote.REJECT).count(); }
        public int blindCount()   { return (int) votes.values().stream().filter(v -> v == Vote.BLIND).count(); }
        public int totalVotes()   { return votes.size(); }
        public int visibleTotal() { return totalVotes() - blindCount(); }

        /** Weighted approve: each APPROVE × its model's resonance weight (1.0–1.5). */
        public double weightedApprove(Map<String, ModelPosition> models) {
            double w = 0;
            for (var e : votes.entrySet()) {
                if (e.getValue() != Vote.APPROVE) continue;
                ModelPosition mp = models.get(e.getKey());
                if (mp == null) { w += 1.0; continue; }
                double d = Math.abs(mp.pulsePhase - timeSlot);
                if (d > 0.5) d = 1.0 - d;
                w += 1.0 + 0.5 * (1.0 - 2.0 * d);
            }
            return w;
        }

        public String status(Map<String, ModelPosition> models, int quorumMin, int approveMin) {
            int blind = blindCount(), visible = visibleTotal();
            if (blind > visible && totalVotes() >= quorumMin) return "BLINDED";
            if (visible < quorumMin) return "PENDING";
            return weightedApprove(models) >= approveMin ? "APPROVED" : "REJECTED";
        }

        @Override public String toString() {
            return String.format("#%s: %s %s ✓%d ✗%d 🌫%d", id, text, hex, approveCount(), rejectCount(), blindCount());
        }
    }

    private final Map<String, ModelPosition> models = new LinkedHashMap<>();
    private final Map<String, Proposal> proposals = new LinkedHashMap<>();
    private final int fowHop;
    private final int quorumMin;
    private final int approveMin;
    private boolean fowEnabled = true;

    public WeightedQuorumVote() { this(1, 3, 2); }
    public WeightedQuorumVote(int fowHop, int quorumMin, int approveMin) {
        this.fowHop = fowHop; this.quorumMin = quorumMin; this.approveMin = approveMin;
    }

    public void setModelPosition(String name, int q, int r) {
        models.put(name, new ModelPosition(name, new HexCoord(q, r)));
    }

    public Proposal registerProposal(String id, String text, HexCoord hex) {
        Proposal p = new Proposal(id, text, hex);
        proposals.put(id, p);
        return p;
    }
    public Proposal registerProposal(String id, String text, int q, int r) {
        return registerProposal(id, text, new HexCoord(q, r));
    }

    /** Advance the time pulse: oscillate every model's phase by delta. */
    public void advanceTimePulse(double delta) {
        for (ModelPosition mp : models.values()) {
            mp.pulsePhase = (mp.pulsePhase + delta) % 1.0;
            if (mp.pulsePhase < 0) mp.pulsePhase += 1.0;
        }
    }

    public void setPulsePhase(String model, double phase) {
        ModelPosition mp = models.get(model);
        if (mp != null) mp.pulsePhase = ((phase % 1.0) + 1.0) % 1.0;
    }

    public boolean isVisible(HexCoord target, String model) {
        if (!fowEnabled) return true;
        ModelPosition mp = models.get(model);
        if (mp == null) return true;
        return mp.hex.distanceTo(target) <= fowHop;
    }

    /** Cast a vote; returns BLIND if the model is outside FOW. */
    public Vote castVote(String proposalId, String modelName, Vote intent) {
        Proposal p = proposals.get(proposalId);
        if (p == null) throw new IllegalArgumentException("unknown proposal: " + proposalId);
        Vote actual = isVisible(p.hex, modelName) ? intent : Vote.BLIND;
        p.votes.put(modelName, actual);
        return actual;
    }
    public Vote castVote(String proposalId, String modelName, boolean approve) {
        return castVote(proposalId, modelName, approve ? Vote.APPROVE : Vote.REJECT);
    }

    public QuorumResult calculateQuorum(String proposalId) {
        Proposal p = proposals.get(proposalId);
        return p == null ? null : new QuorumResult(p, models, quorumMin, approveMin);
    }

    /** Auto-vote all models on all proposals (FOW-gated, 70% approve default). */
    public void autoVoteAll() {
        for (Proposal p : proposals.values())
            for (ModelPosition mp : models.values())
                castVote(p.id, mp.name, isVisible(p.hex, mp.name)
                    ? (Math.random() > 0.3 ? Vote.APPROVE : Vote.REJECT)
                    : Vote.BLIND);
    }

    public Proposal getProposal(String id) { return proposals.get(id); }
    public ModelPosition getModel(String name) { return models.get(name); }
    public Collection<Proposal> allProposals() { return proposals.values(); }
    public Collection<ModelPosition> allModels() { return models.values(); }
    public Map<String, ModelPosition> allModelsMap() { return Collections.unmodifiableMap(models); }
    public int proposalCount() { return proposals.size(); }
    public int modelCount() { return models.size(); }
    public int getFowHop() { return fowHop; }
    public void setFowEnabled(boolean e) { fowEnabled = e; }
    public boolean isFowEnabled() { return fowEnabled; }

    public static final class QuorumResult {
        public final String proposalId, text, hexKey, status;
        public final int approve, reject, blind, visible, total;
        public final double weightedApprove, avgPulsePhase;
        public final List<String> visibleModels, blindModels;

        QuorumResult(Proposal p, Map<String, ModelPosition> models, int qMin, int aMin) {
            proposalId = p.id; text = p.text; hexKey = p.hex.key();
            approve = p.approveCount(); reject = p.rejectCount(); blind = p.blindCount();
            total = p.totalVotes(); visible = p.visibleTotal();
            weightedApprove = p.weightedApprove(models);
            status = p.status(models, qMin, aMin);

            visibleModels = new ArrayList<>(); blindModels = new ArrayList<>();
            double pulseSum = 0; int pulseCount = 0;
            for (var e : p.votes.entrySet()) {
                ModelPosition mp = models.get(e.getKey());
                if (e.getValue() == Vote.BLIND) blindModels.add(e.getKey());
                else { visibleModels.add(e.getKey()); if (mp != null) { pulseSum += mp.pulsePhase; pulseCount++; } }
            }
            avgPulsePhase = pulseCount > 0 ? pulseSum / pulseCount : 0;
        }

        @Override public String toString() {
            return String.format("Quorum[#%s: %s] %s ✓%d ✗%d 🌫%d (w:%.2f) pulse:%.2f visible:%s",
                proposalId, text, status, approve, reject, blind, weightedApprove, avgPulsePhase, visibleModels);
        }
    }

    @Override public String toString() {
        StringBuilder sb = new StringBuilder("WeightedQuorumVote: " + proposals.size() + " proposals, " + models.size() + " models\n");
        for (Proposal p : proposals.values()) sb.append("  ").append(p).append("\n");
        return sb.toString();
    }
}
