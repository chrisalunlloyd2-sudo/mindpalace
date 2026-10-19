package com.mindpalace.tailscale;

import com.mindpalace.backup.MemoryManager;
import com.mindpalace.backup.Telemetry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T3 use 11 — log/telemetry collector feeding the Tree of Knowledge.
 *
 * Two halves:
 *  - LogShipper (remote side, each node): tails its own game_console.log and
 *    POSTs new lines to the collector node's /v1/log/ship endpoint (bound by
 *    the live Router — admin handlers may read it down to read-public).
 *  - LogCollector (sink side, this node): accepts shipped lines, appends them
 *    to dataRoot/collected_logs/<day>.log, records a telemetry breadcrumb,
 *    and feeds notable lines (code / mistake patterns) into MemoryManager's
 *    knowledge tables so the Tree of Knowledge grows from every node's logs.
 *
 * Hermetic: LogCollector is pure in-memory + local files (no sockets here —
 * the mesh socket belongs to MeshServer/use-8 and the live Router to T1);
 * LogShipper degrades when dataRoot is read-only or the endpoint is absent.
 */
public final class LogCollector {

    /** Lines that carry knowledge worth memorizing (bug-fix / code snippets / errors). */
    private static final Pattern[] KNOWLEDGE_PATTERNS = {
        Pattern.compile("fix\\(#[0-9]+\\)"),
        Pattern.compile("ERROR|Exception|FATAL"),
        Pattern.compile("selftest|VERIFY-PASS|RESULT"),
    };

    private final Path logDir;
    private final MemoryManager memory;
    private final Telemetry telemetry;
    private long accepted;
    private long knowledgeHits;
    private final List<String> recent = new ArrayList<>();
    private static final int RECENT_CAP = 200;

    public LogCollector(Path dataRoot, MemoryManager memory, Telemetry telemetry) {
        this.logDir = dataRoot.resolve("collected_logs");
        this.memory = memory;
        this.telemetry = telemetry;
        try {
            Files.createDirectories(logDir);
        } catch (IOException ignored) {}
    }

    /**
     * Ingest one shipped line from a node.
     * @param node  tailnet login or device name (never stored raw-PII — here it
     *              is the Architect's own login from WhoIsResolver)
     * @param line  one log line
     * @return true if accepted (non-blank, not a duplicate of any of the
     *              RECENT_CAP most recent lines)
     */
    public synchronized boolean ingest(String node, String line) {
        if (line == null || line.isBlank()) return false;
        String trimmed = line.length() > 2000 ? line.substring(0, 2000) : line.trim();
        // Dedup across the whole recent ring, not just the last line: a node
        // that ships the same log line twice should count once (selftest-
        // observable via replay of an earlier line).
        if (recent.contains(trimmed)) {
            return false; // duplicate of any recent line — drop
        }
        String stamp = Instant.now().toString();
        String row = stamp + "\t" + (node == null || node.isBlank() ? "unknown" : node) + "\t" + trimmed;
        try {
            Path day = logDir.resolve("collected-" + stamp.substring(0, 10) + ".log");
            Files.writeString(day, row + System.lineSeparator(),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) {}
        recent.add(trimmed);
        if (recent.size() > RECENT_CAP) recent.remove(0);
        accepted++;
        feedKnowledge(trimmed);
        return true;
    }

    /** Feed notable lines into MemoryManager so the Tree of Knowledge grows. */
    private void feedKnowledge(String line) {
        for (Pattern p : KNOWLEDGE_PATTERNS) {
            if (p.matcher(line).find()) {
                try {
                    if (memory != null) {
                        memory.recordCode(line, "log");
                    }
                    knowledgeHits++;
                } catch (Exception ignored) {}
                break; // one record per line
            }
        }
    }

    /** For the spectator/admin status pages: last N ingested lines, newest first. */
    public synchronized List<String> recent(int n) {
        int to = Math.min(n, recent.size());
        List<String> out = new ArrayList<>(recent.subList(recent.size() - to, recent.size()));
        java.util.Collections.reverse(out);
        return out;
    }

    public synchronized long acceptedCount() { return accepted; }
    public synchronized long knowledgeCount() { return knowledgeHits; }

    /**
     * Remote half: POST this node's new game-console lines to a collector URL.
     * Bounded: maxLines per call, degrades silently when the endpoint/socket
     * is absent (use 11 must never crash a headless node). Returns lines sent.
     */
    public static int shipTo(Path consoleLog, String collectorUrl, int maxLines) {
        if (consoleLog == null || !Files.isRegularFile(consoleLog)) return 0;
        List<String> lines;
        try {
            lines = Files.readAllLines(consoleLog);
        } catch (IOException e) {
            return 0;
        }
        int from = Math.max(0, lines.size() - Math.max(maxLines, 1));
        int sent = 0;
        StringBuilder body = new StringBuilder();
        for (int i = from; i < lines.size(); i++) {
            body.append(lines.get(i)).append('\n');
            sent++;
        }
        if (sent == 0) return 0;
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(collectorUrl))
                .header("Content-Type", "text/plain; charset=utf-8")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body.toString()))
                .timeout(java.time.Duration.ofSeconds(10))
                .build();
            java.net.http.HttpResponse<String> resp =
                client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200 ? sent : 0;
        } catch (Exception e) {
            return 0; // endpoint absent / no mesh — degrade silently
        }
    }

    /** One-shot selftest probe: ingest a fix-line and an error-line, verify both stored. */
    public static boolean probe(LogCollector c) {
        try {
            boolean a = c.ingest("selftest-node", "fix(#114): taildrop shipping wired");
            if (!a) return false;
            boolean b = c.ingest("selftest-node", "ERROR: probe-error-line");
            if (!b) return false;
            if (c.ingest("selftest-node", "fix(#114): taildrop shipping wired")) return false; // dup dropped
            if (c.ingest("selftest-node", "   ")) return false; // blank dropped
            if (c.recent(5).size() < 2) return false;
            if (c.knowledgeCount() < 2) return false;
            return c.acceptedCount() >= 2;
        } catch (Exception e) {
            return false;
        }
    }
}