package com.mindpalace.agent.sims;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import com.mindpalace.agent.ModelScheduler;

/**
 * WeightedQuorumVote — FOW-gated quorum voting with a time pulse.
 * Ported from SIMS1337 (adapted to 2D hex; the world is a 2D room grid).
 *
 * Each model has a hex position and a pulse phase (0.0–1.0) that oscillates
 * with its cycle. Vote weight = 1.0 + resonance bonus (models in-phase with
 * the proposal's time slot get up to 1.5×). Proposals are anchored to hexes;
 * only models within FOW hop can see/vote — the rest cast BLIND.
 *
 * Quorum (t_deed6566): REAL votes only — blind/abstain never count toward
 * the verdict. A verdict needs visible ≥ quorumMin AND real votes
 * (approve+reject) ≥ REAL_VOTE_MIN, else NO_QUORUM (logged, nothing spawns).
 * A model that gives no usable answer ABSTAINS with a labeled reason
 * (abstain_timeout / abstain_empty / abstain_unparseable / abstain_error) —
 * the old Math.random() fallback is gone.
 */
public class WeightedQuorumVote {
    public enum Vote { APPROVE, REJECT, BLIND, ABSTAIN }

    /** Minimum REAL votes (approve+reject — never blind/sim/abstain) for a verdict. */
    public static final int REAL_VOTE_MIN = 2;

    /** Max wait for one model's vote before it abstains (was implicit 30s). */
    private static final long VOTE_TIMEOUT_MS = 30_000;

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
        public final Map<String, Vote> votes = new ConcurrentHashMap<>();
        public final String proposalType; // TASK_0124: proposal category (system_health_check, feature_iteration, etc)
        public final Map<String, List<String>> suggestedActions = new ConcurrentHashMap<>(); // TASK_0126: agent suggestions

        Proposal(String id, String text, HexCoord hex, double timeSlot, String proposalType) {
            this.id = id; this.text = text; this.hex = hex; this.timeSlot = timeSlot; this.proposalType = proposalType;
        }
        Proposal(String id, String text, HexCoord hex, double timeSlot) { this(id, text, hex, timeSlot, "system_health_check"); }
        Proposal(String id, String text, HexCoord hex) { this(id, text, hex, 0.5); }

        public int approveCount() { return (int) votes.values().stream().filter(v -> v == Vote.APPROVE).count(); }
        public int rejectCount()  { return (int) votes.values().stream().filter(v -> v == Vote.REJECT).count(); }
        public int blindCount()   { return (int) votes.values().stream().filter(v -> v == Vote.BLIND).count(); }
        public int abstainCount() { return (int) votes.values().stream().filter(v -> v == Vote.ABSTAIN).count(); }
        public int totalVotes()   { return votes.size(); }
        public int visibleTotal() { return totalVotes() - blindCount(); }
        /** REAL votes: approve + reject — never blind, abstain or scripted casts. */
        public int realVotes()    { return approveCount() + rejectCount(); }

