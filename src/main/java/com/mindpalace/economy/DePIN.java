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

        Participant(String name, double initial, EconomyLedger ledger) {
            this.name = name;
            this.wallet = new Wallet(name, initial, ledger);
        }

        /** Skill tier derived from completed jobs (1..5). */
        public int tier() {
            int s = skill.get();
            return s >= 20 ? 5 : s >= 12 ? 4 : s >= 6 ? 3 : s >= 2 ? 2 : 1;
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
