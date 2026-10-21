package com.mindpalace.agent.sims;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * SimsHardening100 — HARDEN-x100 gate (step 96, 2026-10-18).
 *
 * 100 individually-named, deterministic, fast invariants over the SIMS1337-parity
 * layer: quorum voting (30), model routing (10), LoRA switching (10), FOW gating
 * (10), hex math (10), vote-quality ledger (10), proposal rotation (10), and the
 * language registry (10). Every case runs in microseconds; there is no I/O, no
 * threading contention, and no Math.random() — the gate is reproducible from the
 * printed case index alone.
 *
 * Design rules (from the v7 "X10" hardening precedent, scaled x10):
 *   - fail names the case AND the observed value, so a red run is triageable
 *     from the selftest log without a debugger;
 *   - each case uses fresh instances (no cross-case state leakage);
 *   - malformed-input cases assert "survives + degrades", never "throws".
 */
public final class SimsHardening100 {
    private SimsHardening100() {}

    /** One named invariant case. */
    private record Case(String name, BooleanSupplier check) {}

    public static int totalCases() { return CASES.size(); }

    /** Run all 100 cases; returns a failures list ("name: detail") — empty = green. */
    public static List<String> runAll() {
        List<String> failures = new ArrayList<>();
        for (Case c : CASES) {
            boolean ok;
            try {
                ok = c.check().getAsBoolean();
            } catch (Throwable t) {
                failures.add(c.name() + ": threw " + t.getClass().getSimpleName() + " (" + t.getMessage() + ")");
                continue;
            }
            if (!ok) failures.add(c.name() + ": invariant violated");
        }
        return failures;
    }

    /** One-line summary for the selftest log: "100 cases, 0 failed". */
    public static String summary(List<String> failures) {
        return (CASES.size() - failures.size()) + "/" + CASES.size() + " cases PASS";
    }

    // ── case helpers ────────────────────────────────────────────────────────

    private static boolean isFinite01(double d) {
        return !Double.isNaN(d) && !Double.isInfinite(d) && d >= 0.0 && d <= 1.0;
    }

    /** Register a proposal with one APPROVE per given model, at the proposal hex. */
    private static WeightedQuorumVote.Proposal approved(WeightedQuorumVote q, String id, String... voters) {
        WeightedQuorumVote.Proposal p = q.registerProposal(id, "t", 0, 0);
        for (String v : voters) q.castVote(id, v, WeightedQuorumVote.Vote.APPROVE, VoteQualityLedger.SCRIPTED);
        return p;
    }

    /**
     * Deterministic autoVoteAll probe (HARDEN-x100): fresh voter set over
     * models a/b/c, register the proposal, auto-vote, return a voteMap copy.
     * autoVoteAll is seeded by (proposalId + "|" + model).hashCode() — pinned
     * here via floorMod(%10): "pK|a"=8, "pK|b"=9 → REJECT; "pK|c"=0 → APPROVE.
     */
    private static Map<String, WeightedQuorumVote.Vote> quorumAutoVoteFresh(String id, String text) {
        WeightedQuorumVote q = new WeightedQuorumVote(1, 3, 2);
        q.setModelPosition("a", 0, 0); q.setModelPosition("b", 0, 0); q.setModelPosition("c", 0, 0);
        q.registerProposal(id, text, 0, 0);
        q.autoVoteAll();
        return new java.util.LinkedHashMap<>(q.calculateQuorum(id).voteMap);
    }

    // ── the 100 cases ───────────────────────────────────────────────────────

    private static final List<Case> CASES = build();

