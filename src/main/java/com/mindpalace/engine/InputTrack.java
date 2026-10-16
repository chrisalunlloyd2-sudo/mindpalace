package com.mindpalace.engine;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * PERF-A2 (t_5155f104): scripted input track for the frame-locked perf run
 * (docs/AUTONOMOUS_PERF_TEST.md D2). A JSON file of segments:
 *
 * <pre>
 * { "segments": [ { "t0": 0.0, "t1": 4.0, "keys": ["W"],
 *                   "mouseDx": 0.0, "mouseDy": 0.0 } ],
 *   "snapshots": [120, 240],
 *   "loop": true }
 * </pre>
 *
 * Times are in SIM seconds (seconds of accumulated frame-locked sim time, NOT
 * wall seconds — under -Dmindpalace.frameLock the two are unrelated by design).
 * keys names are GLFW letter names ("W","A","S","D","SPACE","SHIFT",...).
 * mouseDx/mouseDy are per-second LOOK rates in degrees (positive dx = turn
 * right, positive dy = look down, matching real-mouse polarity). apply()
 * receives the camera sensitivity and converts the rates to mouse-pixel
 * deltas, so an injected turn renders EXACTLY like a real mouse move
 * (Camera.rotate applies its own sensitivity to pixel input).
 *
 * Driven from the engine loop's perf branch each frame AFTER input.update and
 * BEFORE player.update: seek by SIM time → identical camera path every run
 * under frame lock.
 */
public class InputTrack {
    public static class Segment {
        float t0, t1;
        int[] keys = new int[0];   // GLFW key codes held during [t0, t1)
        double mouseDx, mouseDy;   // deg/sec look rates
    }

    /** GLFW letter name ("W", "SPACE", ...) → GLFW key code; -1 if unknown. */
    static int keyNameToCode(String name) {
        String n = name.trim().toUpperCase();
        if (n.length() == 1) {
            char c = n.charAt(0);
            if (c >= 'A' && c <= 'Z') return GLFW.GLFW_KEY_A + (c - 'A');
            if (c >= '0' && c <= '9') {
                int dig = c - '0';
                return dig == 0 ? GLFW.GLFW_KEY_0
                     : GLFW.GLFW_KEY_1 + (dig - 1);
            }
        }
        switch (n) {
            case "SPACE": case "SPACEBAR": return GLFW.GLFW_KEY_SPACE;
            case "LEFT_SHIFT": case "SHIFT": return GLFW.GLFW_KEY_LEFT_SHIFT;
            case "LEFT_CONTROL": case "CTRL": return GLFW.GLFW_KEY_LEFT_CONTROL;
            case "LEFT_ALT": case "ALT": return GLFW.GLFW_KEY_LEFT_ALT;
            case "TAB": return GLFW.GLFW_KEY_TAB;
            case "ENTER": return GLFW.GLFW_KEY_ENTER;
            case "E": return GLFW.GLFW_KEY_E;
            case "F": return GLFW.GLFW_KEY_F;
            default: return -1;
        }
    }

    private final List<Segment> segments = new ArrayList<>();
    private final List<Integer> snapshotFrames = new ArrayList<>();
    private boolean looping;         // wrap sim time over total duration
    private int loopCount = 0;       // times wrapped (exposed for [PERF] logs)
    private double totalDuration;
    // Keys held by the PREVIOUS apply — released when their segment ends.
    private final java.util.Set<Integer> heldKeys = new java.util.HashSet<>();

    /** Parse a track file from disk. Throws IOException with a useful message. */
    public static InputTrack load(String path) throws IOException {
        return parse(Files.readString(Path.of(path)), path);
    }

    /**
     * PERF-A2: parse the packaged default track from the classpath
     * (tracks/perf_track_09_agents.json) — used by setPerf when
     * -Dmindpalace.inputTrack is absent.
     */
    public static InputTrack loadResource(String resourcePath) throws IOException {
        InputStream in = InputTrack.class.getClassLoader().getResourceAsStream(resourcePath);
        if (in == null) throw new IOException("classpath resource not found: " + resourcePath);
        try (InputStream s = in) {
            return parse(new String(s.readAllBytes(), StandardCharsets.UTF_8), "classpath:" + resourcePath);
        }
    }

