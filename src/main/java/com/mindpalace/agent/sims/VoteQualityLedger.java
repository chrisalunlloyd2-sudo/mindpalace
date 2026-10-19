package com.mindpalace.agent.sims;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * VoteQualityLedger — in-memory quality stats for every quorum vote cast
 * (t_deed6566). Records how each vote was DECIDED, so the "local models were
 * randomly approving" defect becomes visible on a live readout instead of
 * hiding inside Math.random().
 *
 * Outcome vocabulary:
 *   real             — parsed from the model's raw response, verbatim
 *   real_after_strip — parsed only after stripping think-reasoning tags
 *   abstain_*        — the model gave nothing usable and DID NOT vote
 *                      (timeout / empty / unparseable / infrastructure error)
 *   blind            — vote suppressed by Fog-of-War (model can't see the hex)
 *   sim              — deterministic scripted vote (autoVoteAll / selftest)
 *   scripted         — cast via the public API with an explicit vote
 *
 * The durable store for per-vote rows is telemetry.db (quorum_vote events,
 * wired by AgentManager's vote sink) — this ledger is the readout cache:
 * HUD billboard + per-cycle CLI lines. quorum_ledger.jsonl stays SPAWN-only.
 */
public final class VoteQualityLedger {

    public static final String REAL             = "real";
    public static final String REAL_AFTER_STRIP = "real_after_strip";
    public static final String ABSTAIN_TIMEOUT  = "abstain_timeout";
    public static final String ABSTAIN_EMPTY    = "abstain_empty";
    public static final String ABSTAIN_ERROR    = "abstain_error";
    public static final String ABSTAIN_UNPARSEABLE = "abstain_unparseable";
    public static final String BLIND      = "blind";
    public static final String SIM        = "sim";
    public static final String SCRIPTED   = "scripted";
    /** T2: mesh (tailnet device) ballot — signed, arrived over the mesh. */
    public static final String MESH       = "mesh";

    /** True when an outcome string means "the model did not cast a real vote". */
    public static boolean isAbstain(String outcome) {
        return outcome != null && outcome.startsWith("abstain");
    }

    /** One vote — the row the telemetry sink receives. */
    public static final class VoteRecord {
        public final long ts;
        public final String proposalId, model, outcome;
        public final WeightedQuorumVote.Vote vote;
        public final long latencyMs;

        VoteRecord(long ts, String proposalId, String model,
                   WeightedQuorumVote.Vote vote, String outcome, long latencyMs) {
            this.ts = ts; this.proposalId = proposalId; this.model = model;
            this.vote = vote; this.outcome = outcome; this.latencyMs = latencyMs;
        }

        /** Compact JSON detail for a telemetry row (ASCII only — log-safe). */
        public String toDetail() {
            return new StringBuilder(128)
                .append("{\"proposal\":\"").append(proposalId).append('"')
                .append(",\"model\":\"").append(model).append('"')
                .append(",\"vote\":\"").append(vote == null ? "NONE" : vote.name()).append('"')
                .append(",\"outcome\":\"").append(outcome).append('"')
                .append(",\"latency_ms\":").append(latencyMs)
                .append(",\"ts\":").append(ts).append('}')
                .toString();
        }
    }

    /** Per-model running counts. */
    public static final class ModelStats {
        public final String model;
        final AtomicLong real = new AtomicLong();
        final AtomicLong realAfterStrip = new AtomicLong();
        final AtomicLong abstain = new AtomicLong();
        final AtomicLong blind = new AtomicLong();
        final AtomicLong sim = new AtomicLong();

        ModelStats(String model) { this.model = model; }

        void add(String outcome) {
            switch (outcome) {
                case REAL             -> real.incrementAndGet();
                case REAL_AFTER_STRIP -> realAfterStrip.incrementAndGet();
                case BLIND            -> blind.incrementAndGet();
                case SIM, SCRIPTED    -> sim.incrementAndGet();
                default -> { if (isAbstain(outcome)) abstain.incrementAndGet(); }
            }
        }

        public long realCount()       { return real.get(); }
        public long realAfterStripCount() { return realAfterStrip.get(); }
        public long abstainCount()    { return abstain.get(); }
        public long blindCount()      { return blind.get(); }
        public long simCount()        { return sim.get(); }
        public long total() {
            return real.get() + realAfterStrip.get() + abstain.get() + blind.get() + sim.get();
        }

        /** Compact one-model line: "qwen2.5:0.5b(real:2+1strip abstain:4)" */
        @Override public String toString() {
            return model + "(real:" + real.get()
                + (realAfterStrip.get() > 0 ? "+" + realAfterStrip.get() + "strip" : "")
                + " abstain:" + abstain.get() + ")";
        }
    }

    private static final VoteQualityLedger INSTANCE = new VoteQualityLedger();

    /** Global singleton — vote quality is process-wide (all quorum instances). */
    public static VoteQualityLedger get() { return INSTANCE; }

    private final Map<String, ModelStats> byModel = new ConcurrentHashMap<>();
    private final AtomicLong total = new AtomicLong();

    private VoteQualityLedger() {}

    /** Record one vote (called by WeightedQuorumVote's cast path). */
    void record(VoteRecord rec) {
        total.incrementAndGet();
        byModel.computeIfAbsent(rec.model, ModelStats::new).add(rec.outcome);
    }

    public ModelStats stats(String model) { return byModel.get(model); }
    public java.util.Collection<ModelStats> allModelStats() { return byModel.values(); }
    public long totalRecords() { return total.get(); }

    /**
     * Quality summary of ONE proposal's votes (model → outcome map, carried
     * on QuorumResult.voteOutcomes). Example: "real=2(+1 after strip) abstain=1".
     */
    public static String summarize(Map<String, String> outcomes) {
        long real = 0, strip = 0, abstain = 0, other = 0;
        if (outcomes != null) {
            for (String o : outcomes.values()) {
                if (o == null) { other++; continue; }
                switch (o) {
                    case REAL             -> real++;
                    case REAL_AFTER_STRIP -> strip++;
                    case SIM, SCRIPTED    -> other++;
                    case BLIND            -> other++;
                    default -> { if (isAbstain(o)) abstain++; else other++; }
                }
            }
        }
        return (real + strip) == 0 && abstain == 0 && other == 0 ? "no votes"
            : "real=" + (real + strip) + (strip > 0 ? "(+" + strip + " after strip)" : "")
              + " abstain=" + abstain + (other > 0 ? " scripted/blind=" + other : "");
    }

    /** One-line aggregate readout (HUD + per-cycle): "votes:9 real:6+1 abstain:2 blind:0 sim:0". */
    public String summaryLine() {
        long real = 0, strip = 0, abstain = 0, blind = 0, sim = 0;
        for (ModelStats s : byModel.values()) {
            real += s.real.get(); strip += s.realAfterStrip.get();
            abstain += s.abstain.get(); blind += s.blind.get(); sim += s.sim.get();
        }
        return "votes:" + total.get()
            + " real:" + real + (strip > 0 ? "+" + strip + "strip" : "")
            + " abstain:" + abstain + " blind:" + blind + " sim:" + sim;
    }

    /** Per-model breakdown, one line each (for logs / selftest detail). */
    public String perModelLines() {
        StringBuilder sb = new StringBuilder();
        for (ModelStats s : byModel.values()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(s);
        }
        return sb.length() == 0 ? "(none)" : sb.toString();
    }
}