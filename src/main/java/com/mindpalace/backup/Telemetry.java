package com.mindpalace.backup;

import java.sql.*;
import java.util.*;

/**
 * Telemetry — the game's unified, append-only event ledger.
 *
 * Every meaningful autonomous event (agent action, quorum vote, DePIN job,
 * genetic evolution tick, issue raise, shop purchase, code write, mistake) is
 * recorded as a timestamped, monotonic, queryable row. This is the "telemetry"
 * the Architect keeps asking for: one stream you can replay to see exactly
 * what the swarm did, when, and in what order.
 *
 * Design (KISS, mirrors MemoryManager):
 *   - SQLite at <dataDir>/telemetry.db, same dir as memory.db + genome.json.
 *   - APPEND-ONLY: rows are never updated or deleted (only theta-pruned).
 *   - PACED: a per-category minimum interval so a runaway agent can't flood
 *     the ledger (cellular pacing — the same idea as the issue stream).
 *   - QUERYABLE: recent(n), byCategory(cat), counts, and a compact summary.
 *
 * PERF-A4 (t_2fb1bde4, outcome task): record() used to run INSERT + a prune
 * DELETE synchronously on the calling (render) thread — every commit was a
 * NativeDB.step the game loop sat inside (run004: x621 samples, 109 stalls in
 * frames 700-1000). Now:
 *   - record() appends to an in-memory mirror + hands the INSERT row to a
 *     SqliteWriter (single writer thread, WAL, batched transactions). The
 *     calling thread never touches the driver.
 *   - recent(n)/count()/summary() are answered from the mirror + running
 *     counters, so callers get synchronous, race-free semantics with zero I/O.
 *   - The theta-prune runs every 512th recorded event, inside the writer
 *     thread (never per record, never while a frame is mid-flight).
 *   - byCategory (legacy, no current callers) still reads through a
 *     reader-owned connection; WAL allows that read to run alongside writes.
 */
public final class Telemetry {

    /** Event categories — the fixed vocabulary of the swarm. */
    public static final String AGENT   = "agent";
    public static final String QUORUM   = "quorum";
    public static final String DEPIN    = "depin";
    public static final String GENETIC  = "genetic";
    public static final String ISSUE    = "issue";
    public static final String SHOP     = "shop";
    public static final String CODE     = "code";
    public static final String MISTAKE  = "mistake";
    public static final String SYSTEM   = "system";

    private final Map<String, Long> lastEmit = new HashMap<>();
    private final long minIntervalMs;

    // Theta curve: soft cap on rows; oldest pruned beyond it.
    private static final int THETA_ROWS = 200_000;

    // PERF-A4: async single-writer + in-memory mirror ---------------
    private final SqliteWriter writer;
    /** Reader-owned connection (WAL): legacy byCategory + boot seeding. */
    private final Connection reader;
    /** Ring of the most recent recorded rows, newest LAST (mirror). */
    private final ArrayDeque<String[]> mirror = new ArrayDeque<>();
    /** Mirror cap — recent(64) panel headroom without unbounded growth. */
    private static final int MIRROR_CAP = 1024;
    /** Boot-seeded event count + per-category counts (counters, not queries). */
    private long approxCount;
    private final Map<String, Long> catCounts = new HashMap<>();
    /** Prune cadence: one DELETE per this many recorded events (writer-side). */
    private static final int PRUNE_EVERY = 512;
    private long pruneCounter;
    /** The recurring theta-prune statement (executed inside the writer). */
    private static final String PRUNE_SQL =
        "DELETE FROM events WHERE id <= ("
        + "SELECT id FROM events ORDER BY id DESC LIMIT 1 OFFSET " + THETA_ROWS + ")";
    private static final String INSERT_SQL =
        "INSERT INTO events(ts, category, event, detail) VALUES(?,?,?,?)";
    /** Rows shed when the writer's queue was full (best-effort contract). */
    private long droppedEvents;

    public Telemetry(java.nio.file.Path dataDir) {
        this(dataDir, 0L); // 0 = no pacing by default (callers pace explicitly)
    }

    public Telemetry(java.nio.file.Path dataDir, long minIntervalMs) {
        this.minIntervalMs = minIntervalMs;
        java.nio.file.Path file = null;
        Connection r = null;
        SqliteWriter w = null;
        try {
            java.nio.file.Files.createDirectories(dataDir);
            file = dataDir.resolve("telemetry.db");
            r = DriverManager.getConnection("jdbc:sqlite:" + file);
            try (Statement st = r.createStatement()) {
                st.execute("PRAGMA busy_timeout=5000");
                st.execute("CREATE TABLE IF NOT EXISTS events ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "ts INTEGER NOT NULL,"
                    + "category TEXT NOT NULL,"
                    + "event TEXT NOT NULL,"
                    + "detail TEXT)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_events_cat ON events(category)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_events_ts ON events(ts)");
            }
            seedCounters(r);
        } catch (Exception e) {
            System.err.println("[Telemetry] DB init failed: " + e.getMessage());
            r = null;
        }
        this.reader = r;
        this.writer = file == null ? null : new SqliteWriter("telemetry", file, null);
        // writer field must be final — assign via a local then single store.
        // (SqliteWriter is opened AFTER schema exists; its own connection adds WAL.)
    }

