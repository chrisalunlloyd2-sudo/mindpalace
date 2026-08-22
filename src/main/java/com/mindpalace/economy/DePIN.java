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

        Participant(String name, double initial) {
            this.name = name;
            this.wallet = new Wallet(name, initial);
        }

        /** Skill tier derived from completed jobs (1..5). */
        public int tier() {
            int s = skill.get();
            return s >= 20 ? 5 : s >= 12 ? 4 : s >= 6 ? 3 : s >= 2 ? 2 : 1;
        }
    }

    private final Blackboard board = new Blackboard();
    private final Map<String, Participant> participants = new LinkedHashMap<>();
    private final Map<Long, String> claims = new HashMap<>();  // jobId -> claimant

    public DePIN() {
        register("player", 100.0);
    }

    public Blackboard board() { return board; }

    /** Register a participant (agent or player). Idempotent. */
    public synchronized Participant register(String name, double initial) {
        return participants.computeIfAbsent(name, n -> new Participant(n, initial));
    }

    public synchronized Participant participant(String name) { return participants.get(name); }
    public synchronized Collection<Participant> participants() { return participants.values(); }

    /** Post a job with a bounty; source pays the bounty upfront into escrow. */
    public synchronized Blackboard.Job post(String title, String topic, double bounty, int difficulty) {
        return board.post(title, topic, bounty, difficulty);
    }

    /** Agent claims an open job it is skilled enough for. */
    public synchronized boolean claim(long jobId, String agent) {
        Participant p = participants.get(agent);
        Blackboard.Job j = board.get(jobId);
        if (p == null || j == null) return false;
        if (j.difficulty > p.tier()) return false;   // skill gate
        if (!board.claim(jobId, agent)) return false;
        claims.put(jobId, agent);
        return true;
    }

    /** Complete a claimed job: pay bounty, raise skill. Returns payout. */
    public synchronized double complete(long jobId) {
        String agent = claims.get(jobId);
        Blackboard.Job j = board.complete(jobId);
        if (j == null || agent == null) return 0.0;
        Participant p = participants.get(agent);
        if (p == null) return 0.0;
        p.wallet.earn(j.bounty, "job #" + jobId + " " + j.title);
        p.skill.incrementAndGet();
        p.completed++;
        claims.remove(jobId);
        return j.bounty;
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
            board.post("Maintain " + t, t, bounty, diff);
            i++;
        }
    }
}
