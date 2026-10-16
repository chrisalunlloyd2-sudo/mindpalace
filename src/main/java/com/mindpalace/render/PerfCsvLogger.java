package com.mindpalace.render;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * PERF-A2 (t_5155f104, docs/AUTONOMOUS_PERF_TEST.md D4): per-frame CSV wall
 * cost, buffered on the appender and flushed on a daemon worker so a disk
 * stall never blocks the render thread.
 *
 * Row shape (header included, analyzer-friendly, e2e perf-gate shaped):
 *   frame,wall_ns,frame_ms,update_ms,stage_slowest_ms,stage_name,snap,hitch,note
 *
 * HITCH_MS is 80 — the same threshold as HitchSampler's stall accounting.
 */
public final class PerfCsvLogger {
    /** Same nominal threshold as HitchSampler.STALL_MS's trigger class. */
    public static final double HITCH_MS = 80.0;

    private static final String HEADER =
        "frame,wall_ns,frame_ms,update_ms,stage_slowest_ms,stage_name,snap,hitch,note";

    private final Path path;
    private final List<String> buffer = new ArrayList<>(512);
    private final Thread flusher;
    private volatile boolean open = true;
    private int frames;
    private int hitches;
    private double worstMs;
    private int worstFrame;
    private double sumMs;
    private final List<Double> allMs = new ArrayList<>();

    public PerfCsvLogger(String path) throws IOException {
        this.path = Path.of(path);
        Path parent = this.path.getParent();
        if (parent != null) Files.createDirectories(parent);
        buffer.add(HEADER);
        this.flusher = new Thread(() -> {
            while (true) {
                List<String> batch;
                synchronized (this) {
                    while (buffer.isEmpty() && open) {
                        try { wait(1000); } catch (InterruptedException e) { return; }
                    }
                    if (buffer.isEmpty() && !open) return;
                    batch = new ArrayList<>(buffer);
                    buffer.clear();
                }
                writeBatch(batch);
            }
        }, "perf-csv-flusher");
        this.flusher.setDaemon(true);
        this.flusher.start();
    }

    /** Render thread: append one row. Non-blocking (in-memory append). */
    public void row(long frame, long wallNs, double frameMs, double updateMs,
                    double stageSlowMs, String stageName, boolean snap,
                    String noteOverride) {
        boolean hitch = frameMs > HITCH_MS;
        String note = noteOverride == null ? "" : noteOverride;
        if (hitch && note.isEmpty()) note = "hitch";
        String line = frame + "," + wallNs + "," + String.format(java.util.Locale.ROOT, "%.2f", frameMs)
            + "," + String.format(java.util.Locale.ROOT, "%.2f", updateMs)
            + "," + String.format(java.util.Locale.ROOT, "%.2f", stageSlowMs)
            + "," + (stageName == null ? "none" : stageName)
            + "," + (snap ? 1 : 0) + "," + (hitch ? 1 : 0) + "," + note;
        synchronized (this) {
            buffer.add(line);
            frames++;
            hitches += hitch ? 1 : 0;
            sumMs += frameMs;
            if (frameMs > worstMs) { worstMs = frameMs; worstFrame = (int) frame; }
            allMs.add(frameMs);
        }
    }

    /** Percentile over the linear samples collected so far (or at close). */
    public double percentile(double p) {
        synchronized (this) {
            if (allMs.isEmpty()) return 0.0;
            List<Double> sorted = new ArrayList<>(allMs);
            java.util.Collections.sort(sorted);
            int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
            idx = Math.max(0, Math.min(sorted.size() - 1, idx));
            return sorted.get(idx);
        }
    }

    public int frameCount() { synchronized (this) { return frames; } }
    public int hitchCount() { synchronized (this) { return hitches; } }
    public double worstMs() { synchronized (this) { return worstMs; } }
    public int worstFrame() { synchronized (this) { return worstFrame; } }
    public double avgMs() { synchronized (this) { return frames == 0 ? 0.0 : sumMs / frames; } }
    public String path() { return path.toString(); }

    /** Frames whose wall cost exceeded {@code thresholdMs} (D4 verdict gate). */
    public int framesOver(double thresholdMs) {
        synchronized (this) {
            int n = 0;
            for (double ms : allMs) if (ms > thresholdMs) n++;
            return n;
        }
    }

    /** Flush everything to disk and stop the worker. Returns rows written. */
    public synchronized int close() {
        open = false;
        notifyAll();
        List<String> batch;
        synchronized (this) { batch = new ArrayList<>(buffer); buffer.clear(); }
        writeBatch(batch);
        try { flusher.join(2000); } catch (InterruptedException ignored) {}
        return frames;
    }

    private void writeBatch(List<String> batch) {
        if (batch.isEmpty()) return;
        try (BufferedWriter w = Files.newBufferedWriter(path,
                StandardCharsets.UTF_8,
                batch.get(0).startsWith("frame") || !Files.exists(path)
                    ? java.nio.file.StandardOpenOption.CREATE
                    : java.nio.file.StandardOpenOption.APPEND)) {
            for (String line : batch) {
                w.write(line);
                w.newLine();
            }
        } catch (IOException e) {
            System.err.println("[PerfCsv] write failed: " + e.getMessage());
        }
    }
}