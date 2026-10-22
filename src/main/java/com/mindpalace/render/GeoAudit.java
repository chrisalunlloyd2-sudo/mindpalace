package com.mindpalace.render;

import org.joml.Vector3f;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * QUAL-1 visual-defect sweep — GL-free geometry recorder.
 *
 * Enabled with -Dmindpalace.geoAudit=true (or the headless selftest probe).
 * Every cube/sphere/line emission through Renderer's draw* funnel is captured
 * as a plain-data record: kind, center, size, optional yaw/color/texture id,
 * and the Java call site that emitted it. No GL context, no state change —
 * the recorder cannot alter what a frame looks like.
 *
 * The e2e tour arms one snapshot per waypoint (armed at captureLabeled, so the
 * recorded frame is exactly the one the screenshot came from) plus one
 * widened-cull "sweep" frame right after it (WorldBuilder.cullDistanceOverride),
 * so the audit sees every room's static geometry, not only what the waypoint
 * camera had culled in. GameEngine dumps everything to
 * <screenshotDir>/geo_audit/geo_records.jsonl at the tour's exit;
 * scripts/geo_audit.py parses and checks the file.
 *
 * Mirrors GlTrace's static-ENABLED toggle pattern: zero per-frame cost when
 * the property is absent.
 *
 * jsonl schema (source of truth; scripts/geo_audit.py parses defensively):
 *   {"wp":"07_todo_crystals"}                        snapshot header (starts each block)
 *   {"k":"cube","c":[x,y,z],"s":[sx,sy,sz],"yaw":1.57,
 *    "tex":7,"rgb":[r,g,b],"d":[dx,dy,dz],
 *    "cs":"WorldBuilder.renderRoom:971","n":1}        record
 * Sweep snapshots use wp "<label>#sweep".
 */
public final class GeoAudit {

    /** -Dmindpalace.geoAudit=true arms the recorder (GlTrace-style toggle). */
    public static final boolean ENABLED = Boolean.getBoolean("mindpalace.geoAudit");

    /** Cull distance during a sweep frame — palace + lab + courtyard + planet fringe. */
    public static final float SWEEP_CULL_DISTANCE = 300f;

    /** Set only by the engine selftest probe to exercise the pipeline headlessly. */
    private static boolean probeMode;

    private static boolean active() { return ENABLED || probeMode; }

    /** One emitted primitive. */
    private static final class Rec {
        String kind;
        float cx, cy, cz;
        float sx, sy, sz;
        float yaw = Float.NaN;            // rotateY radians; NaN = drawn unrotated
        float r = -1, g = -1, b = -1;     // solid color; r &lt; 0 = textured/tex id path
        int texId = -1;                    // texture id; -1 = color/line draw
        float dirX, dirY, dirZ;            // drawLine unit direction; 0,0,0 otherwise
        String callSite;
        int count = 1;                     // exact-duplicate collapse within a frame
    }

    private static Path outDir;                     // dump directory
    private static BufferedWriter writer;           // lazily opened stream (truncate per run)
    private static long written;                    // records written so far
    private static int snapshots;                   // snapshots flushed so far
    private static final List<String> flushedWaypoints = new ArrayList<>();

    private static final List<Rec> buffer = new ArrayList<>();
    private static final Map<String, Rec> byKey = new LinkedHashMap<>();
    private static String armedLabel;
    private static boolean armedSweep;
    private static boolean sweepPending;
    private static String lastWaypoint = "unknown";

    private GeoAudit() { }

    // ── wiring points (GameEngine / Renderer) ─────────────────────────────

    /** Renderer.beginFrame: discard any stale half-frame from an early return. */
    public static void frameBegin() {
        if (!active()) return;
        buffer.clear();
        byKey.clear();
    }

    /** GameEngine.render tail (before bloom.end): flush the frame if a
     *  snapshot is armed, else discard the frame's records. */
    public static void frameEnd() {
        if (!active()) return;
        try {
            if (armedLabel != null && !buffer.isEmpty()) {
                flushSnapshot();
            }
            if (armedLabel != null && !armedSweep) sweepPending = true;
        } catch (IOException e) {
            System.out.println("[GeoAudit] flush FAILED: " + e);
        } finally {
            armedLabel = null;
            armedSweep = false;
            buffer.clear();
            byKey.clear();
        }
    }

    /** captureLabeled: record the next rendered frame — the shot's own frame. */
    public static void armWaypoint(String label) {
        if (!active()) return;
        armedLabel = label;
        armedSweep = false;
        lastWaypoint = label;
        buffer.clear();
        byKey.clear();
    }

    /** updateE2ETour arms the widened sweep frame for the previous waypoint. */
    public static void armSweep() {
        if (!active()) return;
        armedLabel = lastWaypoint + "#sweep";
        armedSweep = true;
        buffer.clear();
        byKey.clear();
    }

