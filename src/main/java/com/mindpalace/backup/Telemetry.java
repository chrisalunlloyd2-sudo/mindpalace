package com.mindpalace.backup;

import java.nio.file.*;
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

    private final Connection db;
    private final Map<String, Long> lastEmit = new HashMap<>();
    private final long minIntervalMs;

    // Theta curve: soft cap on rows; oldest pruned beyond it.
    private static final int THETA_ROWS = 200_000;

    public Telemetry(Path dataDir) {
        this(dataDir, 0L); // 0 = no pacing by default (callers pace explicitly)
    }

    public Telemetry(Path dataDir, long minIntervalMs) {
        this.minIntervalMs = minIntervalMs;
        Connection c = null;
        try {
            Files.createDirectories(dataDir);
            c = DriverManager.getConnection("jdbc:sqlite:" + dataDir.resolve("telemetry.db"));
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS events ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "ts INTEGER NOT NULL,"
                    + "category TEXT NOT NULL,"
                    + "event TEXT NOT NULL,"
                    + "detail TEXT)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_events_cat ON events(category)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_events_ts ON events(ts)");
            }
        } catch (Exception e) {
            System.err.println("[Telemetry] DB init failed: " + e.getMessage());
            c = null;
        }
        this.db = c;
    }

    /**
     * Record an event. Returns true if recorded, false if PACED (suppressed
     * because the same category fired too recently). Append-only: never
     * updates or deletes an existing row.
     */
    public synchronized boolean record(String category, String event, String detail) {
        if (db == null) return false;
        long now = System.currentTimeMillis();
        if (minIntervalMs > 0) {
            Long last = lastEmit.get(category);
            if (last != null && now - last < minIntervalMs) return false; // paced
        }
        lastEmit.put(category, now);
        try (PreparedStatement ps = db.prepareStatement(
                "INSERT INTO events(ts, category, event, detail) VALUES(?,?,?,?)")) {
            ps.setLong(1, now);
            ps.setString(2, category);
            ps.setString(3, event);
            ps.setString(4, detail == null ? null : (detail.length() > 2000 ? detail.substring(0, 2000) : detail));
            ps.executeUpdate();
            prune();
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    /** Convenience: record with no detail. */
    public boolean record(String category, String event) {
        return record(category, event, null);
    }

    /** The N most recent events (newest first). */
    public synchronized List<String[]> recent(int n) {
        List<String[]> out = new ArrayList<>();
        if (db == null) return out;
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT ts, category, event, detail FROM events ORDER BY id DESC LIMIT ?")) {
            ps.setInt(1, n);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new String[]{String.valueOf(rs.getLong(1)), rs.getString(2),
                        rs.getString(3), rs.getString(4)});
                }
            }
        } catch (SQLException ignored) {}
        return out;
    }

    /** All events in a category (oldest first). */
    public synchronized List<String[]> byCategory(String category) {
        List<String[]> out = new ArrayList<>();
        if (db == null) return out;
        try (PreparedStatement ps = db.prepareStatement(
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

    /** Total event count. */
    public synchronized long count() {
        if (db == null) return 0;
        try (Statement st = db.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM events")) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (SQLException e) { return 0; }
    }

    /** Per-category counts, as a compact "cat=count" summary. */
    public synchronized String summary() {
        if (db == null) return "telemetry: offline";
        StringBuilder sb = new StringBuilder();
        try (Statement st = db.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT category, COUNT(*) FROM events GROUP BY category ORDER BY 2 DESC")) {
            while (rs.next()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(rs.getString(1)).append('=').append(rs.getLong(2));
            }
        } catch (SQLException ignored) {}
        return sb.length() == 0 ? "telemetry: empty" : sb.toString();
    }

    /** Theta-curve prune: drop oldest rows beyond the soft cap. */
    private void prune() {
        try (Statement st = db.createStatement()) {
            st.execute("DELETE FROM events WHERE id <= ("
                + "SELECT id FROM events ORDER BY id DESC LIMIT 1 OFFSET " + THETA_ROWS + ")");
        } catch (SQLException ignored) {}
    }

    public void stop() {
        if (db != null) { try { db.close(); } catch (SQLException ignored) {} }
    }
}
