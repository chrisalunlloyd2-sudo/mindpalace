package com.mindpalace.economy;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DePIN — the decentralized economy coordinator (Phase 5.3).
 *
 * Wires wallets + blackboard together into a closed loop:
 *
 *   job posted (bounty) → agent claims → agent completes → bounty paid to
 *   agent's wallet → agent spends to "upgrade" (skill level up) → higher
 *   skill unlocks higher-bounty jobs.
 *
 * "Skill = success": each completed job raises the agent's skill, which lets
 * it claim harder, higher-paying work. The player has a wallet too, so the
 * economy is legible from both sides of the glass.
 */
public class DePIN {
    /** A participant's economy state. */
    public static final class Participant {
        public final String name;
        public final Wallet wallet;
        public final AtomicInteger skill = new AtomicInteger(0);
        public int completed = 0;
        /** Epoch-ms of this participant's last completed job (0 = never completed). */
        public volatile long lastCompletedAt = 0;
        /** Epoch-ms decay was last applied (idempotence anchor for #131). */
        public volatile long lastDecayAt = 0;

        Participant(String name, double initial, EconomyLedger ledger) {
            this.name = name;
            this.wallet = new Wallet(name, initial, ledger);
        }

        /** Skill tier derived from completed jobs (1..5). */
        public int tier() {
            int s = skill.get();
            return s >= 20 ? 5 : s >= 12 ? 4 : s >= 6 ? 3 : s >= 2 ? 2 : 1;
        }

        /** #131 (H40): drop skill by the idle-derived amount (never below 0).
         *  IDEMPOTENT: skill follows the envelope f(t)=idleSkillDecay(t-
         *  lastCompletedAt); we remove only the DELTA since lastDecayAt, so
         *  repeated ticks at the same instant are no-ops. A fresh completion
         *  resets the clock (skill regrows via job completion as designed). */
        void applyIdleDecay(long nowMs) {
            if (lastCompletedAt == 0) return;   // never completed: skill is 0 already
            long anchor = Math.max(lastCompletedAt, lastDecayAt);
            int drop = idleSkillDecay(nowMs - lastCompletedAt)
                     - idleSkillDecay(anchor - lastCompletedAt);
            if (drop > 0 && nowMs >= anchor) {
                int cur = skill.get();
                int next = Math.max(0, cur - drop);
                if (next < cur) skill.compareAndSet(cur, next);
            }
            if (nowMs > lastDecayAt) lastDecayAt = nowMs;
        }
    }

    // ── #131 (H40): idle skill decay — skill is CURRENT capability, not a
    // high-water mark ──
    // Without this, an agent that stops completing jobs keeps claim-rights to
    // high-difficulty jobs forever (claim()'s skill gate stays wedged open).
    /** Epoch-ms of the participant's last completed job (0 = never). Kept on
     *  the owning Participant itself; DePIN walks its map on each tick. */
    public static final long IDLE_DECAY_AFTER_MS = 24L * 60 * 60 * 1000;
    /** skill points removed per full idle period past the threshold. */
    public static final int IDLE_DECAY_PER_PERIOD = 1;

    /** skill points to remove after `idleMs` of no completed jobs. Pure —
     *  kept beside tier() so the decay constants and tier bands stay in sync. */
    public static int idleSkillDecay(long idleMs) {
        if (idleMs <= IDLE_DECAY_AFTER_MS) return 0;
        long periods = (idleMs - IDLE_DECAY_AFTER_MS) / IDLE_DECAY_AFTER_MS;
        return (int) Math.min(periods * IDLE_DECAY_PER_PERIOD, Integer.MAX_VALUE);
    }

    /** Re-apply idle decay to every participant. Idempotent (decay is derived
     *  from lastCompletedAt, so double-ticks never over-decay) and cheap: no
     *  work when nothing crosses the threshold. Called from the agent cycle. */
    public synchronized void applyIdleDecay(long nowMs) {
        for (Participant p : participants.values()) {
            if (p.lastCompletedAt == 0) continue;   // never completed: skill is 0 already
            p.applyIdleDecay(nowMs);
        }
    }

    private final EconomyLedger ledger = new EconomyLedger();
    private final Treasury treasury;
    private final Escrow escrow = new Escrow(ledger);
    private final Blackboard board = new Blackboard();
    private final Map<String, Participant> participants = new LinkedHashMap<>();
    private final Map<Long, String> claims = new HashMap<>();  // jobId -> claimant

    public DePIN() {
        this(Treasury.configuredInitialBalance());
    }

    public DePIN(double initialTreasury) {
        treasury = new Treasury(ledger, initialTreasury);
        register("player", 100.0);
    }

    public Blackboard board() { return board; }
    public EconomyLedger ledger() { return ledger; }
    public Treasury treasury() { return treasury; }
    public Escrow escrow() { return escrow; }