    /** True while the CURRENT frame is the armed widened-cull sweep.
     *  WorldBuilder.render reads this to bypass distance-cull + fog. */
    public static boolean isSweepFrame() {
        return active() && armedSweep;
    }

    /** True once per completed waypoint snapshot; clears the latch. */
    public static boolean consumeSweepPending() {
        if (!sweepPending) return false;
        sweepPending = false;
        return true;
    }

    /** Where geo_records.jsonl lands. Default "geo_audit_out" relative to cwd. */
    public static void setOutDir(Path dir) {
        outDir = dir;
    }

    // ── record sites (Renderer draw funnels) ──────────────────────────────

    /** Capture one cube/sphere draw through the Renderer funnel. */
    public static void record(String kind, Vector3f position, Vector3f size,
                              float yaw, int texId, float r, float g, float b) {
        if (!active()) return;
        recordInternal(kind, position.x, position.y, position.z,
            size.x, size.y, size.z, yaw, texId, r, g, b, 0, 0, 0);
    }

    /** Capture a drawLine: center at the segment midpoint, long axis = dir. */
    public static void recordLine(Vector3f mid, Vector3f dir, float thickness, float len,
                                  float r, float g, float b) {
        if (!active()) return;
        recordInternal("line", mid.x, mid.y, mid.z, thickness, thickness, len,
            Float.NaN, -1, r, g, b, dir.x, dir.y, dir.z);
    }

    private static void recordInternal(String kind, float cx, float cy, float cz,
                                       float sx, float sy, float sz, float yaw,
                                       int texId, float r, float g, float b,
                                       float dx, float dy, float dz) {
        String key = kind + '/' + texId
            + '/' + Float.floatToRawIntBits(cx) + ',' + Float.floatToRawIntBits(cy) + ',' + Float.floatToRawIntBits(cz)
            + '/' + Float.floatToRawIntBits(sx) + ',' + Float.floatToRawIntBits(sy) + ',' + Float.floatToRawIntBits(sz)
            + '/' + Float.floatToRawIntBits(yaw)
            + '/' + Float.floatToRawIntBits(r) + ',' + Float.floatToRawIntBits(g) + ',' + Float.floatToRawIntBits(b);
        Rec existing = byKey.get(key);
        if (existing != null) {
            existing.count++;
            return;
        }
        Rec rec = new Rec();
        rec.kind = kind;
        rec.cx = cx; rec.cy = cy; rec.cz = cz;
        rec.sx = sx; rec.sy = sy; rec.sz = sz;
        rec.yaw = yaw;
        rec.texId = texId;
        rec.r = r; rec.g = g; rec.b = b;
        rec.dirX = dx; rec.dirY = dy; rec.dirZ = dz;
        rec.callSite = callSite();
        byKey.put(key, rec);
        buffer.add(rec);
    }

    /** First non-render-pkg stack frame above the funnel = the real caller. */
    private static String callSite() {
        StackTraceElement[] st = Thread.currentThread().getStackTrace();
        int depth = Math.min(st.length, 12);
        for (int i = 2; i < depth; i++) {
            StackTraceElement e = st[i];
            String cls = e.getClassName();
            if (cls.startsWith("com.mindpalace.render.")) continue;
            String simple = cls.substring(cls.lastIndexOf('.') + 1);
            return simple + "." + e.getMethodName() + ":" + e.getLineNumber();
        }
        return "unknown";
    }

    // ── dump ──────────────────────────────────────────────────────────────

    /** Close the stream + write meta.json. Called at the e2e tour's exit. */
    public static void dump() {
        if (!ENABLED && !probeMode) return;
        try {
            if (writer != null) {
                writer.close();
                writer = null;
            }
            StringBuilder meta = new StringBuilder();
            meta.append("{\"written\":").append(written)
                .append(",\"snapshots\":").append(snapshots)
                .append(",\"waypoints\":[");
            for (int i = 0; i < flushedWaypoints.size(); i++) {
                if (i > 0) meta.append(',');
                meta.append('"').append(jsonEscape(flushedWaypoints.get(i))).append('"');
            }
            meta.append("]}");
            Path metaPath = dumpDir().resolve("meta.json");
            Files.write(metaPath, meta.toString().getBytes(StandardCharsets.UTF_8));
            System.out.println("[GeoAudit] dumped " + written + " records, " + snapshots
                + " snapshots -> " + metaPath.toAbsolutePath());
        } catch (IOException e) {
            System.out.println("[GeoAudit] dump FAILED: " + e);
        }
    }

    private static Path dumpDir() throws IOException {
        if (outDir == null) outDir = Paths.get("geo_audit_out");
        Files.createDirectories(outDir);
        return outDir;
    }

    private static void ensureWriter() throws IOException {
        if (writer == null) {
            Path dir = dumpDir();
            writer = Files.newBufferedWriter(dir.resolve("geo_records.jsonl"),
                StandardCharsets.UTF_8);
        }
    }

