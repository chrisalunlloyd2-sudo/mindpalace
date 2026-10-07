package com.mindpalace.render;

import org.lwjgl.opengl.GL11;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Opt-in OpenGL error bisection. OpenGL keeps a global error flag, so an error raised by one draw call is
 * only noticed by whoever calls glGetError next. {@code mark("after-sky")} drains the flag and attributes
 * any error to the code that ran SINCE THE PREVIOUS MARK, which narrows a per-frame error to a stage.
 *
 * Off by default (one static boolean test per mark, no GL calls): glGetError can stall the pipeline.
 * Enable with {@code -Dmindpalace.glTrace=true}. Output is bounded: each distinct (stage, error) pair is
 * logged on its first three hits, and a summary of all pairs is printed every {@value #SUMMARY_FRAMES} frames
 * and at shutdown, so a per-frame error cannot flood the log.
 */
public final class GlTrace {
    public static final boolean ENABLED = Boolean.getBoolean("mindpalace.glTrace");
    /** -Dmindpalace.frameProf=true: wall-clock time between marks, no GL calls (so it adds no pipeline stalls). Any frame
     *  slower than {@value #HITCH_MS} ms prints its slowest stages and the update() time, so a hitch names its stage. */
    public static final boolean PROF = Boolean.getBoolean("mindpalace.frameProf");
    private static final long HITCH_NS = 80_000_000L;
    private static final int PROF_MAX = 96;
    private static final String[] profNames = new String[PROF_MAX];
    private static final long[] profNs = new long[PROF_MAX];
    private static int profN;
    private static long profLast, profUpdateNs;
    private static volatile long profFrames;

    public static long frameCount() { return profFrames; }

    /** The game loop reports how long this frame's update() ticks took. */
    public static void noteUpdate(long ns) { if (PROF) profUpdateNs += ns; }

    private static void profMark(String stage) {
        long now = System.nanoTime();
        if (profLast != 0 && profN < PROF_MAX) { profNames[profN] = stage; profNs[profN] = now - profLast; profN++; }
        profLast = now;
    }

    private static void profFrameStart() {
        profMark("frame-start (bloom.end + swap + update + loop)");
        long total = 0;
        for (int i = 0; i < profN; i++) total += profNs[i];
        profFrames = profFrames + 1;
        if (total > HITCH_NS) {
            StringBuilder sb = new StringBuilder("[FrameProf] frame ").append(profFrames).append(": ").append(total / 1_000_000)
                .append(" ms, update() ").append(profUpdateNs / 1_000_000).append(" ms; slowest:");
            boolean[] used = new boolean[profN];
            for (int k = 0; k < 4 && k < profN; k++) {
                int best = -1;
                for (int i = 0; i < profN; i++) if (!used[i] && (best < 0 || profNs[i] > profNs[best])) best = i;
                used[best] = true;
                sb.append(' ').append(profNames[best]).append(' ').append(profNs[best] / 1_000_000).append("ms;");
            }
            System.out.println(sb);
        }
        profN = 0;
        profUpdateNs = 0;
    }

    private static final int SUMMARY_FRAMES = 600;
    private static final int MAX_KEYS = 256;                 // hard bound on distinct pairs we remember

    private static final Map<String, long[]> counts = new LinkedHashMap<>();
    private static String previous = "start";
    private static int frames;

    private GlTrace() {}

    public static String describe(int err) {
        switch (err) {
            case GL11.GL_INVALID_ENUM: return "GL_INVALID_ENUM(0x500)";
            case GL11.GL_INVALID_VALUE: return "GL_INVALID_VALUE(0x501)";
            case GL11.GL_INVALID_OPERATION: return "GL_INVALID_OPERATION(0x502)";
            case GL11.GL_STACK_OVERFLOW: return "GL_STACK_OVERFLOW(0x503)";
            case GL11.GL_STACK_UNDERFLOW: return "GL_STACK_UNDERFLOW(0x504)";
            case GL11.GL_OUT_OF_MEMORY: return "GL_OUT_OF_MEMORY(0x505)";
            default: return "0x" + Integer.toHexString(err);
        }
    }

    /** Call at the start of each frame; resets the stage chain and prints the periodic summary. */
    public static void frameStart() {
        if (PROF) profFrameStart();
        if (!ENABLED) return;
        previous = "frame-start";
        mark("frame-start(leftover from update/swap)");
        if (++frames % SUMMARY_FRAMES == 0) summary();
    }

    /** Drain GL errors and attribute them to the work done since the previous mark. */
    public static void mark(String stage) {
        if (PROF) profMark(stage);
        if (!ENABLED) return;
        int guard = 0;
        for (int e = GL11.glGetError(); e != GL11.GL_NO_ERROR && guard < 16; e = GL11.glGetError(), guard++) {
            String key = describe(e) + " between [" + previous + "] and [" + stage + "]";
            long[] c = counts.get(key);
            if (c == null) {
                if (counts.size() >= MAX_KEYS) continue;
                c = new long[]{0, frames};
                counts.put(key, c);
            }
            errorTotal++;
            if (++c[0] <= 3) {
                System.out.println("[GlTrace] " + key + " (frame " + frames + ", hit " + c[0] + ")");
            }
        }
        int prog = GL11.glGetInteger(org.lwjgl.opengl.GL20.GL_CURRENT_PROGRAM);
        if (prog != lastProgram && ++programChanges <= 12) {
            System.out.println("[GlTrace] shader program " + lastProgram + " -> " + prog + " between [" + previous + "] and [" + stage + "] (frame " + frames + ")");
        }
        lastProgram = prog;
        previous = stage;
    }

    private static long errorTotal;

    /** Total GL errors attributed so far (always 0 unless tracing is enabled). */
    public static long errorTotal() { return errorTotal; }

    private static int lastProgram = -1;
    private static int programChanges;

    public static void summary() {
        if (!ENABLED) return;
        if (counts.isEmpty()) {
            System.out.println("[GlTrace] frame " + frames + ": no GL errors");
            return;
        }
        System.out.println("[GlTrace] summary at frame " + frames + ":");
        counts.forEach((k, v) -> System.out.println("[GlTrace]   x" + v[0] + "  " + k + "  (first seen frame " + v[1] + ")"));
    }

    private static int programMismatches;

    /** Report (first few times only) when the program bound is not the one the caller assumes: a uniform set while
     *  the wrong program is current raises GL_INVALID_OPERATION, because locations belong to one program. */
    public static void expectProgram(int expected, String where) {
        if (!ENABLED) return;
        int actual = GL11.glGetInteger(org.lwjgl.opengl.GL20.GL_CURRENT_PROGRAM);
        if (actual != expected && ++programMismatches <= 5) {
            System.out.println("[GlTrace] wrong program at [" + where + "]: current=" + actual + " expected=" + expected + " (frame " + frames + ")");
        }
    }
}