    /** Register a participant (agent or player). Idempotent. */
    public synchronized Participant register(String name, double initial) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Participant name must not be blank");
        }
        return participants.computeIfAbsent(name, n -> new Participant(n, initial, ledger));
    }

    public synchronized Participant participant(String name) { return participants.get(name); }
    public synchronized Collection<Participant> participants() { return participants.values(); }

    /** Post a job with a bounty; source pays the bounty upfront into escrow. */
    public synchronized Blackboard.Job post(String title, String topic, double bounty, int difficulty) {
        return board.post(title, topic, bounty, difficulty,
            jobId -> escrow.reserve(jobId, bounty));
    }

    /** Agent claims an open job it is skilled enough for. */
    public synchronized boolean claim(long jobId, String agent) {
        Participant p = participants.get(agent);
        Blackboard.Job j = board.get(jobId);
        if (p == null || j == null) return false;
        if (j.difficulty > p.tier()) return false;   // skill gate
        synchronized (board) {
            if (j.status != Blackboard.JobStatus.OPEN || escrow.held(jobId) <= 0) return false;
            if (!board.claim(jobId, agent)) return false;
            claims.put(jobId, agent);
            return true;
        }
    }

    /** Complete a claimed job: pay bounty, raise skill. Returns payout. */
    public synchronized double complete(long jobId) {
        String agent = claims.get(jobId);
        Blackboard.Job j = board.get(jobId);
        if (j == null || agent == null || j.status != Blackboard.JobStatus.CLAIMED) return 0.0;
        Participant p = participants.get(agent);
        if (p == null) return 0.0;
        synchronized (board) {
            if (board.get(jobId) != j || j.status != Blackboard.JobStatus.CLAIMED) return 0.0;
            if (!escrow.release(jobId, p.wallet)) return 0.0;
            if (board.complete(jobId) == null) return 0.0;
            p.skill.incrementAndGet();
            p.completed++;
            p.lastCompletedAt = System.currentTimeMillis();  // #131 (H40) idle clock
            claims.remove(jobId);
            return j.bounty;
        }
    }

    /** Cancel an unclaimed job and return its reserved bounty to the treasury. */
    public synchronized boolean cancel(long jobId) {
        synchronized (board) {
            Blackboard.Job job = board.get(jobId);
            return job != null && job.status == Blackboard.JobStatus.OPEN
                && escrow.refund(jobId) && board.cancel(jobId);
        }
    }

    /** Agent spends credits on an upgrade (returns true on success). */
    public synchronized boolean spend(String agent, double amount, String reason) {
        Participant p = participants.get(agent);
        return p != null && p.wallet.spend(amount, reason);
    }

    // ── Reward categories (folded from the dead economics/AgentRewardMechanism, #97) ──
    // Reward reason codes carry what the agent did to earn: a plain job
    // completion stays "depin:<jobId>" via complete(); these categories cover
    // the event sites outside the job board.
    public static final String REASON_TOOL_ROUND = "tool_round";
    public static final String REASON_BUG_FIX = "bug_fix";
    public static final String REASON_GLITCH_FOUND = "glitch_found";
    public static final String REASON_PERF_IMPROVEMENT = "perf_improvement";
    public static final String REASON_FEATURE = "feature";

    /** Credit an agent directly for a reward-category event (no job board).
     *  Refuses unregistered participants so awards can't conjure accounts. */
    public synchronized boolean award(String agent, double amount, String reason) {
        Participant p = participants.get(agent);
        if (p == null || amount <= 0 || reason == null || reason.isBlank()) return false;
        return p.wallet.earn(amount, reason);
    }

    // ── #130 (H39): the compute tax — the spend side of the closed loop ──
    // DePIN pays OUT on job completion / tool rounds, but nothing paid IN for
    // the LLM calls that burn real wall-clock/GPU time. Every Ollama call now
    // debits the calling agent's wallet by chars/4 (rough token estimate).
    public static final String REASON_COMPUTE = "compute_tax";
    /** Cost multiplier: one credit per 4 chars of prompt+response (H39 step 99). */
    public static final double COMPUTE_CREDITS_PER_CHAR = 0.25;

    /** Compute-tax cost of a call: (promptChars + responseChars) × credits/char.
     *  Pure and null-safe so callers can compute BEFORE deciding to charge. */
    public static double computeTaxCost(String promptChars, String responseChars) {
        int len = (promptChars == null ? 0 : promptChars.length())
                + (responseChars == null ? 0 : responseChars.length());
        return len * COMPUTE_CREDITS_PER_CHAR;
    }

    /** Charge the calling agent for one Ollama call. No response (null/empty)
     *  → no charge (the "no refund needed since nothing was charged" rule).
     *  Unregistered agents are silently exempt (awards can't conjure accounts,
     *  taxes can't either). Returns the amount actually charged. */
    public synchronized double chargeCompute(String agent, String promptChars, String responseChars) {
        if (responseChars == null || responseChars.isEmpty()) return 0.0;
        Participant p = participants.get(agent);
        if (p == null) return 0.0;
        double cost = computeTaxCost(promptChars, responseChars);
        if (cost <= 0) return 0.0;
        // spend() fails silently when funds run dry — a broke agent keeps
        // working (compute is not a permission gate, it's an economy signal).
        return p.wallet.spend(cost, REASON_COMPUTE) ? cost : 0.0;
    }

    /** Seed the initial job board from a topic list (deterministic bounties). */
    public synchronized void seedJobs(List<String> topics) {
        int i = 0;
        for (String t : topics) {
            int diff = 1 + (i % 5);
            double bounty = 5.0 + diff * 5.0;
            post("Maintain " + t, t, bounty, diff);
            i++;
        }
    }
}
