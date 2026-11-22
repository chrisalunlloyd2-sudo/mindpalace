package com.mindpalace.agent.sims;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * VoteSchemaCodec — the canonical wire/schema format for WeightedQuorumVote
 * (task t_0b2c73c9: port the voting schema from repos/SIMS1337 exactly).
 *
 * SIMS1337 sources of truth (com/aigen/sims/voting/WeightedQuorumVote.java):
 *   - Vote enum:  APPROVE | REJECT | BLIND            (3 values)
 *   - Proposal:   id:String, text:String, hex:HexCoord, timeSlot:double(0..1),
 *                 votes: model → Vote (LinkedHashMap, insertion order kept)
 *   - Weights:    vote weight = 1.0 + 0.5 * (1 - 2*phaseDist), i.e. 1.0–1.5×
 *                 where phaseDist = |pulse - timeSlot| wrapped at 0.5
 *   - Quorum:     visible votes >= quorumMin (3 default) AND
 *                 weighted approve >= approveMin (2 default)
 *   - FOW:        blind > visible && total >= quorumMin → "BLINDED";
 *                 visible < quorumMin → "PENDING";
 *                 else approved iff weightedApprove >= approveMin
 *   - HexCoord:   "q,r" string form (z omitted at 0)
 *
 * MindPalace deltas (deliberate, called out per acceptance criteria — see
 * the WeightedQuorumVote header):
 *   - 4th ballot ABSTAIN (t_deed6566) — serialized as its own name,
 *     deserialized back to the same; real-vote floor REAL_VOTE_MIN=2 and
 *     NO_QUORUM replace SIMS1337's PENDING when the floor is unmet.
 *   - Blind votes NEVER count toward a verdict (realVotes = approve+reject).
 *
 * Wire format (one JSON object, field names stable, dependency-light:
 * only Gson — already shipped in the shaded jar):
 * {
 *   "proposal_id": "...",        // Proposal.id
 *   "text": "...",               // Proposal.text
 *   "hex": "q,r" [, "z"],        // HexCoord.key() — z layer only when != 0
 *   "time_slot": 0.5,            // Proposal.timeSlot
 *   "quorum_min": 3,             // engine thresholds, echoed for round-trip
 *   "approve_min": 2,
 *   "votes": {"model": "APPROVE"|"REJECT"|"BLIND"|"ABSTAIN", ...}
 * }
 * A result envelope additionally carries: status, approve, reject, blind,
 * abstain, visible, total, real, weighted_approve (double), avg_pulse (double).
 */
public final class VoteSchemaCodec {

    private VoteSchemaCodec() {}

    /** The wire schema version. Bump on breaking field changes. */
    public static final String SCHEMA_VERSION = "1.0";

    // ── Serialize ──────────────────────────────────────────────────────

    /** Serialize one Proposal's vote schema to canonical JSON. */
    public static String proposalToJson(WeightedQuorumVote.Proposal p) {
        JsonObject o = new JsonObject();
        o.addProperty("schema", SCHEMA_VERSION);
        o.addProperty("proposal_id", p.id);
        o.addProperty("text", p.text == null ? "" : p.text);
        o.addProperty("hex", p.hex == null ? "0,0" : p.hex.key());
        o.addProperty("time_slot", round(p.timeSlot));
        o.addProperty("quorum_min", 3);
        o.addProperty("approve_min", 2);
        JsonObject votes = new JsonObject();
        for (Map.Entry<String, WeightedQuorumVote.Vote> e : p.votes.entrySet())
            votes.addProperty(e.getKey(), voteName(e.getValue()));
        o.add("votes", votes);
        return o.toString();
    }

    /**
     * Serialize a full quorum verdict (QuorumResult) with the tally envelope.
     * Field names match the quorum_ledger.jsonl vocabulary already shipped by
     * QuorumTaskSpawner.ledgerRecord (votes/outcome/weight/pulse) so both
     * sinks emit one consistent dialect.
     */
    public static String resultToJson(WeightedQuorumVote.QuorumResult r) {
        JsonObject o = new JsonObject();
        o.addProperty("schema", SCHEMA_VERSION);
        o.addProperty("proposal_id", r.proposalId);
        o.addProperty("text", r.text == null ? "" : r.text);
        o.addProperty("hex", r.hexKey);
        o.addProperty("time_slot", 0.5); // QuorumResult does not carry timeSlot; canonical echo
        o.addProperty("quorum_min", 3);
        o.addProperty("approve_min", 2);
        JsonObject votes = new JsonObject();
        for (Map.Entry<String, WeightedQuorumVote.Vote> e : r.voteMap.entrySet())
            votes.addProperty(e.getKey(), voteName(e.getValue()));
        o.add("votes", votes);
        JsonObject tally = new JsonObject();
        tally.addProperty("status", r.status);
        tally.addProperty("approve", r.approve);
        tally.addProperty("reject", r.reject);
        tally.addProperty("blind", r.blind);
        tally.addProperty("abstain", r.abstain);
        tally.addProperty("visible", r.visible);
        tally.addProperty("total", r.total);
        tally.addProperty("real", r.real);
        tally.addProperty("weighted_approve", round(r.weightedApprove));
        tally.addProperty("avg_pulse", round(r.avgPulsePhase));
        o.add("tally", tally);
        return o.toString();
    }

