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
        if (!ENABLED) return;
        previous = "frame-start";
        mark("frame-start(leftover from update/swap)");
        if (++frames % SUMMARY_FRAMES == 0) summary();
    }

    /** Drain GL errors and attribute them to the work done since the previous mark. */
    public static void mark(String stage) {
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