    private static InputTrack parse(String json, String origin) throws IOException {
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            throw new IOException(origin + ": not a JSON object track — " + e);
        }
        InputTrack track = new InputTrack();
        if (root.has("segments")) {
            for (var se : root.getAsJsonArray("segments")) {
                JsonObject so = se.getAsJsonObject();
                Segment s = new Segment();
                s.t0 = so.get("t0").getAsFloat();
                s.t1 = so.get("t1").getAsFloat();
                s.mouseDx = so.has("mouseDx") ? so.get("mouseDx").getAsDouble() : 0.0;
                s.mouseDy = so.has("mouseDy") ? so.get("mouseDy").getAsDouble() : 0.0;
                if (so.has("keys")) {
                    List<Integer> codes = new ArrayList<>();
                    for (var ke : so.getAsJsonArray("keys")) {
                        int code = keyNameToCode(ke.getAsString());
                        if (code < 0) throw new IOException(origin + ": unknown key name '" + ke.getAsString() + "'");
                        codes.add(code);
                    }
                    s.keys = codes.stream().mapToInt(Integer::intValue).toArray();
                }
                track.segments.add(s);
            }
        }
        if (root.has("snapshots")) {
            for (var fe : root.getAsJsonArray("snapshots"))
                track.snapshotFrames.add(fe.getAsInt());
        }
        track.looping = !root.has("loop") || root.get("loop").getAsBoolean();
        double dur = 0;
        for (Segment s : track.segments) dur = Math.max(dur, s.t1);
        track.totalDuration = dur;
        return track;
    }

    /**
     * Inject the segments active at sim time {@code simTime} for a frame of
     * length {@code frameDt} (SIM seconds) into {@code input}. Called from the
     * engine loop's perf branch BEFORE player.update consumes the state.
     * {@code cameraSensitivity} converts the track's deg/s look rates into the
     * pixel deltas the camera consumes.
     */
    public void apply(double simTime, double frameDt, Input input, float cameraSensitivity) {
        double t = simTime;
        if (looping && totalDuration > 0) {
            double wrapped = t % totalDuration;
            int newLoops = (int) (t / totalDuration);
            if (newLoops != loopCount) {
                loopCount = newLoops;
                System.out.println("[PERF] input track loop " + newLoops
                    + " (sim t=" + String.format("%.1f", t) + "s)");
            }
            t = wrapped;
        }
        java.util.Set<Integer> nowHeld = new java.util.HashSet<>();
        for (Segment s : segments) {
            if (t >= s.t0 && t < s.t1) {
                for (int k : s.keys) { input.injectKeyHold(k, true); nowHeld.add(k); }
                // deg/s → mouse pixels this frame: px = deg / sensitivity, with
                // Camera.rotate applying sensitivity to pixel input. Positive
                // dx = look right, positive dy = look down (mouse polarity).
                double scale = frameDt / Math.max(1e-4f, cameraSensitivity);
                if (s.mouseDx != 0.0) input.injectMouseDelta(s.mouseDx * scale, 0);
                if (s.mouseDy != 0.0) input.injectMouseDelta(0, s.mouseDy * scale);
            }
        }
        // Release holds whose segment ended this frame. Belt-and-braces: the
        // engine re-polls GLFW before each apply in the real loop, but a
        // standalone probe (selftest) has no GLFW, so release must be
        // self-contained for clean segment boundaries everywhere.
        for (int k : heldKeys) if (!nowHeld.contains(k)) input.injectKeyHold(k, false);
        heldKeys.clear();
        heldKeys.addAll(nowHeld);
    }

    /** True when {@code frame} is a configured snapshot frame (this run). */
    public boolean isSnapshotFrame(int frame) { return snapshotFrames.contains(frame); }

    public int snapshotFrameCount() { return snapshotFrames.size(); }
    public double totalDuration() { return totalDuration; }
    public int segmentCount() { return segments.size(); }
    /** Times the input track has wrapped back to t0 (0-based; for [PERF] logs). */
    public int loopCount() { return loopCount; }
}