    /** Vote enum → wire string. Never null in; ABSTAIN keeps its own name. */
    public static String voteName(WeightedQuorumVote.Vote v) {
        return v == null ? "ABSTAIN" : v.name();
    }

    private static double round(double d) { return Math.round(d * 1000.0) / 1000.0; }

    // ── Deserialize + validate ─────────────────────────────────────────

    /**
     * Parse and VALIDATE one proposal JSON into a VoteBallot.
     * Throws VoteSchemaException on any malformed input: unknown/non-"1.0"
     * schema, missing required fields, bad hex, unknown vote strings, or a
     * ballot map that is not an object. Unknown ballot names are REJECTED,
     * not coerced — the schema is the shared contract for AdapterRegistry
     * and FOWGate consumers, so leniency here would hide wire drift.
     */
    public static VoteBallot parseProposal(String json) {
        if (json == null || json.trim().isEmpty())
            throw new VoteSchemaException("empty payload");
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (RuntimeException e) {
            throw new VoteSchemaException("not JSON: " + e.getMessage());
        }
        if (!root.isJsonObject()) throw new VoteSchemaException("payload is not a JSON object");
        JsonObject o = root.getAsJsonObject();

        String schema = stringOrNull(o, "schema");
        if (schema == null || !SCHEMA_VERSION.equals(schema))
            throw new VoteSchemaException("unsupported schema: " + schema);

        String proposalId = requireString(o, "proposal_id");
        String text = stringOrNull(o, "text");
        if (text == null) text = "";
        HexCoord hex = parseHex(requireString(o, "hex"));
        double timeSlot = requireDouble(o, "time_slot");
        if (timeSlot < 0.0 || timeSlot > 1.0)
            throw new VoteSchemaException("time_slot out of range: " + timeSlot);
        int quorumMin = requireInt(o, "quorum_min");
        int approveMin = requireInt(o, "approve_min");
        if (quorumMin <= 0 || approveMin <= 0)
            throw new VoteSchemaException("non-positive threshold: q=" + quorumMin + " a=" + approveMin);

        JsonElement votesEl = o.get("votes");
        if (votesEl == null || !votesEl.isJsonObject())
            throw new VoteSchemaException("missing votes object");
        Map<String, WeightedQuorumVote.Vote> votes = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : votesEl.getAsJsonObject().entrySet()) {
            if (e.getValue() == null || !e.getValue().isJsonPrimitive())
                throw new VoteSchemaException("ballot not a string: " + e.getKey());
            WeightedQuorumVote.Vote v = parseVote(e.getValue().getAsString());
            votes.put(e.getKey(), v);
        }

