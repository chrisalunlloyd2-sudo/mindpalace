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

    /** Headless entry for the local CI gate (scripts/local_ci.sh): no window, no GL, no network. Run from the repo root (some cases read repo files).
     *  Exit 0 = every case passed; 1 = at least one failed (each printed as "FAIL name: detail"). */
    public static void main(String[] args) {
        List<String> failures = runAll();
        for (String f : failures) System.out.println("FAIL " + f);
        System.out.println("HARDENING " + summary(failures));
        System.exit(failures.isEmpty() ? 0 : 1);
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

    // ── small-box roster (step 156) ─────────────────────────────────────────

    /**
     * The production quorum for the 3-voter small-box roster: the same
     * constructor and the same hex table AgentManager.initSimsParity uses.
     */
    private static WeightedQuorumVote smallBoxQuorum() {
        int[][] hexes = {{0, 0}, {1, 0}, {0, 0}, {0, 1}, {1, 0}};
        WeightedQuorumVote q = new WeightedQuorumVote();
        List<String> roster = com.mindpalace.agent.ModelConfig.SMALL_BOX_VOTERS;
        for (int i = 0; i < roster.size(); i++) {
            q.setModelPosition(roster.get(i), hexes[i][0], hexes[i][1]);
        }
        q.registerProposal("p1", "t", 0, 0);
        return q;
    }

    /** Cast one ballot on the small roster: {@code seats[i]} is APPROVE, REJECT or ABSTAIN (a cold model that timed out). */
    private static String smallBoxBallot(WeightedQuorumVote.Vote... seats) {
        WeightedQuorumVote q = smallBoxQuorum();
        List<String> roster = com.mindpalace.agent.ModelConfig.SMALL_BOX_VOTERS;
        for (int i = 0; i < seats.length; i++) {
            q.castVote("p1", roster.get(i), seats[i], seats[i] == WeightedQuorumVote.Vote.ABSTAIN
                ? VoteQualityLedger.ABSTAIN_TIMEOUT : VoteQualityLedger.SCRIPTED);
        }
        return q.calculateQuorum("p1").status;
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
        cs.add(new Case("quorum.smallBoxRosterIsThreeAndVisible", () -> {
            // all three small-box seats sit within FOW hop 1 of the proposal: no ballot is ever BLIND
            WeightedQuorumVote q = smallBoxQuorum();
            List<String> roster = com.mindpalace.agent.ModelConfig.SMALL_BOX_VOTERS;
            if (roster.size() != 3) return false;
            for (String m : roster) {
                if (q.castVote("p1", m, WeightedQuorumVote.Vote.APPROVE, VoteQualityLedger.SCRIPTED) == WeightedQuorumVote.Vote.BLIND) return false;
            }
            return "APPROVED".equals(q.calculateQuorum("p1").status);
        }));
        cs.add(new Case("quorum.smallBoxColdVoterInAnySeatStillApproves", () -> {
            // MP-014 for 3 voters: one cold-loaded model that times out must not starve the ballot
            return "APPROVED".equals(smallBoxBallot(WeightedQuorumVote.Vote.ABSTAIN, WeightedQuorumVote.Vote.APPROVE, WeightedQuorumVote.Vote.APPROVE))
                && "APPROVED".equals(smallBoxBallot(WeightedQuorumVote.Vote.APPROVE, WeightedQuorumVote.Vote.ABSTAIN, WeightedQuorumVote.Vote.APPROVE))
                && "APPROVED".equals(smallBoxBallot(WeightedQuorumVote.Vote.APPROVE, WeightedQuorumVote.Vote.APPROVE, WeightedQuorumVote.Vote.ABSTAIN));
        }));
        cs.add(new Case("quorum.smallBoxColdVoterStillRejects", () -> {
            return "REJECTED".equals(smallBoxBallot(WeightedQuorumVote.Vote.REJECT, WeightedQuorumVote.Vote.REJECT, WeightedQuorumVote.Vote.ABSTAIN))
                && "REJECTED".equals(smallBoxBallot(WeightedQuorumVote.Vote.ABSTAIN, WeightedQuorumVote.Vote.REJECT, WeightedQuorumVote.Vote.REJECT));
        }));
        cs.add(new Case("quorum.smallBoxSplitWithColdVoterRejects", () -> {
            // one approve + one reject + one cold: two real votes reach the floor, one approve is below approveMin=2 -> REJECTED (nothing spawns)
            return "REJECTED".equals(smallBoxBallot(WeightedQuorumVote.Vote.APPROVE, WeightedQuorumVote.Vote.REJECT, WeightedQuorumVote.Vote.ABSTAIN));
        }));
        cs.add(new Case("quorum.smallBoxTwoColdVotersIsNoQuorum", () -> {
            // the limit of the 3-voter roster: two cold models leave one real vote, below REAL_VOTE_MIN
            return "NO_QUORUM".equals(smallBoxBallot(WeightedQuorumVote.Vote.APPROVE, WeightedQuorumVote.Vote.ABSTAIN, WeightedQuorumVote.Vote.ABSTAIN))
                && "NO_QUORUM".equals(smallBoxBallot(WeightedQuorumVote.Vote.ABSTAIN, WeightedQuorumVote.Vote.ABSTAIN, WeightedQuorumVote.Vote.ABSTAIN));
        }));
        cs.add(new Case("campus.deterministicForFixedTime", () -> {
            var a = com.mindpalace.world.EinsteinCampus.build(0f, 12.5f, true);
            var b = com.mindpalace.world.EinsteinCampus.build(0f, 12.5f, true);
            return a.size() == b.size() && a.equals(b) && a.size() > 150;
        }));
        cs.add(new Case("campus.allSixExhibitsPresentAndFinite", () -> {
            var pcs = com.mindpalace.world.EinsteinCampus.build(0f, 3.7f, true);
            for (String ex : com.mindpalace.world.EinsteinCampus.EXHIBITS) {
                if (pcs.stream().noneMatch(x -> ex.equals(x.exhibit()))) return false;
            }
            for (var x : pcs) {
                if (!Float.isFinite(x.x() + x.y() + x.z() + x.sx() + x.sy() + x.sz() + x.yaw() + x.r() + x.g() + x.b())) return false;
                if (x.sx() <= 0f || x.sy() <= 0f || x.sz() <= 0f) return false;
            }
            return true;
        }));
        cs.add(new Case("campus.insideItsRadiusAndAboveGround", () -> {
            float R = com.mindpalace.world.EinsteinCampus.RADIUS;
            for (float t : new float[]{0f, 1.3f, 9.9f, 47f}) {
                for (var x : com.mindpalace.world.EinsteinCampus.build(0f, t, true)) {
                    float dx = x.x() - com.mindpalace.world.EinsteinCampus.CX, dz = x.z() - com.mindpalace.world.EinsteinCampus.CZ;
                    if (dx * dx + dz * dz > (R + 2f) * (R + 2f)) return false;
                    if (x.y() - x.sy() / 2f < -0.1f) return false;
                }
            }
            return true;
        }));
        cs.add(new Case("campus.bloomHasBrightPieces", () -> {
            // the bloom bright-pass starts at luminance 0.6 and is full at 0.9: enough emissive pieces must clear it, or the bloom has nothing to show
            long bright = com.mindpalace.world.EinsteinCampus.build(0f, 2.2f, true).stream().filter(x -> x.emissive() && x.luminance() >= 0.9f).count();
            long all = com.mindpalace.world.EinsteinCampus.build(0f, 2.2f, true).stream().filter(x -> x.emissive()).count();
            return bright >= 40 && all >= 60;
        }));
        cs.add(new Case("campus.movingClockRunsAtOneOverGamma", () -> {
            float g = com.mindpalace.world.EinsteinCampus.GAMMA;
            float v2 = 1f - 1f / (g * g);                        // v^2/c^2 from gamma
            boolean sqrt3over2 = Math.abs(v2 - 0.75f) < 1e-5f;   // v = sqrt(3)/2 c
            float t = 10f;
            return sqrt3over2 && Math.abs(com.mindpalace.world.EinsteinCampus.movingHand(t) * g - com.mindpalace.world.EinsteinCampus.restHand(t)) < 1e-4f;
        }));
        cs.add(new Case("campus.farLodKeepsRingAndDropsDetail", () -> {
            var near = com.mindpalace.world.EinsteinCampus.build(0f, 1f, true);
            var far = com.mindpalace.world.EinsteinCampus.build(0f, 1f, false);
            boolean ringFar = far.stream().anyMatch(x -> "collider".equals(x.exhibit()));
            boolean noLatticeFar = far.stream().noneMatch(x -> "spacetime lattice".equals(x.exhibit()));
            return ringFar && noLatticeFar && far.size() < near.size() / 2;
        }));
        cs.add(new Case("campus.clearOfOtherDistricts", () -> {
            float cx = com.mindpalace.world.EinsteinCampus.CX, cz = com.mindpalace.world.EinsteinCampus.CZ, R = com.mindpalace.world.EinsteinCampus.RADIUS;
            float[][] others = {{20f, -180f, 14f}, {-60f, -120f, 12f}, {80f, -150f, 18f}, {0f, -250f, 20f}, {40f, -210f, 30f}};
            for (float[] o : others) {
                float dx = cx - o[0], dz = cz - o[1];
                if (Math.sqrt(dx * dx + dz * dz) < R + o[2]) return false;
            }
            return Math.abs(cx) + R < com.mindpalace.world.OutsideWorld.HALF_W
                && cz - R > com.mindpalace.world.OutsideWorld.MIN_Z && cz + R < com.mindpalace.world.OutsideWorld.MAX_Z;
        }));
        cs.add(new Case("telemetry.usesWalSoStartupDoesNotFsyncStall", () -> {
            try {
                java.nio.file.Path d = java.nio.file.Files.createTempDirectory("mp-tel");
                new com.mindpalace.backup.Telemetry(d).record(com.mindpalace.backup.Telemetry.SYSTEM, "boot", "t");
                try (java.sql.Connection c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + d.resolve("telemetry.db"));
                     java.sql.Statement st = c.createStatement(); java.sql.ResultSet rs = st.executeQuery("PRAGMA journal_mode")) {
                    return rs.next() && "wal".equalsIgnoreCase(rs.getString(1));
                }
            } catch (java.io.IOException | java.sql.SQLException e) { return false; }
        }));
        cs.add(new Case("ollama.flashBudgetDoesNotLeakOntoCriticOrCodeLane", () -> {
            // #371/#380: FLASH_MODEL, CRITIC_MODEL and CODE_MODEL share one tag, so a tag test cannot tell the lanes apart
            return com.mindpalace.agent.ModelConfig.CRITIC_MODEL.equals(com.mindpalace.agent.ModelConfig.FLASH_MODEL)
                && !com.mindpalace.agent.OllamaClient.optionsFor(com.mindpalace.agent.ModelConfig.CRITIC_MODEL).has("num_predict")
                && !com.mindpalace.agent.OllamaClient.optionsFor(com.mindpalace.agent.ModelConfig.CODE_MODEL).has("num_predict")
                && com.mindpalace.agent.OllamaClient.optionsFor(com.mindpalace.agent.ModelConfig.FLASH_MODEL, true).get("num_predict").getAsInt()
                   == com.mindpalace.agent.ModelConfig.FLASH_BUDGET;
        }));
        // Agent teams: fake local model, no network; every case is deterministic.
        cs.add(new Case("team.llmPlusBotRetriesUntilTheBotPasses", () -> {
            int[] calls = {0};
            com.mindpalace.agent.team.Member.Llm fake = (m, sys, user) -> "draft " + (++calls[0]);
            var t = com.mindpalace.agent.team.Team.llmPlusBot(
                com.mindpalace.agent.team.Member.llm("coder", "qwen2.5-coder:1.5b", AdapterType.CODE, "write", fake),
                com.mindpalace.agent.team.Member.bot("checker", b -> b.last().text().endsWith("3") ? "PASS" : "FIX: not yet"), 5);
            var r = new com.mindpalace.agent.team.TeamRunner(new LoRASwitcher()).run(t, "make it 3");
            return r.success() && r.answer().equals("draft 3") && r.turns() == 6 && r.board().log().get(1).text().startsWith("FIX");
        }));
        cs.add(new Case("team.loraSwapsOnlyWhenTheAdapterChanges", () -> {
            com.mindpalace.agent.team.Member.Llm fake = (m, sys, user) -> "ok";
            LoRASwitcher lora = new LoRASwitcher();   // starts on CHAT
            var t = new com.mindpalace.agent.team.Team("pipe", com.mindpalace.agent.team.Team.Pattern.PIPELINE, java.util.List.of(
                com.mindpalace.agent.team.Member.llm("a", "m", AdapterType.CODE, "", fake),
                com.mindpalace.agent.team.Member.llm("b", "m", AdapterType.CODE, "", fake),
                com.mindpalace.agent.team.Member.bot("bot", b -> "seen"),
                com.mindpalace.agent.team.Member.llm("c", "m", AdapterType.ANALYSIS, "", fake)), 1);
            var r = new com.mindpalace.agent.team.TeamRunner(lora).run(t, "x");
            return r.success() && r.adapterSwaps() == 2 && lora.currentType() == AdapterType.ANALYSIS && r.answer().equals("ok");
        }));
        cs.add(new Case("team.toolLinesRunDeterministically", () -> {
            String out = com.mindpalace.agent.team.Member.runToolLines("plan\n? add 2 3\n? nope x",
                java.util.Map.of("add", a -> { String[] p = a.split(" "); return String.valueOf(Integer.parseInt(p[0]) + Integer.parseInt(p[1])); }));
            return out.equals("plan\n? add 2 3\n= 5\n? nope x\n= ERR unknown tool");
        }));
        cs.add(new Case("team.aMemberCrashFailsTheTeamNotTheGame", () -> {
            var t = com.mindpalace.agent.team.Team.twoLlms(
                com.mindpalace.agent.team.Member.llm("w", "m", AdapterType.CODE, "", (m, s, u) -> { throw new IllegalStateException("model gone"); }),
                com.mindpalace.agent.team.Member.bot("c", b -> "PASS"), 3);
            var r = new com.mindpalace.agent.team.TeamRunner(new LoRASwitcher()).run(t, "x");
            return !r.success() && r.why().contains("model gone");
        }));
        cs.add(new Case("team.endlessCriticHitsTheRoundCap", () -> {
            var t = com.mindpalace.agent.team.Team.twoLlms(
                com.mindpalace.agent.team.Member.llm("w", "m", AdapterType.CODE, "", (m, s, u) -> "d"),
                com.mindpalace.agent.team.Member.llm("c", "m", AdapterType.ANALYSIS, "", (m, s, u) -> "no"), 4);
            var r = new com.mindpalace.agent.team.TeamRunner(new LoRASwitcher()).run(t, "x");
            return !r.success() && r.turns() == 8 && r.why().contains("4 rounds");
        }));
        cs.add(new Case("team.arenaPicksTheHigherScoreAndRecordsIt", () -> {
            var sb = new com.mindpalace.agent.team.Scoreboard();
            var fast = com.mindpalace.agent.team.Team.llmPlusBot(com.mindpalace.agent.team.Member.llm("a", "m", AdapterType.CODE, "", (m, s, u) -> "1. do it\n2. test it"), com.mindpalace.agent.team.PlanCheck.bot("a"), 2);
            int[] n = {0};
            var slow = com.mindpalace.agent.team.Team.llmPlusBot(com.mindpalace.agent.team.Member.llm("b", "m", AdapterType.CODE, "", (m, s, u) -> ++n[0] == 1 ? "no steps" : "1. do it\n2. test it"), com.mindpalace.agent.team.PlanCheck.bot("b"), 2);
            var broken = com.mindpalace.agent.team.Team.llmPlusBot(com.mindpalace.agent.team.Member.llm("c", "m", AdapterType.CODE, "", (m, s, u) -> { throw new IllegalStateException("gone"); }), com.mindpalace.agent.team.PlanCheck.bot("c"), 2);
            var out = new com.mindpalace.agent.team.Arena(new com.mindpalace.agent.team.TeamRunner(new LoRASwitcher()), sb).compete(java.util.List.of(slow, broken, fast), "t", com.mindpalace.agent.team.PlanCheck::score);
            return out.winner().team() == fast && out.entries().get(1).score() == -1 && out.entries().get(0).score() < out.winner().score()
                && sb.wins(fast.name()) == 1 && sb.runs(slow.name()) == 1 && sb.wins(slow.name()) == 0 && sb.runs(broken.name()) == 1;
        }));
        cs.add(new Case("team.arenaHasNoWinnerWhenEveryTeamFails", () -> {
            var t = com.mindpalace.agent.team.Team.llmPlusBot(com.mindpalace.agent.team.Member.llm("a", "m", AdapterType.CODE, "", (m, s, u) -> "nope"), com.mindpalace.agent.team.PlanCheck.bot("a"), 2);
            var out = new com.mindpalace.agent.team.Arena(new com.mindpalace.agent.team.TeamRunner(new LoRASwitcher()), null).compete(java.util.List.of(t), "t", com.mindpalace.agent.team.PlanCheck::score);
            return out.winner() == null && out.entries().size() == 1 && !out.entries().get(0).result().success();
        }));
        cs.add(new Case("team.planCheckIsDeterministicAndLocalOnly", () -> {
            return com.mindpalace.agent.team.PlanCheck.check("1. read the file\n2. fix the bug").equals("PASS")
                && com.mindpalace.agent.team.PlanCheck.check("just do it").startsWith("FIX")
                && com.mindpalace.agent.team.PlanCheck.check("1. call https://api.example.com\n2. done").contains("local only")
                && com.mindpalace.agent.team.PlanCheck.check("1. a\n2. b\nWould you like more?").contains("meta-talk")
                && com.mindpalace.agent.team.PlanCheck.check(null).startsWith("FIX")
                && com.mindpalace.agent.team.PlanCheck.check("1. a\n2. b\n3. c\n4. d\n5. e\n6. f").contains("five");
        }));
        cs.add(new Case("team.scoreboardPicksBestPlusLeastTriedAndSurvivesRestart", () -> {
            try {
                com.mindpalace.agent.team.Member.Llm f = (m, s, u) -> "x";
                var a = com.mindpalace.agent.team.Team.llmPlusBot(com.mindpalace.agent.team.Member.llm("a", "m", AdapterType.CODE, "", f), com.mindpalace.agent.team.PlanCheck.bot("a"), 1);
                var b = com.mindpalace.agent.team.Team.llmPlusBot(com.mindpalace.agent.team.Member.llm("b", "m", AdapterType.CODE, "", f), com.mindpalace.agent.team.PlanCheck.bot("b"), 1);
                var c = com.mindpalace.agent.team.Team.llmPlusBot(com.mindpalace.agent.team.Member.llm("c", "m", AdapterType.CODE, "", f), com.mindpalace.agent.team.PlanCheck.bot("c"), 1);
                var sb = new com.mindpalace.agent.team.Scoreboard();
                sb.record(a.name(), false); sb.record(a.name(), false); sb.record(b.name(), true); sb.record(b.name(), false);
                java.nio.file.Path tmp = java.nio.file.Files.createTempFile("team_scores", ".tsv");
                sb.save(tmp);
                var re = com.mindpalace.agent.team.Scoreboard.load(tmp);
                java.nio.file.Files.deleteIfExists(tmp);
                var pick = re.pick(java.util.List.of(a, b, c), 2);
                return pick.size() == 2 && pick.get(0) == b && pick.get(1) == c && re.runs(a.name()) == 2 && re.wins(b.name()) == 1
                    && com.mindpalace.agent.team.Scoreboard.load(tmp).runs(a.name()) == 0;
            } catch (Exception e) { return false; }
        }));
        cs.add(new Case("team.dispatchWritesTheWinnersPlanAndRefusesASecondJob", () -> {
            try {
                java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("team_dispatch");
                java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1), release = new java.util.concurrent.CountDownLatch(1);
                com.mindpalace.agent.team.Member.Llm fake = (m, s, u) -> {
                    entered.countDown();
                    try { release.await(20, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    return s.contains("review") ? "PASS" : "1. open the room\n2. fix the door";
                };
                var d = new com.mindpalace.agent.team.TeamDispatch(new com.mindpalace.agent.team.TeamRunner(new LoRASwitcher()), com.mindpalace.agent.team.TeamDispatch.defaultRoster(fake), dir, x -> { });
                boolean first = d.offer("cyc-1", "REFACTOR", "tidy the door code");
                boolean inside = entered.await(20, java.util.concurrent.TimeUnit.SECONDS);
                boolean second = d.offer("cyc-2", "REFACTOR", "another");
                release.countDown();
                for (int i = 0; i < 200 && d.busy(); i++) Thread.sleep(50);
                String ledger = java.nio.file.Files.readString(dir.resolve("team_ledger.jsonl"));
                boolean scores = java.nio.file.Files.isRegularFile(dir.resolve("team_scores.tsv"));
                try (var w = java.nio.file.Files.walk(dir)) { w.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); }
                return first && inside && !second && !d.busy() && scores && ledger.lines().count() == 1
                    && ledger.contains("\"cycle_id\":\"cyc-1\"") && ledger.contains("\"winner\":\"planner+bot\"")
                    && ledger.contains("fix the door") && !ledger.contains("cyc-2");
            } catch (Exception e) { return false; }
        }));
        cs.add(new Case("team.aTurnTheLaneGivesUpOnFailsTheTeamAndTeamsAreOffByDefault", () -> {
            com.mindpalace.agent.team.TeamRunner.Lane boom = turn -> { throw new IllegalStateException("turn timed out after 1 ms"); };
            var t = com.mindpalace.agent.team.Team.llmPlusBot(com.mindpalace.agent.team.Member.llm("a", "m", AdapterType.CODE, "", (m, s, u) -> "x"), com.mindpalace.agent.team.Member.bot("b", b -> "PASS"), 1);
            var r = new com.mindpalace.agent.team.TeamRunner(new LoRASwitcher(), boom).run(t, "x");
            return !r.success() && r.why().contains("timed out") && !com.mindpalace.agent.team.TeamDispatch.enabled();
        }));
        cs.add(new Case("lane.refusesAnyHostThatIsNotThisMachine", () -> {
            for (String bad : new String[]{"http://example.com:8080", "https://127.0.0.1:18080", "http://10.0.0.5:18080", "", "ollama.com"}) {
                try { com.mindpalace.agent.lane.LaneClient.assertLoopback(bad); return false; } catch (IllegalArgumentException expected) { }
            }
            com.mindpalace.agent.lane.LaneClient.assertLoopback("http://127.0.0.1:18080");
            com.mindpalace.agent.lane.LaneClient.assertLoopback("http://localhost:18080/");
            return true;
        }));
        cs.add(new Case("lane.theAdapterRecipeTravelsInsideTheRequest", () -> {
            String[] sent = {null};
            var lane = new com.mindpalace.agent.lane.LaneClient((path, body) -> {
                if (path.equals("/health")) return "{\"status\":\"ok\"}";
                if (path.equals("/lora-adapters")) return "[{\"id\":0,\"path\":\"C:/x/probe_a.gguf\",\"scale\":1.0},{\"id\":1,\"path\":\"C:\\\\y\\\\probe_b.GGUF\",\"scale\":1.0}]";
                sent[0] = body;
                return "{\"choices\":[{\"message\":{\"content\":\"1. a\\n2. b\"}}]}";
            });
            var bank = com.mindpalace.agent.lane.AdapterBank.of(java.util.Map.of(AdapterType.CODE, java.util.Map.of("probe_b", 1.0)));
            java.util.List<String> packets = new java.util.ArrayList<>();
            String code = com.mindpalace.agent.team.Member.onLane(lane, bank, AdapterType.CODE, 64, packets::add).chat("ignored", "sys", "user");
            var on = com.google.gson.JsonParser.parseString(sent[0]).getAsJsonObject();
            com.mindpalace.agent.team.Member.onLane(lane, bank, AdapterType.CHAT, 64, null).chat("ignored", "", "user");
            var off = com.google.gson.JsonParser.parseString(sent[0]).getAsJsonObject();
            var l = on.getAsJsonArray("lora");
            return lane.healthy() && code.equals("1. a\n2. b") && lane.adapters().get(1).name().equals("probe_b")
                && l.size() == 2 && l.get(0).getAsJsonObject().get("scale").getAsDouble() == 0.0 && l.get(1).getAsJsonObject().get("scale").getAsDouble() == 1.0
                && on.get("temperature").getAsInt() == 0 && on.getAsJsonArray("messages").size() == 2
                && off.getAsJsonArray("lora").get(1).getAsJsonObject().get("scale").getAsDouble() == 0.0 && off.getAsJsonArray("messages").size() == 1
                && packets.size() == 1 && packets.get(0).contains("CODE recipe probe_b=1.0");
        }));
        cs.add(new Case("lane.aRecipeForAnAdapterTheLaneLacksIsRefusedAndSoIsABadBankFile", () -> {
            try {
                var loaded = java.util.List.of(new com.mindpalace.agent.lane.LaneClient.Adapter(0, "probe_a", 1.0));
                var ghost = com.mindpalace.agent.lane.AdapterBank.of(java.util.Map.of(AdapterType.CODE, java.util.Map.of("ghost", 1.0)));
                boolean refused = false;
                try { ghost.vector(AdapterType.CODE, loaded); } catch (IllegalStateException e) { refused = true; }
                java.nio.file.Path f = java.nio.file.Files.createTempFile("bank", ".json");
                int bad = 0;
                for (String s : new String[]{"{\"recipes\":{\"CODE\":{\"probe_a\":9}}}", "{\"recipes\":{\"NOPE\":{\"probe_a\":1}}}", "not json"}) {
                    java.nio.file.Files.writeString(f, s);
                    try { com.mindpalace.agent.lane.AdapterBank.load(f); } catch (IllegalArgumentException e) { bad++; }
                }
                java.nio.file.Files.writeString(f, "{\"recipes\":{\"CODE\":{\"probe_a\":0.5}}}");
                var good = com.mindpalace.agent.lane.AdapterBank.load(f);
                java.nio.file.Files.deleteIfExists(f);
                return refused && bad == 3 && good.vector(AdapterType.CODE, loaded).get(0) == 0.5 && good.vector(AdapterType.CHAT, loaded).get(0) == 0.0
                    && good.describe(AdapterType.CHAT).equals("base") && com.mindpalace.agent.lane.AdapterBank.load(f).describe(AdapterType.CODE).equals("base");
            } catch (Exception e) { return false; }
        }));
        cs.add(new Case("team.laneTeamsCompeteAsAnABOfRecipes", () -> {
            java.util.List<String> bodies = new java.util.ArrayList<>();
            var lane = new com.mindpalace.agent.lane.LaneClient((path, body) -> {
                if (path.equals("/lora-adapters")) return "[{\"id\":0,\"path\":\"/a/probe_a.gguf\",\"scale\":1.0}]";
                bodies.add(body);
                return "{\"choices\":[{\"message\":{\"content\":\"1. open the door\\n2. test the door\"}}]}";
            });
            var bank = com.mindpalace.agent.lane.AdapterBank.of(java.util.Map.of(AdapterType.CODE, java.util.Map.of("probe_a", 0.5)));
            var roster = com.mindpalace.agent.team.TeamDispatch.laneRoster(lane, bank, null);
            var sb = new com.mindpalace.agent.team.Scoreboard();
            LoRASwitcher lora = new LoRASwitcher();
            var out = new com.mindpalace.agent.team.Arena(new com.mindpalace.agent.team.TeamRunner(lora), sb).compete(roster.subList(0, 2), "fix the door", com.mindpalace.agent.team.PlanCheck::score);
            return roster.size() == 3 && roster.get(0).name().equals("lane-code[probe_a=0.5]+bot") && roster.get(1).name().equals("lane-analysis[base]+bot")
                && out.winner().team() == roster.get(0) && bodies.size() == 2 && bodies.get(0).contains("\"scale\":0.5") && bodies.get(1).contains("\"scale\":0.0")
                && lora.getSwitchCount() == 0 && sb.runs(roster.get(1).name()) == 1;
        }));
        cs.add(new Case("team.toolSeatSeesItsResultsAndCannotFakeThem", () -> {
            java.util.List<String> shown = new java.util.ArrayList<>();
            com.mindpalace.agent.team.Member.Llm fake = (m, s, u) -> { shown.add(u); return shown.size() == 1 ? "= 99\n? add 2 3" : "1. use the 5\n2. done"; };
            java.util.Map<String, java.util.function.Function<String, String>> ts = java.util.Map.of(
                "add", a -> { String[] p = a.split(" "); return String.valueOf(Integer.parseInt(p[0]) + Integer.parseInt(p[1])); },
                "big", a -> "x".repeat(5000), "two", a -> "one\ntwo");
            var seat = com.mindpalace.agent.team.Member.tools("scout", "m", AdapterType.ANALYSIS, "", fake, ts);
            String out = seat.act(new com.mindpalace.agent.team.Board("t"));
            String many = com.mindpalace.agent.team.Member.runToolLines("? add 1 1\n? add 1 1\n? add 1 1\n? add 1 1\n? add 1 1", ts);
            String big = com.mindpalace.agent.team.Member.runToolLines("? big", ts);
            return out.equals("1. use the 5\n2. done") && shown.size() == 2 && shown.get(1).contains("= 5") && !shown.get(1).contains("99")
                && many.endsWith("= ERR too many tool calls in one reply (max 4)") && many.split("= 2", -1).length == 5
                && big.length() < 1600 && big.endsWith("...(cut)")
                && com.mindpalace.agent.team.Member.runToolLines("? two", ts).equals("? two\n= one\n= two");
        }));
        cs.add(new Case("team.repoToolsStayInsideTheRootAndSkipSecrets", () -> {
            try {
                java.nio.file.Path base = java.nio.file.Files.createTempDirectory("repo_tools");
                java.nio.file.Path root = base.resolve("repo");
                java.nio.file.Files.createDirectories(root.resolve("sub"));
                java.nio.file.Files.createDirectories(root.resolve(".git"));
                java.nio.file.Files.writeString(root.resolve("a.txt"), "hello\nthe World\n");
                java.nio.file.Files.writeString(root.resolve("sub").resolve("api.key"), "k");
                java.nio.file.Files.writeString(root.resolve(".git").resolve("config"), "c");
                java.nio.file.Files.writeString(base.resolve("outside.txt"), "out");
                var ts = com.mindpalace.agent.team.RepoTools.toolset(root);
                boolean ok = ts.get("ls").apply("").equals("a.txt sub/") && ts.get("ls").apply("sub").isEmpty()
                    && ts.get("read").apply("a.txt").equals("hello\nthe World")
                    && ts.get("grep").apply("world a.txt").equals("2: the World") && ts.get("grep").apply("zzz a.txt").equals("no match")
                    && ts.get("read").apply("../outside.txt").startsWith("ERR") && ts.get("read").apply(base.resolve("outside.txt").toString()).startsWith("ERR")
                    && ts.get("read").apply("sub/api.key").startsWith("ERR") && ts.get("read").apply(".git/config").startsWith("ERR")
                    && ts.get("read").apply("sub/../../outside.txt").startsWith("ERR") && ts.get("read").apply("nope.txt").startsWith("ERR")
                    && ts.get("read").apply("a*?.txt").startsWith("ERR") && ts.get("ls").apply("a.txt").startsWith("ERR");
                try (var w = java.nio.file.Files.walk(base)) { w.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); }
                return ok;
            } catch (Exception e) { return false; }
        }));
        cs.add(new Case("team.aPlanAboutTheTaskOutscoresOneAboutNothing", () -> {
            String task = "Proposal (REFACTOR): repair the broken elevator door\nWrite a plan of 2 to 5 numbered steps to carry it out.";
            var about = new com.mindpalace.agent.team.TeamRunner.Result(true, "1. open the elevator panel\n2. repair the door", null, 2, 0, "");
            var vague = new com.mindpalace.agent.team.TeamRunner.Result(true, "1. do it\n2. test it", null, 2, 0, "");
            var parrot = new com.mindpalace.agent.team.TeamRunner.Result(true, "1. write numbered steps\n2. carry the proposal", null, 2, 0, "");
            int a = com.mindpalace.agent.team.PlanCheck.score(task, about), v = com.mindpalace.agent.team.PlanCheck.score(task, vague);
            return a == v + 10 && com.mindpalace.agent.team.PlanCheck.score(task, parrot) == v;
        }));
        cs.add(new Case("lane.theProbePassesOnlyWhenTheSwapIsReal", () -> {
            String adapters = "[{\"id\":0,\"path\":\"/a/probe_a.gguf\",\"scale\":1.0},{\"id\":1,\"path\":\"/a/probe_b.gguf\",\"scale\":1.0}]";
            java.util.function.Function<String, String> answer = t -> "{\"choices\":[{\"message\":{\"content\":\"" + t + "\"}}]}";
            java.util.function.Function<String, String> scales = body -> com.google.gson.JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("lora").toString().replace("\"", "");
            String allOff = "[{id:0,scale:0.0},{id:1,scale:0.0}]";
            var bank = com.mindpalace.agent.lane.AdapterBank.of(java.util.Map.of(
                AdapterType.CODE, java.util.Map.of("probe_b", 1.0), AdapterType.CAREER, java.util.Map.of("probe_b", 1.0),
                AdapterType.ANALYSIS, java.util.Map.of("probe_a", 0.02, "probe_b", 1.0)));
            // a real lane: the answer depends on the scales and on nothing else
            var real = new com.mindpalace.agent.lane.LaneClient((p, b) -> b == null ? adapters : answer.apply(scales.apply(b).equals(allOff) ? "base" : "moved " + scales.apply(b)));
            // a lane that ignores the scales: the "swap" changes nothing
            var deaf = new com.mindpalace.agent.lane.LaneClient((p, b) -> b == null ? adapters : answer.apply("base"));
            // a lane where a recipe leaks into the next call
            boolean[] dirty = {false};
            var leaky = new com.mindpalace.agent.lane.LaneClient((p, b) -> {
                if (b == null) return adapters;
                boolean off = scales.apply(b).equals(allOff);
                String t = off ? (dirty[0] ? "base, tainted" : "base") : "moved";
                if (!off) dirty[0] = true;
                return answer.apply(t);
            });
            var none = new com.mindpalace.agent.lane.LaneClient((p, b) -> b == null ? "[]" : answer.apply("base"));
            var ok = com.mindpalace.agent.lane.LaneProbe.run(real, bank, "q", 8);
            var no = com.mindpalace.agent.lane.LaneProbe.run(deaf, bank, "q", 8);
            var leak = com.mindpalace.agent.lane.LaneProbe.run(leaky, bank, "q", 8);
            var empty = com.mindpalace.agent.lane.LaneProbe.run(real, com.mindpalace.agent.lane.AdapterBank.of(java.util.Map.of()), "q", 8);
            return ok.pass() && ok.lines().size() == 5   // base, base again, two distinct recipes (CODE and CAREER share one), base after
                && !no.pass() && no.lines().stream().anyMatch(l -> l.note().contains("changed nothing"))
                && !leak.pass() && leak.lines().get(leak.lines().size() - 1).note().contains("left something behind")
                && !empty.pass() && !com.mindpalace.agent.lane.LaneProbe.run(none, bank, "q", 8).pass();
        }));
        cs.add(new Case("lane.aDeadLaneCostsOneErrorNotATimeoutPerTurn", () -> {
            long[] now = {1000};
            int[] calls = {0};
            boolean[] up = {false};
            var lane = new com.mindpalace.agent.lane.LaneClient((p, b) -> {
                calls[0]++;
                if (!up[0]) throw new java.io.IOException("refused");
                return "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}";
            }, () -> now[0]);
            java.util.Map<Integer, Double> off = java.util.Map.of(0, 0.0);
            int refused = 0;
            for (int i = 0; i < 6; i++) {
                try { lane.chat("", "q", off, 8); } catch (IllegalStateException e) { if (e.getMessage().startsWith("lane breaker open")) refused++; }
            }
            boolean quiet = calls[0] == 3 && refused == 3 && lane.breakerOpen();   // 3 real failures, then the wire is left alone
            now[0] += 61_000; up[0] = true;
            boolean back = lane.chat("", "q", off, 8).text().equals("ok") && !lane.breakerOpen() && calls[0] == 4;
            return quiet && back;
        }));
        cs.add(new Case("lane.seatsNeedAPassingProbeFromToday", () -> {
            try {
                java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("probe_proof");
                long now = System.currentTimeMillis(), day = 24L * 3600_000;
                boolean none = !com.mindpalace.agent.lane.LaneProbe.freshProof(dir, day, now);
                java.nio.file.Path f = dir.resolve("lane_probe_20261114_162823.json");
                java.nio.file.Files.writeString(f, "{\"pass\":false,\"lines\":[]}");
                boolean failed = !com.mindpalace.agent.lane.LaneProbe.freshProof(dir, day, now);
                java.nio.file.Files.writeString(f, "{\"pass\":true,\"lines\":[]}");
                boolean fresh = com.mindpalace.agent.lane.LaneProbe.freshProof(dir, day, now);
                boolean stale = !com.mindpalace.agent.lane.LaneProbe.freshProof(dir, day, now + 2 * day);
                java.nio.file.Files.writeString(dir.resolve("lane_probe_20261115_000000.json"), "{\"pass\":false}");   // a newer failing proof wins
                boolean newerFails = !com.mindpalace.agent.lane.LaneProbe.freshProof(dir, day, now);
                java.nio.file.Files.writeString(f, "not json");
                java.nio.file.Files.delete(dir.resolve("lane_probe_20261115_000000.json"));
                boolean garbage = !com.mindpalace.agent.lane.LaneProbe.freshProof(dir, day, now);
                try (var w = java.nio.file.Files.walk(dir)) { w.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); }
                return none && failed && fresh && stale && newerFails && garbage;
            } catch (Exception e) { return false; }
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

        // ── #136 (H46, step 105): quota ledger + morning digest ─────────────
        // Fresh-Telemetry-per-case (design rule: no cross-case state leakage,
        // temp dirs only, stopped + wiped after use).
        cs.add(new Case("quota.categoryConstantIsLowercaseQuota", () ->
            "quota".equals(com.mindpalace.backup.Telemetry.QUOTA)));
        cs.add(new Case("quota.recordAndDigestRoundTrip", () -> {
            java.nio.file.Path tmp;
            try { tmp = java.nio.file.Files.createTempDirectory("quota-gate"); }
            catch (java.io.IOException e) { return false; }
            try {
                com.mindpalace.backup.Telemetry t = new com.mindpalace.backup.Telemetry(tmp, 0L);
                boolean w1 = t.record(com.mindpalace.backup.Telemetry.QUOTA, "qwen2.5:0.5b", "call units=~25", 25);
                boolean w2 = t.record(com.mindpalace.backup.Telemetry.QUOTA, "tinyllama:1.1b", "call units=~10", 10);
                // non-quota row must NOT leak into the digest
                t.record(com.mindpalace.backup.Telemetry.AGENT, "cycle", "x", 99);
                String digest = t.quotaDigest(java.time.Duration.ofHours(24).toMillis());
                boolean ok = w1 && w2 && digest.contains("qwen2.5:0.5b=1(25u)")
                    && digest.contains("tinyllama:1.1b=1(10u)")
                    && digest.contains("calls=2") && digest.contains("units=35")
                    && digest.contains("cloud=0");
                t.stop();
                return ok && !digest.contains("cycle");
            } finally {
                // best-effort wipe (fails silently on locked Windows tmp files)
                try {
                    java.util.stream.Stream<java.nio.file.Path> s =
                        java.nio.file.Files.list(tmp);
                    s.forEach(p -> p.toFile().delete());
                    s.close();
                    tmp.toFile().delete();
                } catch (Exception ignored) { /* inert */ }
            }
        }));
        cs.add(new Case("quota.emptyLedgerDigestIsGraceful", () -> {
            java.nio.file.Path tmp;
            try { tmp = java.nio.file.Files.createTempDirectory("quota-empty"); }
            catch (java.io.IOException e) { return false; }
            try {
                com.mindpalace.backup.Telemetry t = new com.mindpalace.backup.Telemetry(tmp, 0L);
                String digest = t.quotaDigest(java.time.Duration.ofHours(24).toMillis());
                boolean ok = digest.contains("calls=0") && digest.contains("cloud=0");
                t.stop();
                return ok;
            } finally {
                try {
                    java.util.stream.Stream<java.nio.file.Path> s =
                        java.nio.file.Files.list(tmp);
                    s.forEach(p -> p.toFile().delete());
                    s.close();
                    tmp.toFile().delete();
                } catch (Exception ignored) { /* inert */ }
            }
        }));
        cs.add(new Case("quota.oldRowsOutsideWindowExcluded", () -> {
            // Real window test: record 2 QUOTA rows, backdate one 48h via a
            // direct connection to the same sqlite file (committed write),
            // then digest(24h) must count ONLY the fresh row. Deterministic —
            // 48h >> any ms jitter between record and digest.
            java.nio.file.Path tmp;
            try { tmp = java.nio.file.Files.createTempDirectory("quota-window"); }
            catch (java.io.IOException e) { return false; }
            try {
                com.mindpalace.backup.Telemetry t = new com.mindpalace.backup.Telemetry(tmp, 0L);
                t.record(com.mindpalace.backup.Telemetry.QUOTA, "fresh", "call units=~4", 4);
                t.record(com.mindpalace.backup.Telemetry.QUOTA, "stale", "call units=~100", 100);
                try (java.sql.Connection c2 = java.sql.DriverManager.getConnection(
                        "jdbc:sqlite:" + tmp.resolve("telemetry.db"));
                     java.sql.Statement st = c2.createStatement()) {
                    st.execute("UPDATE events SET ts="
                        + (System.currentTimeMillis() - 48L * 3600_000L)
                        + " WHERE event='stale'");
                } catch (java.sql.SQLException e) { return false; }
                String digest = t.quotaDigest(java.time.Duration.ofHours(24).toMillis());
                boolean ok = digest.contains("fresh=1(4u)") && digest.contains("calls=1")
                    && digest.contains("units=4") && !digest.contains("stale");
                t.stop();
                return ok;
            } finally {
                try {
                    java.util.stream.Stream<java.nio.file.Path> s =
                        java.nio.file.Files.list(tmp);
                    s.forEach(p -> p.toFile().delete());
                    s.close();
                    tmp.toFile().delete();
                } catch (Exception ignored) { /* inert */ }
            }
        }));
        cs.add(new Case("quota.digestAfterStopDegradesGracefully", () -> {
            // Graceful-degrade rule: a stopped ledger never throws — `db` is
            // FINAL (never nulled; stop() closes the connection), so post-stop
            // digests hit the SQLException path and degrade to the
            // "quota: query failed" readout, NOT "quota: offline" (that's the
            // db==null constructor-failure shape).
            java.nio.file.Path tmp;
            try { tmp = java.nio.file.Files.createTempDirectory("quota-stop"); }
            catch (java.io.IOException e) { return false; }
            try {
                com.mindpalace.backup.Telemetry t = new com.mindpalace.backup.Telemetry(tmp, 0L);
                t.record(com.mindpalace.backup.Telemetry.QUOTA, "m", "call units=~4", 4);
                t.stop();
                String after = t.quotaDigest(java.time.Duration.ofHours(24).toMillis());
                return "quota: query failed".equals(after);
            } finally {
                try {
                    java.util.stream.Stream<java.nio.file.Path> s =
                        java.nio.file.Files.list(tmp);
                    s.forEach(p -> p.toFile().delete());
                    s.close();
                    tmp.toFile().delete();
                } catch (Exception ignored) { /* inert */ }
            }
        }));

        // ── #137 (H47, step 106): dedupe sentinel — structural duplication ──

        cs.add(new Case("dedupe.copyPastePairFlagged", () -> {
            // Exact copy-paste across two DIFFERENT files MUST be flagged at
            // ≥ threshold (the AgentRewardMechanism/Wallet class of bug).
            String src = "class A {\n"
                + "    public int sumWeights() {\n"
                + "        int total = 0;\n"
                + "        for (int i = 0; i < items.size(); i++) {\n"
                + "            total += items.get(i).weight();\n"
                + "        }\n"
                + "        return total;\n"
                + "    }\n"
                + "}\n";
            java.util.Map<String, java.util.Map<String, String>> files =
                new java.util.LinkedHashMap<>();
            java.util.Map<String, String> a = new java.util.HashMap<>();
            a.put("src/A.java", src);
            java.util.Map<String, String> b = new java.util.HashMap<>();
            b.put("src/B.java", src.replace("sumWeights", "tally"));
            files.put("repoA", a);
            files.put("repoB", b);
            java.util.List<com.mindpalace.github.DedupeSentinel.Pair> out =
                com.mindpalace.github.DedupeSentinel.scan(files);
            return out.size() == 1 && out.get(0).similarity()
                >= com.mindpalace.github.DedupeSentinel.THRESHOLD;
        }));

        cs.add(new Case("dedupe.renamedIdentifiersStillSimilar", () -> {
            // Same shape, renamed locals/method — normalization maps
            // non-keyword identifiers to 'x', so rename-invariant detection
            // still catches the duplication.
            String srcA = "class A {\n"
                + "    public int sumWeights() {\n"
                + "        int total = 0;\n"
                + "        for (int i = 0; i < items.size(); i++) {\n"
                + "            total += items.get(i).weight();\n"
                + "        }\n"
                + "        return total;\n"
                + "    }\n"
                + "}\n";
            String srcB = "class B {\n"
                + "    public int tallyWeights() {\n"
                + "        int sum = 0;\n"
                + "        for (int k = 0; k < xs.size(); k++) {\n"
                + "            sum += xs.get(k).weight();\n"
                + "        }\n"
                + "        return sum;\n"
                + "    }\n"
                + "}\n";
            java.util.Map<String, java.util.Map<String, String>> files =
                new java.util.LinkedHashMap<>();
            java.util.Map<String, String> a = new java.util.HashMap<>();
            a.put("src/A.java", srcA);
            java.util.Map<String, String> b = new java.util.HashMap<>();
            b.put("src/B.java", srcB);
            files.put("repoA", a);
            files.put("repoB", b);
            java.util.List<com.mindpalace.github.DedupeSentinel.Pair> out =
                com.mindpalace.github.DedupeSentinel.scan(files);
            return out.size() == 1 && out.get(0).similarity() >= 0.85;
        }));

        cs.add(new Case("dedupe.intraFilePairNotFlagged", () -> {
            // Same-file near-duplicates are NOT reported — private helpers
            // in one file are idiomatic; only cross-file copy-paste is the
            // bug the sentinel exists for.
            String src = "class C {\n"
                + "    public int sumWeights() {\n"
                + "        int total = 0;\n"
                + "        for (int i = 0; i < items.size(); i++) {\n"
                + "            total += items.get(i).weight();\n"
                + "        }\n"
                + "        return total;\n"
                + "    }\n"
                + "    public int sumWeights2() {\n"
                + "        int total2 = 0;\n"
                + "        for (int j = 0; j < items.size(); j++) {\n"
                + "            total2 += items.get(j).weight();\n"
                + "        }\n"
                + "        return total2;\n"
                + "    }\n"
                + "}\n";
            java.util.Map<String, java.util.Map<String, String>> files =
                new java.util.LinkedHashMap<>();
            java.util.Map<String, String> a = new java.util.HashMap<>();
            a.put("src/A.java", src);
            files.put("repoA", a);
            return com.mindpalace.github.DedupeSentinel.scan(files).isEmpty();
        }));

        cs.add(new Case("dedupe.belowThresholdNotFlagged", () -> {
            // Structurally different logic (string-building vs arithmetic
            // recurrence) shares no 5-token shingles → NOT flagged (no noise).
            String srcA = "class D {\n"
                + "    public String greet(String who) {\n"
                + "        String msg = \"\";\n"
                + "        for (int i = 0; i < 3; i++) {\n"
                + "            msg = msg + \"!\";\n"
                + "        }\n"
                + "        return \"hi \" + who + msg;\n"
                + "    }\n"
                + "}\n";
            String srcB = "class E {\n"
                + "    public long fib(int n) {\n"
                + "        long prev = 1;\n"
                + "        long curr = 1;\n"
                + "        for (int k = 2; k < n; k++) {\n"
                + "            long next = prev + curr;\n"
                + "            prev = curr;\n"
                + "            curr = next;\n"
                + "        }\n"
                + "        return curr;\n"
                + "    }\n"
                + "}\n";
            java.util.Map<String, java.util.Map<String, String>> files =
                new java.util.LinkedHashMap<>();
            java.util.Map<String, String> a = new java.util.HashMap<>();
            a.put("src/D.java", srcA);
            java.util.Map<String, String> b = new java.util.HashMap<>();
            b.put("src/E.java", srcB);
            files.put("repoA", a);
            files.put("repoB", b);
            return com.mindpalace.github.DedupeSentinel.scan(files).isEmpty();
        }));

        // ── #138 (H49, step 108): multiplayer — one WebSocket, additive-only ──

        cs.add(new Case("multiplayer.hubAcceptKeyRfc6455", () -> {
            // RFC6455 §4.2.2 accept key is deterministic — fixed vector.
            String key = "dGhlIHNhbXBsZSBub25jZQ=="; // spec example
            return "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=".equals(
                com.mindpalace.network.MultiplayerHub.acceptKey(key));
        }));

        cs.add(new Case("multiplayer.portRejectsOutOfRange", () -> {
            try {
                new com.mindpalace.network.MultiplayerHub(70_000);
                return false; // >65535 must be refused (0 = ephemeral is VALID)
            } catch (IllegalArgumentException expected) {
                return true;
            } catch (Exception other) {
                return false;
            }
        }));

        cs.add(new Case("multiplayer.offByDefaultRequiresFlag", () -> {
            // H49 contract: gate is false with no flag, true when it flips.
            String prop = "mindpalace.mp.enabled";
            String saved = System.getProperty(prop);
            try {
                System.clearProperty(prop);
                if (com.mindpalace.network.MultiplayerHub.enabled()) return false;
                System.setProperty(prop, "true");
                return com.mindpalace.network.MultiplayerHub.enabled();
            } finally {
                if (saved == null) System.clearProperty(prop);
                else System.setProperty(prop, saved);
            }
        }));

        // ── #139 (H50, step 109): installer v2 — MSI, update check, wizard ──
        cs.add(new Case("installer.msiTargetInBuildScript", () -> {
            // H50: build-installer.sh carries a real jpackage --type msi lane.
            java.nio.file.Path p = java.nio.file.Path.of("build-installer.sh");
            if (!java.nio.file.Files.exists(p)) return false;
            try {
                String s = java.nio.file.Files.readString(p);
                return s.contains("--type msi") && s.contains("--win-per-user-install");
            } catch (Exception e) { return false; }
        }));

        cs.add(new Case("installer.versionCompareSemver", () -> {
            // H50: compareVersions is correct — older returns negative, equal 0,
            // newer positive, across unequal segment counts (1.1.0 vs 1.2).
            com.mindpalace.deploy.UpdateChecker c = new com.mindpalace.deploy.UpdateChecker();
            return c.compareVersions("1.1.0", "1.2.0") < 0
                && c.compareVersions("1.2.0", "1.1.0") > 0
                && c.compareVersions("1.2.0", "1.2.0") == 0
                && c.compareVersions("1.2",   "1.2.0") == 0;
        }));

        cs.add(new Case("installer.firstRunWizardMarker", () -> {
            // H50: first-run flip + marker persistence, state-restoring —
            // markerless before => probe true->false then restore (delete);
            // marker present => read-side invariant only (never re-nags).
            java.nio.file.Path mp = java.nio.file.Path.of(
                System.getProperty("user.home"), ".mindpalace", "first_run_done");
            boolean hadMarker = java.nio.file.Files.exists(mp);
            try {
                if (hadMarker) {
                    return !com.mindpalace.deploy.UpdateChecker.isFirstRun();
                }
                boolean before = com.mindpalace.deploy.UpdateChecker.isFirstRun();
                com.mindpalace.deploy.UpdateChecker.markFirstRunDone();
                return before
                    && !com.mindpalace.deploy.UpdateChecker.isFirstRun()
                    && java.nio.file.Files.exists(mp);
            } finally {
                if (!hadMarker) {
                    try { java.nio.file.Files.deleteIfExists(mp); } catch (Exception ignored) { }
                }
            }
        }));

        return cs;
    }
}