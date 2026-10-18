package com.mindpalace.render;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Opt-in (-Dmindpalace.frameProf=true) stack sampler for frame hitches. A daemon thread watches the render thread's frame
 * counter; while the counter has not moved for {@value #STALL_MS} ms (a hitch in progress) it samples the render thread's
 * stack every {@value #PERIOD_MS} ms and counts the top frames. The histogram prints at JVM exit, so a hitch is attributed to
 * the code that was actually running (JDK file I/O, a lock, a GL call...) without instrumenting update() line by line.
 * Read-only on the render thread (Thread.getStackTrace); costs nothing when the flag is off.
 */
public final class HitchSampler {
    private static final int STALL_MS = 100;
    private static final int PERIOD_MS = 15;
    private static final int DEPTH = 7;
    private static final Map<String, Integer> histogram = new ConcurrentHashMap<>();
    private static boolean started;

    private HitchSampler() {}

    public static synchronized void start(Thread renderThread) {
        if (started || !GlTrace.PROF) return;
        started = true;
        Thread t = new Thread(() -> {
            long lastFrame = -1, sameSince = System.nanoTime();
            while (!Thread.currentThread().isInterrupted()) {
                try { Thread.sleep(PERIOD_MS); } catch (InterruptedException e) { return; }
                long f = GlTrace.frameCount(), now = System.nanoTime();
                if (f != lastFrame) { lastFrame = f; sameSince = now; continue; }
                if ((now - sameSince) / 1_000_000L < STALL_MS) continue;
                StackTraceElement[] st = renderThread.getStackTrace();
                StringBuilder key = new StringBuilder();
                for (int i = 0; i < st.length && i < DEPTH; i++) {
                    String cls = st[i].getClassName();
                    key.append(cls.substring(cls.lastIndexOf('.') + 1)).append('.').append(st[i].getMethodName())
                       .append(':').append(st[i].getLineNumber()).append(i + 1 < DEPTH && i + 1 < st.length ? " < " : "");
                }
                if (key.indexOf("Screenshot.capture") >= 0) continue;     // e2e harness cost, not a game stall
                histogram.merge(key.toString(), 1, Integer::sum);
            }
        }, "hitch-sampler");
        t.setDaemon(true);
        t.start();
        Runtime.getRuntime().addShutdownHook(new Thread(HitchSampler::report, "hitch-report"));
    }

    private static void report() {
        if (histogram.isEmpty()) { System.out.println("[HitchSampler] no stalls over " + STALL_MS + " ms sampled"); return; }
        System.out.println("[HitchSampler] where the render thread was during stalls over " + STALL_MS + " ms (samples, " + PERIOD_MS + " ms apart):");
        histogram.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(10)
            .forEach(e -> System.out.println("[HitchSampler]   x" + e.getValue() + "  " + e.getKey()));
    }
}
