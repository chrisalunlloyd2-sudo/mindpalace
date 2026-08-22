package com.mindpalace.economy;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Blackboard — the shared job board of the DePIN economy.
 *
 * Phase 5.3: models get jobs + money. Agents post and claim jobs here. Each
 * job carries a bounty (credits) and a difficulty; completing it pays the
 * bounty to the claimant's wallet and feeds the "skill = success" loop. A
 * massive TOC tree indexes every job so the whole system is navigable by
 * topic, exactly like a blackboard of TODO slips pinned in a room.
 */
public class Blackboard {
    public enum JobStatus { OPEN, CLAIMED, DONE }

    /** A single job posting. */
    public static final class Job {
        public final long id;
        public final String title;
        public final String topic;     // TOC tree key (e.g. "agent/sims/teleporter")
        public final double bounty;
        public final int difficulty;   // 1..5
        public JobStatus status = JobStatus.OPEN;
        public String claimant = null;

        Job(long id, String title, String topic, double bounty, int difficulty) {
            this.id = id;
            this.title = title;
            this.topic = topic;
            this.bounty = bounty;
            this.difficulty = difficulty;
        }

        public boolean isOpen() { return status == JobStatus.OPEN; }
    }

    private final Map<Long, Job> jobs = new LinkedHashMap<>();
    private final Map<String, List<Long>> byTopic = new HashMap<>();
    private final AtomicLong nextId = new AtomicLong(1);

    /** Post a new job. Returns the job. */
    public synchronized Job post(String title, String topic, double bounty, int difficulty) {
        long id = nextId.getAndIncrement();
        Job j = new Job(id, title, topic, bounty, Math.max(1, Math.min(5, difficulty)));
        jobs.put(id, j);
        byTopic.computeIfAbsent(topic, k -> new ArrayList<>()).add(id);
        return j;
    }

    /** Claim an open job on behalf of an agent. Returns false if unavailable. */
    public synchronized boolean claim(long id, String agent) {
        Job j = jobs.get(id);
        if (j == null || !j.isOpen()) return false;
        j.status = JobStatus.CLAIMED;
        j.claimant = agent;
        return true;
    }

    /** Complete a claimed job (pays out via the caller). */
    public synchronized Job complete(long id) {
        Job j = jobs.get(id);
        if (j == null || j.status != JobStatus.CLAIMED) return null;
        j.status = JobStatus.DONE;
        return j;
    }

    public synchronized Job get(long id) { return jobs.get(id); }
    public synchronized List<Job> openJobs() {
        List<Job> out = new ArrayList<>();
        for (Job j : jobs.values()) if (j.isOpen()) out.add(j);
        return out;
    }
    public synchronized int openCount() { return openJobs().size(); }
    public synchronized int totalCount() { return jobs.size(); }

    /** All job IDs under a topic prefix (TOC tree navigation). */
    public synchronized List<Long> idsByTopicPrefix(String prefix) {
        List<Long> out = new ArrayList<>();
        for (Map.Entry<String, List<Long>> e : byTopic.entrySet())
            if (e.getKey().startsWith(prefix)) out.addAll(e.getValue());
        return out;
    }

    /** The TOC tree — topic hierarchy as a nested map (topic → sub-topics). */
    public synchronized Map<String, Object> tocTree() {
        Map<String, Object> root = new HashMap<>();
        for (String topic : byTopic.keySet()) {
            Map<String, Object> node = root;
            for (String seg : topic.split("/")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> next = (Map<String, Object>) node.computeIfAbsent(seg, k -> new HashMap<String, Object>());
                node = next;
            }
        }
        return root;
    }
}