    private static void flushSnapshot() throws IOException {
        ensureWriter();
        // Block order: header line first (starts the block), then its records.
        writer.write("{\"wp\":\"" + jsonEscape(armedLabel) + "\"}\n");
        for (Rec rec : buffer) {
            StringBuilder sb = new StringBuilder(160);
            sb.append("{\"k\":\"").append(rec.kind)
                .append("\",\"c\":[").append(num(rec.cx)).append(',')
                .append(num(rec.cy)).append(',').append(num(rec.cz))
                .append("],\"s\":[").append(num(rec.sx)).append(',')
                .append(num(rec.sy)).append(',').append(num(rec.sz))
                .append(']');
            if (!Float.isNaN(rec.yaw)) sb.append(",\"yaw\":").append(num(rec.yaw));
            if (rec.texId >= 0) sb.append(",\"tex\":").append(rec.texId);
            if (rec.r >= 0) sb.append(",\"rgb\":[").append(num(rec.r)).append(',')
                .append(num(rec.g)).append(',').append(num(rec.b)).append(']');
            if (rec.dirX != 0 || rec.dirY != 0 || rec.dirZ != 0)
                sb.append(",\"d\":[").append(num(rec.dirX)).append(',')
                    .append(num(rec.dirY)).append(',').append(num(rec.dirZ)).append(']');
            sb.append(",\"cs\":\"").append(jsonEscape(rec.callSite))
                .append("\",\"n\":").append(rec.count).append("}\n");
            writer.write(sb.toString());
            written++;
        }
        writer.flush();
        snapshots++;
        flushedWaypoints.add(armedLabel);
    }

    /** JSON-safe float; NaN/Infinities encode as 1e999 (parses as +inf). */
    private static String num(float f) {
        if (Float.isNaN(f) || Float.isInfinite(f)) return "1e999";
        return Float.toString(f);
    }

    private static String jsonEscape(String s) {
        return s == null ? "unknown" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ── headless selftest probe ───────────────────────────────────────────

    /** Record→collapse→flush→round-trip pipeline check with NO GL at all.
     *  Called by GameEngine.runSelfTest; returns true when everything holds. */
    public static boolean selfTestProbe() {
        boolean ok = false;
        probeMode = true;
        try {
            setOutDir(Paths.get("target", "selftest_geo_audit"));
            Path recordFile = outDir.resolve("geo_records.jsonl");
            Files.deleteIfExists(recordFile);
            armWaypoint("probe");
            Vector3f p = new Vector3f(0.5f, 1.0f, -0.25f);
            Vector3f s = new Vector3f(2f, 0.1f, 0.4f);
            record("cube", p, s, Float.NaN, 3, -1, -1, -1);
            record("cube", p, s, Float.NaN, 3, -1, -1, -1); // exact dup → collapses, n=2
            record("cubeColor", new Vector3f(1f, 2f, 3f), new Vector3f(0.2f, 0.2f, 0.2f),
                0.5f, -1, 1f, 0f, 0f);
            recordLine(new Vector3f(0, 2, 0), new Vector3f(1, 0, 0), 0.05f, 3f, 1f, 0f, 0f);
            frameEnd();
            written = 0; snapshots = 0; // don't leak probe counters into a later dump
            flushedWaypoints.clear();
            List<String> lines = Files.readAllLines(recordFile, StandardCharsets.UTF_8);
            boolean hasHeader = !lines.isEmpty() && lines.get(0).contains("\"probe\"");
            boolean hasDup = lines.stream().anyMatch(l -> l.contains("\"cs\"") && l.contains("\"n\":2"));
            boolean hasColor = lines.stream().anyMatch(l -> l.contains("cubeColor") && l.contains("\"rgb\":[1.0,0.0,0.0]"));
            boolean hasLine = lines.stream().anyMatch(l -> l.contains("\"line\"") && l.contains("\"d\":[1.0,0.0,0.0]"));
            boolean hasTrailer = lines.get(lines.size() - 1).contains("\"cs\"")
                && !lines.get(lines.size() - 1).contains("\"wp\"")
                && lines.stream().filter(l -> l.contains("\"cs\"")).count() == 3;
            ok = hasHeader && hasDup && hasColor && hasLine && hasTrailer;
            if (!ok) {
                System.out.println("FAIL GeoAudit smoke probe: header=" + hasHeader
                    + " dup=" + hasDup + " color=" + hasColor + " line=" + hasLine
                    + " trailer=" + hasTrailer + " lines=" + lines.size());
            }
        } catch (Exception e) {
            System.out.println("FAIL GeoAudit smoke probe: "
                + e.getClass().getSimpleName() + " " + e.getMessage());
            ok = false;
        } finally {
            probeMode = false;
        }
        if (ok) System.out.println("PASS GeoAudit smoke probe (record/collapse/flush/round-trip, no GL)");
        return ok;
    }
}