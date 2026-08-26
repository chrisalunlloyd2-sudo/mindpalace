package com.mindpalace.agent;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Global model scheduler — the single gate through which EVERY Ollama call
 * flows. Enforces the hard rules:
 *
 *   1. Autonomous calls: NEVER two models/nodes at once — one worker, serialized.
 *      User chat is the ONE exception: its own dedicated worker (submitImmediate),
 *      so a reply is never queued behind a slow tool/agent call.
 *   2. ONE chat every 5 minutes MAX — a spacing gate between calls.
 *   3. Slow-paced, low resources — no burst, no concurrency.
 *   4. Telemetry — every call is logged (model, latency, queue depth, drift).
 *
 * All agents (AgentManager, BehaviorTree) submit work here instead of calling
 * Ollama directly. The scheduler drains the queue one request at a time,
 * waiting MIN_SPACING_MS between calls so the local models never thrash.
 */
public class ModelScheduler {
    // Spacing between model calls — HARD FLOOR of 5 minutes. The user's rule:
    // "ONE CHAT EVERY 5 MINS MAX, SLOW-PACED, LOW RESOURCES." Idle detection can
    // widen this further (even slower when playing), but NEVER faster than 5 min.
    public static final long MIN_SPACING_MS = 5 * 60 * 1000; // 5 minutes

    private final OllamaClient ollama;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final BlockingQueue<Job> queue = new LinkedBlockingQueue<>();
    // Dedicated user-chat worker — exempt from the "one model at a time" rule.
    // A user reply must never serialize behind a 10-30s tool/agent call.
    private final ExecutorService userWorker = Executors.newSingleThreadExecutor();
    private final BlockingQueue<Job> userQueue = new LinkedBlockingQueue<>();

    // Dynamic spacing — widened when the user is actively playing (idle detector)
    private volatile long spacingMs = MIN_SPACING_MS;

    // ── Resource fencing ────────────────────────────────────────────────
    // Guards against the local models weighing down the system. Before each
    // call we check free heap + free disk; if either is below its floor, the
    // call is deferred (re-queued) until the system recovers. This is the
    // "memory/HDD fencing" — the models never starve the host.
    private static final long MIN_FREE_HEAP_BYTES = 64L * 1024 * 1024;   // 64 MB
    private static final long MIN_FREE_DISK_BYTES = 256L * 1024 * 1024;   // 256 MB
    private static final long FENCE_RECHECK_MS = 30_000;                 // 30s backoff
    private final AtomicLong fencedCount = new AtomicLong();
    private final AtomicLong lastFenceAt = new AtomicLong(0);

    // Telemetry
    private final AtomicLong totalCalls = new AtomicLong();
    private final AtomicLong totalLatencyMs = new AtomicLong();
    private final AtomicLong lastCallAt = new AtomicLong(0);
    private final AtomicInteger queueDepth = new AtomicInteger();
    private final AtomicLong driftEvents = new AtomicLong();
    private volatile String lastModel = "";
    private volatile String lastStatus = "idle";

    private static final class Job {
        final String model;
        final String prompt;
        final ModelLifespan lifespan; // may be null (stateless)
        final CompletableFuture<String> future;
        final boolean immediate;      // skip the 5-min spacing gate (user chat)
        final Runnable toolRound;     // raw tool-calling round (runs in drain loop)
        Job(String m, String p, ModelLifespan l, CompletableFuture<String> f, boolean imm) {
            model = m; prompt = p; lifespan = l; future = f; immediate = imm; toolRound = null;
        }
        Job(Runnable round) {
            model = null; prompt = null; lifespan = null; future = null; immediate = false; toolRound = round;
        }
    }

    public ModelScheduler(OllamaClient ollama) {
        this.ollama = ollama;
        worker.submit(this::drainLoop);
        userWorker.submit(this::drainImmediateLoop);
    }

    /** Submit a chat request. Returns a future that completes when it runs. */
    public CompletableFuture<String> submit(String model, String prompt, ModelLifespan lifespan) {
        return enqueue(model, prompt, lifespan, false);
    }

    /**
     * Submit a raw tool-calling round to run on the single worker thread (so it
     * never overlaps another model call). The Runnable is executed in the drain
     * loop; it should call ollama.chatWithTools itself and handle the result.
     */
    public void submitToolRound(Runnable round) {
        queue.add(new Job(round));
        queueDepth.set(queue.size());
    }

    /**
     * Submit a chat request that BYPASSES the 5-minute spacing gate. Used for
     * user-initiated chat so a reply arrives promptly instead of sitting in the
     * queue for up to 5 minutes (which made chat look dead). Runs on its OWN
     * dedicated userWorker thread — the ONE exception to "one model at a time",
     * so a reply is never queued behind a slow autonomous/tool call.
     */
    public CompletableFuture<String> submitImmediate(String model, String prompt, ModelLifespan lifespan) {
        CompletableFuture<String> f = new CompletableFuture<>();
        userQueue.add(new Job(model, prompt, lifespan, f, true));
        return f;
    }

    private CompletableFuture<String> enqueue(String model, String prompt, ModelLifespan lifespan, boolean immediate) {
        CompletableFuture<String> f = new CompletableFuture<>();
        queue.add(new Job(model, prompt, lifespan, f, immediate));
        queueDepth.set(queue.size());
        return f;
    }