        return new VoteBallot(proposalId, text, hex, timeSlot, quorumMin, approveMin,
            Collections.unmodifiableMap(votes));
    }

    /** HexCoord wire form "q,r" (SIMS1337 HexCoord.key()), optional ",z". */
    public static HexCoord parseHex(String key) {
        if (key == null) throw new VoteSchemaException("missing hex");
        String[] parts = key.split(",");
        try {
            if (parts.length == 2)
                return new HexCoord(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()));
            if (parts.length == 3) // tolerated on read (SIMS1337 has z); MP is 2D
                return new HexCoord(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()));
        } catch (NumberFormatException e) {
            throw new VoteSchemaException("bad hex: " + key);
        }
        throw new VoteSchemaException("bad hex: " + key);
    }

    /** Wire string → Vote enum. Exact name match only — no case folding. */
    public static WeightedQuorumVote.Vote parseVote(String name) {
        if (name == null) throw new VoteSchemaException("null vote");
        try {
            return WeightedQuorumVote.Vote.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new VoteSchemaException("unknown vote: " + name);
        }
    }

    private static String requireString(JsonObject o, String field) {
        JsonElement e = o.get(field);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString())
            throw new VoteSchemaException("missing field: " + field);
        return e.getAsString();
    }

    private static String stringOrNull(JsonObject o, String field) {
        JsonElement e = o.get(field);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString())
            return null;
        return e.getAsString();
    }

    private static double requireDouble(JsonObject o, String field) {
        JsonElement e = o.get(field);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber())
            throw new VoteSchemaException("missing numeric field: " + field);
        return e.getAsDouble();
    }

    private static int requireInt(JsonObject o, String field) {
        JsonElement e = o.get(field);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber())
            throw new VoteSchemaException("missing numeric field: " + field);
        return e.getAsInt();
    }

    /** Parsed, validated ballot: the typed shape consumers can trust. */
    public static final class VoteBallot {
        public final String proposalId, text;
        public final HexCoord hex;
        public final double timeSlot;
        public final int quorumMin, approveMin;
        public final Map<String, WeightedQuorumVote.Vote> votes;

        VoteBallot(String proposalId, String text, HexCoord hex, double timeSlot,
                   int quorumMin, int approveMin, Map<String, WeightedQuorumVote.Vote> votes) {
            this.proposalId = proposalId; this.text = text; this.hex = hex;
            this.timeSlot = timeSlot; this.quorumMin = quorumMin; this.approveMin = approveMin;
            this.votes = votes;
        }

        /** APPROVE + REJECT only (the "real vote" rule — never blind/abstain). */
        public int realVotes() {
            int n = 0;
            for (WeightedQuorumVote.Vote v : votes.values())
                if (v == WeightedQuorumVote.Vote.APPROVE || v == WeightedQuorumVote.Vote.REJECT) n++;
            return n;
        }

        public int approveCount() { return count(WeightedQuorumVote.Vote.APPROVE); }
        public int rejectCount()  { return count(WeightedQuorumVote.Vote.REJECT); }
        public int blindCount()   { return count(WeightedQuorumVote.Vote.BLIND); }
        public int abstainCount() { return count(WeightedQuorumVote.Vote.ABSTAIN); }

        private int count(WeightedQuorumVote.Vote target) {
            int n = 0;
            for (WeightedQuorumVote.Vote v : votes.values()) if (v == target) n++;
            return n;
        }

        /** Re-serialize — the round-trip half of the contract. */
        public String toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("schema", SCHEMA_VERSION);
            o.addProperty("proposal_id", proposalId);
            o.addProperty("text", text == null ? "" : text);
            o.addProperty("hex", hex.key());
            o.addProperty("time_slot", round(timeSlot));
            o.addProperty("quorum_min", quorumMin);
            o.addProperty("approve_min", approveMin);
            JsonObject vs = new JsonObject();
            for (Map.Entry<String, WeightedQuorumVote.Vote> e : votes.entrySet())
                vs.addProperty(e.getKey(), voteName(e.getValue()));
            o.add("votes", vs);
            return o.toString();
        }

        /**
         * Independent quorum evaluation against the parsed ballot using the
         * SIMS1337 schema rules (visible >= quorumMin, weighted >= approveMin)
         * with the MP delta (blind never real, NO_QUORUM floor REAL_VOTE_MIN).
         * Deterministic: phase distances use the ballot's stored timeSlot and
         * a caller-provided map of model → pulse phase (absent models 1.0×).
         */
        public String evaluate(Map<String, Double> pulseByModel) {
            int blind = blindCount(), total = votes.size();
            int visible = total - blind;
            if (blind > visible && total >= quorumMin) return "BLINDED";
            if (realVotes() < WeightedQuorumVote.REAL_VOTE_MIN || visible < quorumMin)
                return "NO_QUORUM";
            double w = 0;
            for (Map.Entry<String, WeightedQuorumVote.Vote> e : votes.entrySet()) {
                if (e.getValue() != WeightedQuorumVote.Vote.APPROVE) continue;
                Double phase = pulseByModel == null ? null : pulseByModel.get(e.getKey());
                if (phase == null) { w += 1.0; continue; }
                double d = Math.abs(phase - timeSlot);
                if (d > 0.5) d = 1.0 - d;
                w += 1.0 + 0.5 * (1.0 - 2.0 * d);
            }
            return w >= approveMin ? "APPROVED" : "REJECTED";
        }

        /** Vote → ledger line (VoteQualityLedger vocabulary, ASCII-safe). */
        public String toLedgerLine() { return "ballot " + proposalId + " votes:" + votes.size()
            + " real:" + realVotes() + " approve:" + approveCount() + " reject:" + rejectCount()
            + " blind:" + blindCount() + " abstain:" + abstainCount(); }
    }

    /** Malformed schema payload — always thrown, never swallowed. */
    public static final class VoteSchemaException extends RuntimeException {
        public VoteSchemaException(String msg) { super(msg); }
    }
}