        /** Model → decision outcome label (VoteQualityLedger vocabulary). */
        public final Map<String, String> voteOutcomes = new ConcurrentHashMap<>();

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
            int real = realVotes();
            if (real < REAL_VOTE_MIN || visible < quorumMin) return "NO_QUORUM";
            return weightedApprove(models) >= approveMin ? "APPROVED" : "REJECTED";
        }

        @Override public String toString() {
            // t_740fa7c6: ASCII tally. The previous glyphs (check/cross/fog)
            // rendered as "?3 ?0 ?0" through Windows cp1252 consoles and
            // telemetry writes — unreadable exactly where votes matter most.
            // t_deed6566 union: labeled abstain ballot added.
            return String.format("#%s: %s %s ok:%d no:%d blind:%d abstain:%d",
                id, text, hex, approveCount(), rejectCount(), blindCount(), abstainCount());
        }
    }

    private final Map<String, ModelPosition> models = new ConcurrentHashMap<>();
    private final Map<String, Proposal> proposals = new ConcurrentHashMap<>();
    private final int fowHop;
    private final int quorumMin;
    private final int approveMin;
    private boolean fowEnabled = true;
    /** Optional sink for every cast vote (AgentManager wires → telemetry.db). */
    private volatile Consumer<VoteQualityLedger.VoteRecord> voteSink;

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
    public Proposal registerProposal(String id, String text, HexCoord hex, String proposalType) {
        Proposal p = new Proposal(id, text, hex, 0.5, proposalType);
        proposals.put(id, p);
        return p;
    }
    public Proposal registerProposal(String id, String text, int q, int r) {
        return registerProposal(id, text, new HexCoord(q, r));
    }
    public Proposal registerProposal(String id, String text, int q, int r, String proposalType) {
        return registerProposal(id, text, new HexCoord(q, r), proposalType);
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

    /**
     * t_948a71be: post-verdict sweep — drop every registered proposal so the
     * next cycle starts clean. Without this, repeated cycles re-vote on
     * already-applied, already-ledgered items and the voter round grows
     * unboundedly (one registerProposal per backlog item per cycle).
     */
    public void finishCycle() { proposals.clear(); }

    public boolean isVisible(HexCoord target, String model) {
        if (!fowEnabled) return true;
        ModelPosition mp = models.get(model);
        if (mp == null) return true;
        return mp.hex.distanceTo(target) <= fowHop;
    }

    /** Wire the telemetry sink: each cast vote becomes a VoteQualityLedger row. */
    public void setVoteSink(Consumer<VoteQualityLedger.VoteRecord> sink) { this.voteSink = sink; }

    /** One-line vote-quality aggregate (HUD + readouts, t_deed6566). */
    public String qualitySummary() { return VoteQualityLedger.get().summaryLine(); }

    /** Cast a vote; returns BLIND if the model is outside FOW. */
    public Vote castVote(String proposalId, String modelName, Vote intent) {
        return castVote(proposalId, modelName, intent, VoteQualityLedger.SCRIPTED, 0L);
    }
    public Vote castVote(String proposalId, String modelName, boolean approve) {
        return castVote(proposalId, modelName, approve ? Vote.APPROVE : Vote.REJECT,
            VoteQualityLedger.SCRIPTED, 0L);
    }

    /**
     * Core cast path (t_deed6566): FOW gate, per-vote outcome recording into
     * the proposal's outcome map, and one VoteQualityLedger row — every vote
     * (even BLIND) is now visible and counted.
     */
    public Vote castVote(String proposalId, String modelName, Vote intent, String outcome) {
        return castVote(proposalId, modelName, intent, outcome, 0L);
    }

    public Vote castVote(String proposalId, String modelName, Vote intent, String outcome, long latencyMs) {
        Proposal p = proposals.get(proposalId);
        if (p == null) throw new IllegalArgumentException("unknown proposal: " + proposalId);
        Vote actual = isVisible(p.hex, modelName) ? intent : Vote.BLIND;
        String effectiveOutcome = actual == Vote.BLIND && !VoteQualityLedger.BLIND.equals(outcome)
            ? VoteQualityLedger.BLIND : outcome;
        p.votes.put(modelName, actual);
        p.voteOutcomes.put(modelName, effectiveOutcome);
        VoteQualityLedger.get().record(
            new VoteQualityLedger.VoteRecord(System.currentTimeMillis(),
                proposalId, modelName, actual, effectiveOutcome, latencyMs));
        Consumer<VoteQualityLedger.VoteRecord> sink = voteSink;
        if (sink != null) {
            try {
                sink.accept(new VoteQualityLedger.VoteRecord(System.currentTimeMillis(),
                    proposalId, modelName, actual, effectiveOutcome, latencyMs));
            } catch (Exception ignored) {
                // telemetry must never break voting
            }
        }
        return actual;
    }

    public QuorumResult calculateQuorum(String proposalId) {
        Proposal p = proposals.get(proposalId);
        return p == null ? null : new QuorumResult(p, models, quorumMin, approveMin);
    }

    /**
     * Auto-vote all models on all proposals — DETERMINISTIC (t_deed6566):
     * seeded from proposal text + model name, never Math.random(). Scripted,
     * FOW-gated, and every vote is recorded with outcome=sim so the quality
     * readout can separate simulated votes from real model votes.
     */
    public void autoVoteAll() {
        for (Proposal p : proposals.values())
            for (ModelPosition mp : models.values())
                if (isVisible(p.hex, mp.name)) {
                    boolean approve = Math.floorMod(
                        (p.id + "|" + mp.name).hashCode(), 10) < 7; // 70% approve, seeded
                    castVote(p.id, mp.name, approve ? Vote.APPROVE : Vote.REJECT,
                        VoteQualityLedger.SIM);
                } else {
                    castVote(p.id, mp.name, Vote.BLIND, VoteQualityLedger.BLIND);
                }
    }

    /**
     * Vote by routing proposals through the model scheduler (t_deed6566):
     * each visible proposal is sent to each model ONCE, the response is
     * normalized (think-strip + last-line token) into APPROVE/REJECT, and a
     * model that gives nothing Usable ABSTAINS (labeled) — NO random fallback.
     * Blind votes (FOW-blocked) are automatic and outcome=blind.
     */
    public void actualVoteAll(ModelScheduler scheduler) {
        for (Proposal p : proposals.values()) {
            for (ModelPosition mp : models.values()) {
                if (!isVisible(p.hex, mp.name)) {
                    castVote(p.id, mp.name, Vote.BLIND, VoteQualityLedger.BLIND);
                    continue;
                }
                long t0 = System.currentTimeMillis();
                String resp;
                try {
                    var future = scheduler.submit(mp.name, buildVotingPrompt(p, mp.name), null);
                    resp = future.get(VOTE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (java.util.concurrent.TimeoutException e) {
                    castVote(p.id, mp.name, Vote.ABSTAIN, VoteQualityLedger.ABSTAIN_TIMEOUT, VOTE_TIMEOUT_MS);
                    continue;
                } catch (Exception e) {
                    long ms = System.currentTimeMillis() - t0;
                    castVote(p.id, mp.name, Vote.ABSTAIN, VoteQualityLedger.ABSTAIN_ERROR, ms);
                    continue;
                }
                long ms = System.currentTimeMillis() - t0;
                VoteDecision d = decideVote(resp);
                castVote(p.id, mp.name, d.vote(), d.outcome(), ms);
            }
        }
    }

    private String buildVotingPrompt(Proposal p, String modelName) {
        return "You are " + modelName + " in a multi-agent governance system.\n\n" +
               "Proposal: " + p.text + "\n" +
               "Type: " + p.proposalType + "\n\n" +
               "Respond with ONLY 'APPROVE' or 'REJECT'. No explanation.";
    }

    /** A parsed vote: the ballot plus HOW it was decided (quality label). */
    public record VoteDecision(Vote vote, String outcome) {}

    // <think> tags built from char codes — keeps XML-ish literals out of source
    private static final String THINK_OPEN =
        new String(new char[] {'<', 't', 'h', 'i', 'n', 'k', '>'});
    private static final String THINK_CLOSE =
        new String(new char[] {'<', '/', 't', 'h', 'i', 'n', 'k', '>'});

    /**
     * Strip think-reasoning blocks (and stray unclosed openers / lone
     * closers) from a model response — deepseek-r1-style wrappers.
     */
    static String stripThink(String s) {
        // Remove paired think blocks first (DOTALL: bodies span lines). Quoted
        // literals joined with a lazy any — no backslash escapes in source.
        String t = java.util.regex.Pattern
            .compile(java.util.regex.Pattern.quote(THINK_OPEN) + ".*?"
                     + java.util.regex.Pattern.quote(THINK_CLOSE),
                     java.util.regex.Pattern.DOTALL)
            .matcher(s).replaceAll(" ");                         // space: don't glue tokens
        int openIdx = t.indexOf(THINK_OPEN);                 // unclosed opener: keep tail after it
        if (openIdx >= 0) t = t.substring(t.lastIndexOf(THINK_OPEN) + THINK_OPEN.length());
        t = t.replace(THINK_CLOSE, "");                      // lone closer
        return t.trim();
    }

    private static final java.util.regex.Pattern VOTE_TOKEN =
        java.util.regex.Pattern.compile("\\b(APPROVE|REJECT)\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /** First APPROVE|REJECT token on the line, or null. */
    private static String parseToken(String line) {
        if (line == null || line.isEmpty()) return null;
        var m = VOTE_TOKEN.matcher(line);
        return m.find() ? m.group(1).toUpperCase() : null;
    }

    // CR/LF via char codes — escape sequences get mangled by the tool transport
    private static final char CHAR_CR = (char) 13;
    private static final char CHAR_LF = (char) 10;

    /** Trimmed last line that isn't blank, or null. */
    private static String lastNonEmptyLine(String s) {
        if (s == null) return null;
        String[] lines = s.replace(CHAR_CR, CHAR_LF).split(String.valueOf(CHAR_LF));
        for (int i = lines.length - 1; i >= 0; i--)
            if (!lines[i].trim().isEmpty()) return lines[i].trim();
        return null;
    }

    /**
     * Normalized vote parse (t_deed6566): the think block may itself contain
     * a contradicting token ("I do not REJECT..."), so the RAW response is
     * authoritative (outcome=real); if raw yields no token, strip think and
     * re-parse (outcome=real_after_strip — the block was reasoning noise);
     * nothing parseable at all → ABSTAIN with a labeled reason.
     */
    public static VoteDecision decideVote(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return new VoteDecision(Vote.ABSTAIN, VoteQualityLedger.ABSTAIN_EMPTY);
        }
        String rawToken = parseToken(lastNonEmptyLine(raw));
        if (rawToken != null) {
            return new VoteDecision(rawToken.equals("APPROVE") ? Vote.APPROVE : Vote.REJECT,
                VoteQualityLedger.REAL);
        }
        String strippedToken = parseToken(lastNonEmptyLine(stripThink(raw)));
        if (strippedToken != null) {
            return new VoteDecision(strippedToken.equals("APPROVE") ? Vote.APPROVE : Vote.REJECT,
                VoteQualityLedger.REAL_AFTER_STRIP);
        }
        return new VoteDecision(Vote.ABSTAIN, VoteQualityLedger.ABSTAIN_UNPARSEABLE);
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
        public final String proposalId, text, hexKey, status, proposalType;
        public final int approve, reject, blind, abstain, visible, total, real;
        public final double weightedApprove, avgPulsePhase;
        public final List<String> visibleModels, blindModels;
        public final Map<String, List<String>> suggestedActions;
        /** Model → decision outcome label (VoteQualityLedger vocabulary). */
        public final Map<String, String> voteOutcomes;

        QuorumResult(Proposal p, Map<String, ModelPosition> models, int qMin, int aMin) {
            proposalId = p.id; text = p.text; hexKey = p.hex.key(); proposalType = p.proposalType;
            // CARD-Q1 F10: COPY the proposal's live map — aliasing it let the
            // spawner mutate the proposal after tallying.
            suggestedActions = new ConcurrentHashMap<>(p.suggestedActions);
            approve = p.approveCount(); reject = p.rejectCount(); blind = p.blindCount();
            abstain = p.abstainCount(); real = p.realVotes();
            total = p.totalVotes(); visible = p.visibleTotal();
            weightedApprove = p.weightedApprove(models);
            status = p.status(models, qMin, aMin);
            voteOutcomes = Collections.unmodifiableMap(new LinkedHashMap<>(p.voteOutcomes));

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
            String typeAndText = proposalType + (text != null && !text.isEmpty() ? " | " + text : "");
            // t_740fa7c6: ASCII-only — check/cross/fog glyphs mangle via cp1252.
            // t_deed6566 union: abstain ballot labeled in the tally; real votes decide.
            return String.format("Quorum[#%s: %s] %s ok:%d no:%d blind:%d abstain:%d real:%d (w:%.2f) pulse:%.2f visible:%s",
                proposalId, typeAndText, status, approve, reject, blind, abstain, real, weightedApprove, avgPulsePhase, visibleModels);
        }
    }

    @Override public String toString() {
        StringBuilder sb = new StringBuilder("WeightedQuorumVote: " + proposals.size() + " proposals, " + models.size() + " models\n");
        for (Proposal p : proposals.values()) sb.append("  ").append(p).append("\n");
        return sb.toString();
    }
}
