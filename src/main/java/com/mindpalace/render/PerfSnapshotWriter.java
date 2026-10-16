package com.mindpalace.render;

import org.lwjgl.stb.STBImageWrite;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * PERF-A2 (t_5155f104, docs/AUTONOMOUS_PERF_TEST.md D3): single daemon worker
 * that takes frame buffers captured on the GL thread (Screenshot.captureRaw)
 * and does the slow PNG encode OFF the render thread, so encode time never
 * counts inside a measured perf frame. Mirrors captureLabeled's doctrine
 * (measure BEFORE the PNG encode — encoding costs 0.3-1.2 s and was
 * previously mis-counted as a hitch in the next waypoint's window).
 */
public final class PerfSnapshotWriter {
    private static final List<Job> QUEUE = new ArrayList<>();
    private static Thread worker;

    private PerfSnapshotWriter() {}

    private static final class Job {
        final ByteBuffer pixels;
        final int width, height;
        final String path;
        final String note;
        Job(ByteBuffer pixels, int width, int height, String path, String note) {
            this.pixels = pixels; this.width = width; this.height = height;
            this.path = path; this.note = note;
        }
    }

    /** Start the worker (idempotent). Called from perf-mode init on the GL thread. */
    public static synchronized void start(int width, int height, String dir) {
        if (worker != null) return;
        Thread t = new Thread(() -> {
            while (true) {
                Job job;
                synchronized (QUEUE) {
                    while (QUEUE.isEmpty()) {
                        try { QUEUE.wait(); }
                        catch (InterruptedException e) { return; }
                    }
                    job = QUEUE.remove(0);
                }
                try {
                    Files.createDirectories(Path.of(dir));
                    boolean ok = STBImageWrite.stbi_write_png(
                        job.path, job.width, job.height, 4, job.pixels, job.width * 4);
                    System.out.println("[PerfSnapshot] " + (ok ? "" : "FAILED ")
                        + "frame " + job.path + (job.note == null ? "" : " — " + job.note));
                } catch (Exception e) {
                    System.err.println("[PerfSnapshot] write failed: " + job.path
                        + " — " + e.getClass().getSimpleName() + " " + e.getMessage());
                }
            }
        }, "perf-snapshot-writer");
        t.setDaemon(true);
        t.start();
        worker = t;
        System.out.println("[PerfSnapshot] worker started (encode off GL thread)"
            + " -> " + dir + " (" + width + "x" + height + ")");
    }

    /**
     * GL thread: hand a freshly captured buffer (Screenshot.captureRaw) to the
     * worker. Non-blocking (enqueue + notify) — never stalls the render loop.
     */
    public static synchronized void submit(ByteBuffer pixels, int width, int height,
                                           String path, String note) {
        synchronized (QUEUE) { QUEUE.add(new Job(pixels, width, height, path, note)); QUEUE.notifyAll(); }
    }
}