    private void seedCounters(Connection c) {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT category, COUNT(*) FROM events GROUP BY category")) {
            while (rs.next()) {
                catCounts.put(rs.getString(1), rs.getLong(2));
            }
        } catch (SQLException ignored) {}
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM events")) {
            if (rs.next()) approxCount = rs.getLong(1);
        } catch (SQLException ignored) {}
    }

    /**
     * Record an event. Returns true if recorded, false if PACED (suppressed
     * because the same category fired too recently). Append-only: never
     * updates or deletes an existing row.
     *
     * PERF-A4: never blocks on SQLite — mirror append + async writer enqueue.
     */
    public synchronized boolean record(String category, String event, String detail) {
        if (writer == null) return false;
        long now = System.currentTimeMillis();
        if (minIntervalMs > 0) {
            Long last = lastEmit.get(category);
            if (last != null && now - last < minIntervalMs) return false; // paced
        }
        lastEmit.put(category, now);
        String truncated = detail == null ? null
            : (detail.length() > 2000 ? detail.substring(0, 2000) : detail);
        // Mirror FIRST (so count/recent/summary are immediately consistent).
        if (mirror.size() >= MIRROR_CAP) mirror.pollFirst();
        mirror.addLast(new String[]{String.valueOf(now), category, event, truncated});
        approxCount++;
        catCounts.merge(category, 1L, Long::sum);
        // Prune every PRUNE_EVERY events — in-transaction with that row,
        // executed on the writer thread, never on the caller (PERF-A4).
        boolean withPrune = (++pruneCounter % PRUNE_EVERY) == 0;
        boolean accepted = withPrune
            ? writer.submit(INSERT_SQL,
                new Object[]{now, category, event, truncated}, PRUNE_SQL)
            : writer.submit(INSERT_SQL, new Object[]{now, category, event, truncated});
        if (!accepted) droppedEvents++; // writer queue full: shed (best-effort)
        return true;
    }

    /** Convenience: record with no detail. */
    public boolean record(String category, String event) {
        return record(category, event, null);
    }

    /** The N most recent events (newest first) — served from the mirror. */
    public synchronized List<String[]> recent(int n) {
        List<String[]> out = new ArrayList<>(Math.min(n, Math.max(mirror.size(), 1)));
        Iterator<String[]> it = mirror.descendingIterator();
        while (it.hasNext() && out.size() < n) {
            out.add(it.next());
        }
        if (out.isEmpty() && reader != null) {
            // Fresh process, nothing recorded yet — fall back to the ledger.
            try (PreparedStatement ps = reader.prepareStatement(
                    "SELECT ts, category, event, detail FROM events ORDER BY id DESC LIMIT ?")) {
                ps.setInt(1, n);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new String[]{String.valueOf(rs.getLong(1)), rs.getString(2),
                            rs.getString(3), rs.getString(4)});
                    }
                }
            } catch (SQLException ignored) {}
        }
        return out;
    }

    /** All events in a category (oldest first) — legacy read path. */
    public synchronized List<String[]> byCategory(String category) {
        List<String[]> out = new ArrayList<>();
        if (reader == null) return out;
        try (PreparedStatement ps = reader.prepareStatement(
                "SELECT ts, event, detail FROM events WHERE category=? ORDER BY id")) {
            ps.setString(1, category);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new String[]{String.valueOf(rs.getLong(1)), rs.getString(2), rs.getString(3)});
                }
            }
        } catch (SQLException ignored) {}
        return out;
    }

    /** Total event count — running counter (boot-seeded + recorded). */
    public synchronized long count() {
        return approxCount;
    }

    /** Per-category counts, as a compact "cat=count" summary (running counter). */
    public synchronized String summary() {
        if (writer == null) return "telemetry: offline";
        if (catCounts.isEmpty()) return "telemetry: empty";
        StringBuilder sb = new StringBuilder();
        List<Map.Entry<String, Long>> sorted = new ArrayList<>(catCounts.entrySet());
        sorted.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        for (Map.Entry<String, Long> e : sorted) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /**
     * Theta-curve prune — every-512th-event cadence, executed inside the
     * writer thread with that row's transaction (see record()). Kept as a
     * method for documentation parity; the live pruning is the PRUNE_SQL
     * constant attached in record().
     */
    public long droppedEvents() {
        return droppedEvents;
    }

    /**
     * Stop the async writer: drain queued rows to disk (bounded), then close.
     */
    public void stop() {
        if (writer != null) writer.close();
        if (reader != null) {
            try { reader.close(); } catch (SQLException ignored) {}
        }
    }
}