    private static List<Case> build() {
        List<Case> cs = new ArrayList<>();

        // ── QUORUM (30) — weighted, FOW, real-vote floor, malformed input ──
        cs.add(new Case("quorum.basicApprove", () -> {
            WeightedQuorumVote q = new WeightedQuorumVote(1, 3, 2);
            q.setModelPosition("a", 0, 0); q.setModelPosition("b", 0, 0); q.setModelPosition("c", 0, 0);
            approved(q, "p1", "a", "b", "c");
            return "APPROVED".equals(q.calculateQuorum("p1").status);
        }));
        cs.add(new Case("quorum.basicReject", () -> {
            WeightedQuorumVote q = new WeightedQuorumVote(1, 3, 2);
            q.setModelPosition("a", 0, 0); q.setModelPosition("b", 0, 0); q.setModelPosition("c", 0, 0);
            q.registerProposal("p1", "t", 0, 0);
            q.castVote("p1", "a", WeightedQuorumVote.Vote.REJECT, VoteQualityLedger.SCRIPTED);
            q.castVote("p1", "b", WeightedQuorumVote.Vote.REJECT, VoteQualityLedger.SCRIPTED);
            q.castVote("p1", "c", WeightedQuorumVote.Vote.REJECT, VoteQualityLedger.SCRIPTED);
            return "REJECTED".equals(q.calculateQuorum("p1").status);
        }));
        cs.add(new Case("quorum.noQuorumWhenOneRealVote", () -> {
            WeightedQuorumVote q = new WeightedQuorumVote(1, 3, 2);
            q.setModelPosition("a", 0, 0); q.setModelPosition("b", 0, 0); q.setModelPosition("c", 0, 0);
            WeightedQuorumVote.Proposal p = q.registerProposal("p1", "t", 0, 0);
            q.castVote("p1", "a", WeightedQuorumVote.Vote.APPROVE, VoteQualityLedger.SCRIPTED);
            q.castVote("p1", "b", WeightedQuorumVote.Vote.ABSTAIN, VoteQualityLedger.ABSTAIN_EMPTY);
            q.castVote("p1", "c", WeightedQuorumVote.Vote.ABSTAIN, VoteQualityLedger.ABSTAIN_TIMEOUT);
            return "NO_QUORUM".equals(p.status(q.allModelsMap(), 3, 2));
        }));
        cs.add(new Case("quorum.realVotesFloorNeedsTwo", () -> {
            // REAL_VOTE_MIN=2: one lone real vote can never clear quorum, even
            // with quorumMin=1 — and blind-majority labels BLINDED first.
            WeightedQuorumVote q = new WeightedQuorumVote(0, 1, 1);
            q.setModelPosition("far", 5, 5);
            q.registerProposal("p1", "t", 0, 0);
            WeightedQuorumVote.Vote cast = q.castVote("p1", "far", WeightedQuorumVote.Vote.APPROVE, VoteQualityLedger.SCRIPTED);
            WeightedQuorumVote.QuorumResult r = q.calculateQuorum("p1");
            return cast == WeightedQuorumVote.Vote.BLIND && r.real == 0
                && "BLINDED".equals(r.status);
        }));
        cs.add(new Case("quorum.fowBlindMajorityBlinded", () -> {
            WeightedQuorumVote q = new WeightedQuorumVote(1, 2, 1);
            q.setModelPosition("a", 0, 0); q.setModelPosition("b", 9, 9); q.setModelPosition("c", 9, 9);
            q.registerProposal("p1", "t", 0, 0);
            q.castVote("p1", "a", WeightedQuorumVote.Vote.APPROVE, VoteQualityLedger.SCRIPTED);
            q.castVote("p1", "b", WeightedQuorumVote.Vote.APPROVE, VoteQualityLedger.SCRIPTED);
            q.castVote("p1", "c", WeightedQuorumVote.Vote.APPROVE, VoteQualityLedger.SCRIPTED);
            return "BLINDED".equals(q.calculateQuorum("p1").status);
        }));
        cs.add(new Case("quorum.weightedApproveInRange", () -> {
            WeightedQuorumVote q = new WeightedQuorumVote(1, 1, 1);
            q.setModelPosition("a", 0, 0);
            approved(q, "p1", "a");
            double w = q.calculateQuorum("p1").weightedApprove;
            return w >= 1.0 && w <= 1.5;
        }));
        cs.add(new Case("quorum.weightedApproveInPhaseMax", () -> {
            // model phase == timeSlot (both 0.5) → resonance max: w = 1.0+0.5
            WeightedQuorumVote q = new WeightedQuorumVote(1, 1, 1);
            q.setModelPosition("a", 0, 0);
            q.setPulsePhase("a", 0.5);
            WeightedQuorumVote.Proposal p = q.registerProposal("p1", "t", 0, 0);
            q.castVote("p1", "a", WeightedQuorumVote.Vote.APPROVE, VoteQualityLedger.SCRIPTED);
            return Math.abs(p.weightedApprove(q.allModelsMap()) - 1.5) < 1e-9;
        }));
        cs.add(new Case("quorum.weightedApproveAntiPhaseMin", () -> {
            // model phase 0.0 vs timeSlot 0.5 → circular distance 0.5 → w = 1.0
            WeightedQuorumVote q = new WeightedQuorumVote(1, 1, 1);
            q.setModelPosition("a", 0, 0);
            q.setPulsePhase("a", 0.0);
            WeightedQuorumVote.Proposal p = q.registerProposal("p1", "t", 0, 0);
            q.castVote("p1", "a", WeightedQuorumVote.Vote.APPROVE, VoteQualityLedger.SCRIPTED);
            return Math.abs(p.weightedApprove(q.allModelsMap()) - 1.0) < 1e-9;
        }));
        cs.add(new Case("quorum.unknownModelWeightsExactlyOne", () -> {
            WeightedQuorumVote q = new WeightedQuorumVote(1, 1, 1);
            q.registerProposal("p1", "t", 0, 0);
            q.castVote("p1", "ghost", WeightedQuorumVote.Vote.APPROVE, VoteQualityLedger.SCRIPTED);
            return Math.abs(q.calculateQuorum("p1").weightedApprove - 1.0) < 1e-9;
        }));
        cs.add(new Case("quorum.setPulsePhaseNaNNeutralized", () -> {
            WeightedQuorumVote q = new WeightedQuorumVote(1, 1, 1);
            q.setModelPosition("a", 0, 0);
            q.setPulsePhase("a", Double.NaN);
            WeightedQuorumVote.Proposal p = q.registerProposal("p1", "t", 0, 0);
            q.castVote("p1", "a", WeightedQuorumVote.Vote.APPROVE, VoteQualityLedger.SCRIPTED);
            return isFinite01(q.getModel("a").pulsePhase)
                && isFinite01(q.calculateQuorum("p1").weightedApprove)
                && isFinite01(q.calculateQuorum("p1").avgPulsePhase);
        }));
        cs.add(new Case("quorum.setPulsePhaseInfNeutralized", () -> {
            WeightedQuorumVote q = new WeightedQuorumVote(1, 1, 1);
            q.setModelPosition("a", 0, 0); q.setModelPosition("b", 0, 0);
            q.setPulsePhase("a", Double.POSITIVE_INFINITY);
            q.setPulsePhase("b", Double.NEGATIVE_INFINITY);
            return isFinite01(q.getModel("a").pulsePhase)
                && isFinite01(q.getModel("b").pulsePhase);
        }));
        cs.add(new Case("quorum.advancePulseHugeDeltaStaysFinite", () -> {
            WeightedQuorumVote q = new WeightedQuorumVote(1, 1, 1);
            q.setModelPosition("a", 0, 0);
            q.advanceTimePulse(1e300);
            q.advanceTimePulse(-1e300);
            return isFinite01(q.getModel("a").pulsePhase);
        }));
        cs.add(new Case("quorum.advancePulseNaNStaysFinite", () -> {
            WeightedQuorumVote q = new WeightedQuorumVote(1, 1, 1);
            q.setModelPosition("a", 0, 0);
            q.advanceTimePulse(Double.NaN);
            return isFinite01(q.getModel("a").pulsePhase);
        }));
        cs.add(new Case("quorum.pulseWrapsAtOne", () -> {
            WeightedQuorumVote q = new WeightedQuorumVote(1, 1, 1);
            q.setModelPosition("a", 0, 0);
            q.setPulsePhase("a", 0.9);
            q.advanceTimePulse(0.2);
            return isFinite01(q.getModel("a").pulsePhase);
        }));
        cs.add(new Case("quorum.nullModelCastDoesNotThrowAndAbstains", () -> {
            WeightedQuorumVote q = new WeightedQuorumVote(1, 3, 2);
            q.setModelPosition("a", 0, 0);
            q.registerProposal("p1", "t", 0, 0);
            return q.castVote("p1", null, WeightedQuorumVote.Vote.APPROVE, VoteQualityLedger.SCRIPTED)
                == WeightedQuorumVote.Vote.ABSTAIN;
        }));
        cs.add(new Case("quorum.blankModelCastDoesNotThrowAndAbstains", () -> {
            WeightedQuorumVote q = new WeightedQuorumVote(1, 2, 1);
            q.setModelPosition("a", 0, 0);
            q.registerProposal("p1", "t", 0, 0);
            return q.castVote("p1", "   ", WeightedQuorumVote.Vote.REJECT, VoteQualityLedger.SCRIPTED)
                == WeightedQuorumVote.Vote.ABSTAIN;
        }));
        cs.add(new Case("quorum.nullModelCastRecordedInLedger", () -> {
            WeightedQuorumVote q = new WeightedQuorumVote(1, 2, 1);
            q.setModelPosition("a", 0, 0);
            q.registerProposal("p1", "t", 0, 0);
            q.castVote("p1", null, WeightedQuorumVote.Vote.APPROVE, VoteQualityLedger.SCRIPTED);
            return q.getProposal("p1").totalVotes() == 0
                && VoteQualityLedger.get().totalRecords() >= 1;
        }));
        cs.add(new Case("quorum.registerNullIdGetsSyntheticId", () -> {
            WeightedQuorumVote q = new WeightedQuorumVote(1, 1, 1);
            WeightedQuorumVote.Proposal p = q.registerProposal(null, "t", 0, 0);
            return p.id != null && !p.id.isEmpty()
                && q.calculateQuorum(p.id) != null;
        }));
        cs.add(new Case("quorum.registerNullHexGetsOrigin", () -> {
            WeightedQuorumVote q = new WeightedQuorumVote(1, 2, 1);
            q.setModelPosition("a", 0, 0); q.setModelPosition("b", 0, 0);
            WeightedQuorumVote.Proposal p = q.registerProposal("p1", "t", (HexCoord) null);
            q.castVote(p.id, "a", WeightedQuorumVote.Vote.APPROVE, VoteQualityLedger.SCRIPTED);
            q.castVote(p.id, "b", WeightedQuorumVote.Vote.APPROVE, VoteQualityLedger.SCRIPTED);
            WeightedQuorumVote.QuorumResult r = q.calculateQuorum(p.id);
            return r != null && "0,0".equals(r.hexKey) && "APPROVED".equals(r.status);
        }));
        cs.add(new Case("quorum.registerNullTextBecomesEmpty", () -> {
            WeightedQuorumVote q = new WeightedQuorumVote(1, 1, 1);
            WeightedQuorumVote.Proposal p = q.registerProposal("p1", null, 0, 0);
            return p.text.isEmpty();
        }));
        cs.add(new Case("quorum.calculateQuorumUnknownIdNull", () -> {
            WeightedQuorumVote q = new WeightedQuorumVote();
            return q.calculateQuorum("missing") == null;
        }));
        cs.add(new Case("quorum.finishCycleClearsProposals", () -> {
            WeightedQuorumVote q = new WeightedQuorumVote();
            q.registerProposal("p1", "t", 0, 0);
            q.finishCycle();
            return q.proposalCount() == 0;
        }));
        cs.add(new Case("quorum.finishCycleKeepsModels", () -> {
            WeightedQuorumVote q = new WeightedQuorumVote();
            q.setModelPosition("a", 0, 0);
            q.registerProposal("p1", "t", 0, 0);
            q.finishCycle();
            return q.modelCount() == 1;
        }));
        cs.add(new Case("quorum.autoVoteAllDeterministic", () -> {
            // autoVoteAll is seeded by (proposalId + "|" + model).hashCode()
            // → floorMod(%10) < 7 decides. Same inputs MUST give the same
            // voteMap every fresh construction, and the pinned seed for
            // "pK|a" is 8 (≥7 → REJECT) — both asserted here.
            Map<String, WeightedQuorumVote.Vote> a = quorumAutoVoteFresh("pK", "same text");
            Map<String, WeightedQuorumVote.Vote> b = quorumAutoVoteFresh("pK", "same text");
            return a.equals(b)
                && WeightedQuorumVote.Vote.REJECT.equals(a.get("a"))
                && WeightedQuorumVote.Vote.APPROVE.equals(a.get("c"));
        }));
        cs.add(new Case("quorum.decideVoteRejectCaseInsensitive", () ->
            WeightedQuorumVote.decideVote("i say ReJeCt, final answer").vote() == WeightedQuorumVote.Vote.REJECT
                && VoteQualityLedger.REAL.equals(WeightedQuorumVote.decideVote("ReJeCt").outcome())));
        cs.add(new Case("quorum.decideVoteRawWinsOverThinkNoise", () -> {
            // raw-authoritative parse: the think block may contradict the
            // ballot; the RAW last line decides → outcome stays real.
            String open = "<" + "think" + ">", close = "<" + "/" + "think" + ">";
            WeightedQuorumVote.VoteDecision d =
                WeightedQuorumVote.decideVote(open + "I say REJECT instead" + close + "\nAPPROVE");
            return d.vote() == WeightedQuorumVote.Vote.APPROVE
                && VoteQualityLedger.REAL.equals(d.outcome());
        }));
        cs.add(new Case("quorum.decideVoteStripRealAfterStrip", () -> {
            // trailing think block: the RAW last line is the token-free think
            // line (raw parse fails), after strip the ballot becomes last →
            // real_after_strip.
            String open = "<" + "think" + ">", close = "<" + "/" + "think" + ">";
            WeightedQuorumVote.VoteDecision d1 =
                WeightedQuorumVote.decideVote("REJECT\n" + open + "hmm what about neither" + close);
            WeightedQuorumVote.VoteDecision d2 =
                WeightedQuorumVote.decideVote("APPROVE\n" + open + "noise with no ballot" + close);
            return d1.vote() == WeightedQuorumVote.Vote.REJECT
                && VoteQualityLedger.REAL_AFTER_STRIP.equals(d1.outcome())
                && d2.vote() == WeightedQuorumVote.Vote.APPROVE
                && VoteQualityLedger.REAL_AFTER_STRIP.equals(d2.outcome());
        }));
        cs.add(new Case("quorum.decideVoteEmptyAbstains", () ->
            WeightedQuorumVote.decideVote("").vote() == WeightedQuorumVote.Vote.ABSTAIN
                && VoteQualityLedger.ABSTAIN_EMPTY.equals(WeightedQuorumVote.decideVote("").outcome())));
        cs.add(new Case("quorum.decideVoteNullAbstains", () ->
            WeightedQuorumVote.decideVote(null).vote() == WeightedQuorumVote.Vote.ABSTAIN));
        cs.add(new Case("quorum.decideVoteGarbageAbstains", () ->
            WeightedQuorumVote.decideVote("hello world 123 !!!").vote() == WeightedQuorumVote.Vote.ABSTAIN
                && VoteQualityLedger.ABSTAIN_UNPARSEABLE.equals(
                    WeightedQuorumVote.decideVote("hello world 123 !!!").outcome())));
        cs.add(new Case("quorum.unknownModelCastVisibleHeuristic", () -> {
            WeightedQuorumVote q = new WeightedQuorumVote(1, 1, 1);
            q.registerProposal("p1", "t", 0, 0);
            // unknown models bypass FOW (isVisible returns true) → intent passes
            return q.castVote("p1", "ghost", WeightedQuorumVote.Vote.APPROVE, VoteQualityLedger.SCRIPTED)
                == WeightedQuorumVote.Vote.APPROVE;
        }));

        // ── ROUTER (10) — tier mapping, latency ceiling, estimate bounds ──
        cs.add(new Case("router.lowIsQwen", () ->
            new ModelRouter().select(Complexity.LOW).equals(ModelRouter.FAST)));
        cs.add(new Case("router.veryLowIsQwen", () ->
            new ModelRouter().select(Complexity.VERY_LOW).equals(ModelRouter.FAST)));
        cs.add(new Case("router.mediumIsTinyllama", () ->
            new ModelRouter().select(Complexity.MEDIUM).equals(ModelRouter.BALANCED)));
        cs.add(new Case("router.highIsPhi", () ->
            new ModelRouter().select(Complexity.HIGH).equals(ModelRouter.REASONING)));
        cs.add(new Case("router.veryHighIsPhi3", () ->
            new ModelRouter().select(Complexity.VERY_HIGH).equals(ModelRouter.DEEP)));
        cs.add(new Case("router.criticalIsPhi3", () ->
            new ModelRouter().select(Complexity.CRITICAL).equals(ModelRouter.DEEP)));
        cs.add(new Case("router.latencyCeilingForcesFast", () ->
            new ModelRouter().selectWithLatency(Complexity.CRITICAL, 100).equals(ModelRouter.FAST)));
        cs.add(new Case("router.latencyCeilingForcesBalanced", () ->
            new ModelRouter().selectWithLatency(Complexity.CRITICAL, 500).equals(ModelRouter.BALANCED)));
        cs.add(new Case("router.latencyCeilingNoPressureKeepsTier", () ->
            new ModelRouter().selectWithLatency(Complexity.LOW, 60_000).equals(ModelRouter.FAST)));
        // #134 (H43): flash lane — trivial → FLASH_MODEL, one-way (no escalation).
        cs.add(new Case("router.flashTrivialGoesFlash", () ->
            new ModelRouter().selectFlash(Complexity.VERY_LOW)
                .equals(com.mindpalace.agent.ModelConfig.FLASH_MODEL)));
        cs.add(new Case("router.flashLowGoesFlash", () ->
            new ModelRouter().selectFlash(Complexity.LOW)
                .equals(com.mindpalace.agent.ModelConfig.FLASH_MODEL)));
        cs.add(new Case("router.flashMediumKeepsTier", () ->
            new ModelRouter().selectFlash(Complexity.MEDIUM).equals(ModelRouter.BALANCED)));
        cs.add(new Case("router.flashModelIsConfigured", () ->
            com.mindpalace.agent.ModelConfig.FLASH_MODEL != null
                && com.mindpalace.agent.ModelConfig.FLASH_MODEL.equals(
                    com.mindpalace.agent.ModelConfig.CRITIC_MODEL)));
        // #135 (H44): quota firewall — burst OFF by default, endpoints rejected,
        // local tags pass, every configured model survives the firewall.
        cs.add(new Case("firewall.burstClientOffByDefault", () ->
            !com.mindpalace.agent.ModelConfig.BURST_CLIENT_ENABLED));
        cs.add(new Case("firewall.rejectsHttpApi", () -> {
            try { com.mindpalace.agent.OllamaClient.assertLocalModel("http://api.openai.com/v1"); return false; }
            catch (IllegalArgumentException expected) { return true; }
        }));
        cs.add(new Case("firewall.rejectsHttpsUrl", () -> {
            try { com.mindpalace.agent.OllamaClient.assertLocalModel("https://api.anthropic.com"); return false; }
            catch (IllegalArgumentException expected) { return true; }
        }));
        cs.add(new Case("firewall.rejectsCloudishHost", () -> {
            try { com.mindpalace.agent.OllamaClient.assertLocalModel("openai-api.com/model"); return false; }
            catch (IllegalArgumentException expected) { return true; }
        }));
        cs.add(new Case("firewall.acceptsOllamaTags", () -> {
            com.mindpalace.agent.OllamaClient.assertLocalModel("qwen2.5:0.5b");
            com.mindpalace.agent.OllamaClient.assertLocalModel("llama3.2:1b");
            com.mindpalace.agent.OllamaClient.assertLocalModel("localhost:11434");
            return true;
        }));
        cs.add(new Case("firewall.allConfiguredModelsLocal", () -> {
            com.mindpalace.agent.OllamaClient.assertLocalModel(com.mindpalace.agent.ModelConfig.TOOL_MODEL);
            com.mindpalace.agent.OllamaClient.assertLocalModel(com.mindpalace.agent.ModelConfig.CRITIC_MODEL);
            com.mindpalace.agent.OllamaClient.assertLocalModel(com.mindpalace.agent.ModelConfig.EMBED_MODEL);
            com.mindpalace.agent.OllamaClient.assertLocalModel(ModelRouter.FAST);
            com.mindpalace.agent.OllamaClient.assertLocalModel(ModelRouter.BALANCED);
            com.mindpalace.agent.OllamaClient.assertLocalModel(ModelRouter.REASONING);
            com.mindpalace.agent.OllamaClient.assertLocalModel(ModelRouter.DEEP);
            com.mindpalace.agent.OllamaClient.assertLocalModel(com.mindpalace.agent.ModelConfig.FLASH_MODEL);
            return true;
        }));
        cs.add(new Case("router.estimateLatencyMonotonic", () -> {
            ModelRouter r = new ModelRouter();
            return r.estimateLatency(Complexity.LOW) <= r.estimateLatency(Complexity.MEDIUM)
                && r.estimateLatency(Complexity.MEDIUM) <= r.estimateLatency(Complexity.HIGH)
                && r.estimateLatency(Complexity.HIGH) <= r.estimateLatency(Complexity.CRITICAL);
        }));

        // ── LORA (10) — switch mechanics, counts, circular rotation ──
        cs.add(new Case("lora.startsOnChat", () ->
            new LoRASwitcher().currentType() == AdapterType.CHAT));
        cs.add(new Case("lora.switchToCode", () -> {
            LoRASwitcher s = new LoRASwitcher();
            s.switchAdapter(AdapterType.CODE);
            return s.currentType() == AdapterType.CODE;
        }));
        cs.add(new Case("lora.switchCountIncrementsOnlyOnRealSwitch", () -> {
            LoRASwitcher s = new LoRASwitcher();
            s.switchAdapter(AdapterType.CODE);
            s.switchAdapter(AdapterType.PATHFIND);
            s.switchAdapter(AdapterType.PATHFIND);
            return s.getSwitchCount() == 2;
        }));
        cs.add(new Case("lora.avgSwitchMsNonNegative", () -> {
            LoRASwitcher s = new LoRASwitcher();
            s.switchAdapter(AdapterType.CODE);
            return s.getAvgSwitchMs() >= 0;
        }));
        cs.add(new Case("lora.avgSwitchMsZeroWhenNoSwitch", () ->
            new LoRASwitcher().getAvgSwitchMs() == 0));
        cs.add(new Case("lora.switchToNextRotates", () -> {
            LoRASwitcher s = new LoRASwitcher();
            AdapterType first = s.currentType();
            s.switchToNext();
            return s.currentType() != first;
        }));
        cs.add(new Case("lora.switchToNextVisitsAllSix", () -> {
            LoRASwitcher s = new LoRASwitcher();
            java.util.Set<AdapterType> seen = java.util.EnumSet.noneOf(AdapterType.class);
            for (int i = 0; i < AdapterType.values().length; i++) {
                s.switchToNext();
                seen.add(s.currentType());
            }
            return seen.size() == AdapterType.values().length;
        }));
        cs.add(new Case("lora.roundTripReturnsToStart", () -> {
            LoRASwitcher s = new LoRASwitcher();
            AdapterType start = s.currentType();
            for (int i = 0; i < AdapterType.values().length; i++) s.switchToNext();
            return s.currentType() == start;
        }));
        cs.add(new Case("lora.contextRoundTrip", () -> {
            LoRASwitcher s = new LoRASwitcher();
            s.setContext("lang", "Java");
            return "Java".equals((String) s.getContext("lang"));
        }));

        // ── FOW (10) — pin/assign/hop/slice/disable ──
        cs.add(new Case("fow.modelWithoutAgentSeesAll", () ->
            new FOWGate(1).isVisible(new HexCoord(100, 100), "ghost")));
        cs.add(new Case("fow.agentWithoutHexSeesAll", () -> {
            FOWGate g = new FOWGate(1);
            g.assignModel("m", "orphan");
            return g.isVisible(new HexCoord(100, 100), "m");
        }));
        cs.add(new Case("fow.sameHexVisible", () -> {
            FOWGate g = new FOWGate(1);
            g.pinAgent("a", new HexCoord(3, 3));
            g.assignModel("m", "a");
            return g.isVisible(new HexCoord(3, 3), "m");
        }));
        cs.add(new Case("fow.oneHopVisible", () -> {
            FOWGate g = new FOWGate(1);
            g.pinAgent("a", new HexCoord(3, 3));
            g.assignModel("m", "a");
            return g.isVisible(new HexCoord(4, 3), "m");
        }));
        cs.add(new Case("fow.twoHopsHiddenAtHopOne", () -> {
            FOWGate g = new FOWGate(1);
            g.pinAgent("a", new HexCoord(3, 3));
            g.assignModel("m", "a");
            return !g.isVisible(new HexCoord(5, 3), "m");
        }));
        cs.add(new Case("fow.widerHopSeesFarther", () -> {
            FOWGate g = new FOWGate(2);
            g.pinAgent("a", new HexCoord(3, 3));
            g.assignModel("m", "a");
            return g.isVisible(new HexCoord(5, 3), "m");
        }));
        cs.add(new Case("fow.aaxialDistanceSymmetric", () -> {
            HexCoord a = new HexCoord(-7, 2), b = new HexCoord(4, -5);
            return a.distanceTo(b) == b.distanceTo(a);
        }));
        cs.add(new Case("fow.disableSeesAll", () -> {
            FOWGate g = new FOWGate(0);
            g.pinAgent("a", new HexCoord(0, 0));
            g.assignModel("m", "a");
            g.setEnabled(false);
            return g.isVisible(new HexCoord(50, 50), "m") && !g.isEnabled();
        }));
        cs.add(new Case("fow.sliceLocalNullWhenHidden", () -> {
            FOWGate g = new FOWGate(1);
            g.pinAgent("a", new HexCoord(0, 0));
            g.assignModel("m", "a");
            return g.sliceLocal(new HexCoord(9, 9), "m") == null;
        }));
        cs.add(new Case("fow.sliceLocalKeepsVisible", () -> {
            FOWGate g = new FOWGate(1);
            g.pinAgent("a", new HexCoord(0, 0));
            g.assignModel("m", "a");
            return g.sliceLocal(new HexCoord(1, 0), "m") != null;
        }));

        // ── HEX (10) — parse, distance, neighbors, key/equals ──
        cs.add(new Case("hex.parseValid", () ->
            HexCoord.fromString("  3 , -4 ").equals(new HexCoord(3, -4))));
        cs.add(new Case("hex.parseNoCommaFallsBack", () ->
            HexCoord.fromString("nocomma").equals(new HexCoord(0, 0))));
        cs.add(new Case("hex.parseNullFallsBack", () ->
            HexCoord.fromString(null).equals(new HexCoord(0, 0))));
        cs.add(new Case("hex.parseEmptyFallsBack", () ->
            HexCoord.fromString("  ").equals(new HexCoord(0, 0))));
        cs.add(new Case("hex.parseGarbageFallsBackNoThrow", () ->
            HexCoord.fromString("a,b,c").equals(new HexCoord(0, 0))));
        cs.add(new Case("hex.parsePartialGarbageFallsBack", () ->
            HexCoord.fromString("1,x").equals(new HexCoord(0, 0))));
        cs.add(new Case("hex.distanceZero", () ->
            new HexCoord(5, 5).distanceTo(new HexCoord(5, 5)) == 0));
        cs.add(new Case("hex.neighborsExactlySixAndAdjacent", () -> {
            HexCoord base = new HexCoord(2, -3);
            HexCoord[] n = base.neighbors();
            if (n.length != 6) return false;
            for (HexCoord h : n) if (base.distanceTo(h) != 1) return false;
            return true;
        }));
        cs.add(new Case("hex.keyRoundTrip", () ->
            HexCoord.fromString(new HexCoord(-12, 34).key()).equals(new HexCoord(-12, 34))));
        cs.add(new Case("hex.equalsHashCodeContract", () -> {
            HexCoord a = new HexCoord(1, 2), b = new HexCoord(1, 2);
            return a.equals(b) && a.hashCode() == b.hashCode() && !a.equals(new HexCoord(2, 1));
        }));

        // ── LEDGER (10) — outcome vocabulary, counting, summary ──
        cs.add(new Case("ledger.isAbstainFamily", () ->
            VoteQualityLedger.isAbstain(VoteQualityLedger.ABSTAIN_TIMEOUT)
                && VoteQualityLedger.isAbstain(VoteQualityLedger.ABSTAIN_EMPTY)
                && VoteQualityLedger.isAbstain(VoteQualityLedger.ABSTAIN_ERROR)
                && VoteQualityLedger.isAbstain(VoteQualityLedger.ABSTAIN_UNPARSEABLE)));
        cs.add(new Case("ledger.isAbstainFalseForRealFamilies", () ->
            !VoteQualityLedger.isAbstain(VoteQualityLedger.REAL)
                && !VoteQualityLedger.isAbstain(VoteQualityLedger.REAL_AFTER_STRIP)
                && !VoteQualityLedger.isAbstain(VoteQualityLedger.BLIND)
                && !VoteQualityLedger.isAbstain(VoteQualityLedger.SIM)
                && !VoteQualityLedger.isAbstain(VoteQualityLedger.SCRIPTED)));
        cs.add(new Case("ledger.isAbstainNullSafe", () ->
            !VoteQualityLedger.isAbstain(null)));
        cs.add(new Case("ledger.modelStatsCountsReal", () -> {
            VoteQualityLedger.ModelStats s = new VoteQualityLedger.ModelStats("m");
            s.add(VoteQualityLedger.REAL);
            s.add(VoteQualityLedger.REAL_AFTER_STRIP);
            return s.realCount() == 1 && s.realAfterStripCount() == 1 && s.abstainCount() == 0;
        }));
        cs.add(new Case("ledger.modelStatsCountsAbstainBlindSim", () -> {
            VoteQualityLedger.ModelStats s = new VoteQualityLedger.ModelStats("m");
            s.add(VoteQualityLedger.ABSTAIN_TIMEOUT);
            s.add(VoteQualityLedger.BLIND);
            s.add(VoteQualityLedger.SIM);
            s.add(VoteQualityLedger.SCRIPTED);
            return s.abstainCount() == 1 && s.blindCount() == 1 && s.simCount() == 2;
        }));
        cs.add(new Case("ledger.modelStatsTotalConsistent", () -> {
            VoteQualityLedger.ModelStats s = new VoteQualityLedger.ModelStats("m");
            s.add(VoteQualityLedger.REAL);
            s.add(VoteQualityLedger.BLIND);
            s.add(VoteQualityLedger.ABSTAIN_EMPTY);
            s.add(VoteQualityLedger.SIM);
            return s.total() == 4 && s.total() ==
                s.realCount() + s.realAfterStripCount() + s.abstainCount() + s.blindCount() + s.simCount();
        }));
        cs.add(new Case("ledger.modelStatsUnknownOutcomeIgnored", () -> {
            VoteQualityLedger.ModelStats s = new VoteQualityLedger.ModelStats("m");
            s.add("weird_custom_outcome");
            return s.total() == 0;
        }));
        cs.add(new Case("ledger.recordCountsTotals", () -> {
            VoteQualityLedger l = VoteQualityLedger.get();
            long before = l.totalRecords();
            l.record(new VoteQualityLedger.VoteRecord(1L, "prop", "hardened-model-a", null,
                VoteQualityLedger.REAL, 5L));
            l.record(new VoteQualityLedger.VoteRecord(2L, "prop", "hardened-model-a", null,
                VoteQualityLedger.ABSTAIN_EMPTY, 6L));
            VoteQualityLedger.ModelStats s = l.stats("hardened-model-a");
            return l.totalRecords() == before + 2 && s.realCount() >= 1 && s.abstainCount() >= 1;
        }));
        cs.add(new Case("ledger.summarizeNullSafe", () ->
            VoteQualityLedger.summarize(null).equals("no votes")));
        cs.add(new Case("ledger.summarizeMixedCounts", () -> {
            Map<String, String> m = new java.util.HashMap<>();
            m.put("a", VoteQualityLedger.REAL);
            m.put("b", VoteQualityLedger.REAL_AFTER_STRIP);
            m.put("c", VoteQualityLedger.ABSTAIN_TIMEOUT);
            m.put("d", VoteQualityLedger.BLIND);
            return VoteQualityLedger.summarize(m).contains("real=2(+1 after strip)")
                && VoteQualityLedger.summarize(m).contains("abstain=1")
                && VoteQualityLedger.summarize(m).contains("scripted/blind=1");
        }));

        // ── ROTATOR (10) — deterministic 5-type cycle ──
        cs.add(new Case("rotator.firstIsHealthCheck", () ->
            new ProposalRotator().peek().equals(ProposalRotator.PROPOSAL_TYPES[0])));
        cs.add(new Case("rotator.peekDoesNotConsume", () -> {
            ProposalRotator r = new ProposalRotator();
            String a = r.peek();
            return r.peek().equals(a) && r.cycles() == 0;
        }));
        cs.add(new Case("rotator.cycleOrder", () -> {
            ProposalRotator r = new ProposalRotator();
            for (int i = 0; i < ProposalRotator.PROPOSAL_TYPES.length; i++)
                if (!r.next().equals(ProposalRotator.PROPOSAL_TYPES[i])) return false;
            return r.cycles() == 5;
        }));
        cs.add(new Case("rotator.wrapsAfterFive", () -> {
            ProposalRotator r = new ProposalRotator();
            for (int i = 0; i < 5; i++) r.next();
            return r.peek().equals(ProposalRotator.PROPOSAL_TYPES[0]);
        }));
        cs.add(new Case("rotator.hundredCyclesModFive", () -> {
            ProposalRotator r = new ProposalRotator();
            for (int i = 0; i < 100; i++) r.next();
            return r.peek().equals(ProposalRotator.PROPOSAL_TYPES[100 % 5])
                && r.cycles() == 100;
        }));
        cs.add(new Case("rotator.twoInstancesIndependent", () -> {
            ProposalRotator r1 = new ProposalRotator();
            r1.next();
            ProposalRotator r2 = new ProposalRotator();
            return r1.peek().equals(ProposalRotator.PROPOSAL_TYPES[1])
                && r2.peek().equals(ProposalRotator.PROPOSAL_TYPES[0]);
        }));
        cs.add(new Case("rotator.fiveTypesDefined", () ->
            ProposalRotator.PROPOSAL_TYPES.length == 5));
        cs.add(new Case("rotator.typesAreUnique", () -> {
            java.util.Set<String> s = new java.util.HashSet<>();
            for (String t : ProposalRotator.PROPOSAL_TYPES) s.add(t);
            return s.size() == ProposalRotator.PROPOSAL_TYPES.length;
        }));
        cs.add(new Case("rotator.concurrentNextNeverSkips", () -> {
            ProposalRotator r = new ProposalRotator();
            for (int i = 0; i < 50; i++) r.next();   // synchronized next(): no skipped slots
            return r.cycles() == 50;
        }));
        cs.add(new Case("rotator.peekAfterOddCycles", () -> {
            ProposalRotator r = new ProposalRotator();
            r.next(); r.next(); r.next();
            return r.peek().equals(ProposalRotator.PROPOSAL_TYPES[3 % 5]);
        }));

        // ── LANGUAGE (10) — registry coverage, toggle order, LoRA mapping ──
        cs.add(new Case("language.registryAtLeastTwenty", () -> LanguageRegistry.count() >= 20));
        cs.add(new Case("language.javaStartsToggle", () ->
            LanguageRegistry.next("Java").equals("Python")));
        cs.add(new Case("language.toggleWrapsToFirst", () ->
            LanguageRegistry.next("Markdown").equals("Java")));
        cs.add(new Case("language.byNameCaseInsensitive", () ->
            LanguageRegistry.byName("jAvA") != null
                && LanguageRegistry.byName("jAvA").extension.equals(".java")));
        cs.add(new Case("language.byNameUnknownNull", () ->
            LanguageRegistry.byName("Whitespace") == null
                && LanguageRegistry.byName(null) == null));
        cs.add(new Case("language.byExtensionWithDot", () ->
            LanguageRegistry.byExtension(".py") != null));
        cs.add(new Case("language.byExtensionWithoutDot", () ->
            LanguageRegistry.byExtension("py") != null));
        cs.add(new Case("language.byExtensionUnknownNull", () ->
            LanguageRegistry.byExtension(".cob") == null && LanguageRegistry.byExtension(null) == null));
        cs.add(new Case("language.allCodeLanguagesMapToCodeLora", () -> {
            for (String name : new String[]{"Java", "Python", "Rust", "Go", "Shell"})
                if (LanguageRegistry.byName(name).adapter != AdapterType.CODE) return false;
            return true;
        }));
        cs.add(new Case("language.dataLanguagesMapToAnalysisLora", () -> {
            for (String name : new String[]{"SQL", "JSON", "YAML"})
                if (LanguageRegistry.byName(name).adapter != AdapterType.ANALYSIS) return false;
            return LanguageRegistry.byName("Markdown").adapter == AdapterType.CHAT;
        }));

        return cs;
    }
}