    /**
     * User-chat drain loop — runs immediate jobs on their OWN worker thread so a
     * user reply is never queued behind a slow autonomous call. No 5-min spacing
     * (the user is here NOW), but still resource-fenced and still single-threaded
     * (one user reply at a time).
     */
    private void drainImmediateLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Job job = userQueue.take();
                if (!resourcesAvailable()) {
                    fencedCount.incrementAndGet();
                    lastFenceAt.set(System.currentTimeMillis());
                    lastStatus = "fenced (user chat)";
                    userQueue.add(job);          // re-queue for later
                    Thread.sleep(FENCE_RECHECK_MS);
                    continue;
                }
                lastModel = job.model;
                lastStatus = "chat " + job.model;
                long t0 = System.currentTimeMillis();
                String resp;
                if (job.lifespan != null) {
                    resp = job.lifespan.chat(job.prompt);
                } else {
                    resp = ollama.chat(job.model, job.prompt, "");
                }
                long latency = System.currentTimeMillis() - t0;
                totalCalls.incrementAndGet();
                totalLatencyMs.addAndGet(latency);
                lastCallAt.set(System.currentTimeMillis());
                lastStatus = "idle";
                job.future.complete(resp);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                lastStatus = "error: " + e.getMessage();
            }
        }
    }

    /** The single drain loop — one call at a time, spaced 5 min apart (unless immediate). */
    private void drainLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Job job = queue.take();
                queueDepth.set(queue.size());

                // Enforce spacing: wait until spacingMs since the last call.
                // Immediate jobs (user chat) skip this wait.
                long now = System.currentTimeMillis();
                long sinceLast = now - lastCallAt.get();
                if (!job.immediate && sinceLast < spacingMs) {
                    long wait = spacingMs - sinceLast;
                    lastStatus = "spacing " + (wait / 1000) + "s";
                    Thread.sleep(wait);
                }

                // Resource fence: if the system is low on heap or disk, defer the
                // call (re-queue it) and back off. The models never starve the host.
                if (!resourcesAvailable()) {
                    fencedCount.incrementAndGet();
                    lastFenceAt.set(System.currentTimeMillis());
                    lastStatus = "fenced (low resources)";
                    queue.add(job);          // re-queue for later
                    Thread.sleep(FENCE_RECHECK_MS);
                    continue;
                }

                // Tool-calling round: run the raw round directly (it manages its
                // own ollama.chatWithTools call + result handling).
                if (job.toolRound != null) {
                    lastStatus = "tool round";
                    long t0 = System.currentTimeMillis();
                    job.toolRound.run();
                    totalCalls.incrementAndGet();
                    totalLatencyMs.addAndGet(System.currentTimeMillis() - t0);
                    lastCallAt.set(System.currentTimeMillis());
                    lastStatus = "idle";
                    continue;
                }

                // Run the call (through lifespan if provided, else raw)
                lastModel = job.model;
                lastStatus = "running " + job.model;
                long t0 = System.currentTimeMillis();
                String resp;
                if (job.lifespan != null) {
                    resp = job.lifespan.chat(job.prompt);
                } else {
                    resp = ollama.chat(job.model, job.prompt, "");
                }
                long latency = System.currentTimeMillis() - t0;

                totalCalls.incrementAndGet();
                totalLatencyMs.addAndGet(latency);
                lastCallAt.set(System.currentTimeMillis());
                lastStatus = "idle";
                job.future.complete(resp);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                lastStatus = "error: " + e.getMessage();
            }
        }
    }

    // ── Telemetry getters ──
    public long getTotalCalls() { return totalCalls.get(); }
    public long getAvgLatencyMs() {
        long c = totalCalls.get();
        return c == 0 ? 0 : totalLatencyMs.get() / c;
    }
    public long getSecondsSinceLastCall() {
        long last = lastCallAt.get();
        return last == 0 ? -1 : (System.currentTimeMillis() - last) / 1000;
    }
    public int getQueueDepth() { return queueDepth.get(); }
    public String getLastModel() { return lastModel; }
    public String getStatus() { return lastStatus; }
    public long getDriftEvents() { return driftEvents.get(); }
    public void recordDrift() { driftEvents.incrementAndGet(); }

    public void shutdown() { worker.shutdownNow(); userWorker.shutdownNow(); }

    /** Set dynamic spacing (idle detector: slow when playing, never below 5 min). */
    public void setSpacingMs(long ms) { this.spacingMs = Math.max(MIN_SPACING_MS, ms); }
    public long getSpacingMs() { return spacingMs; }

    // ── Resource fencing ────────────────────────────────────────────────

    /** True when the host has enough free heap AND disk to run a model call. */
    private boolean resourcesAvailable() {
        Runtime rt = Runtime.getRuntime();
        long freeHeap = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory());
        if (freeHeap < MIN_FREE_HEAP_BYTES) return false;
        // Disk: check the working directory's usable space.
        try {
            java.io.File cwd = new java.io.File(".").getAbsoluteFile();
            long freeDisk = cwd.getUsableSpace();
            return freeDisk >= MIN_FREE_DISK_BYTES;
        } catch (Exception e) {
            return true; // can't stat disk — don't block on it
        }
    }

    public long getFencedCount() { return fencedCount.get(); }
    public long getSecondsSinceLastFence() {
        long last = lastFenceAt.get();
        return last == 0 ? -1 : (System.currentTimeMillis() - last) / 1000;
    }
}
