package com.mindpalace.backup;

import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/**
 * Self-managing memory — theta-curve pruning + "never make code twice" DB.
 *
 * 1. Logs/DBs self-prune when they hit the theta curve (a soft cap where
 *    growth slows asymptotically), so models never degrade from context bloat.
 * 2. A SQLite DB records every code snippet ever written (content-hashed),
 *    so identical code is never generated twice.
 * 3. A "never make mistakes twice" DB records error signatures + fixes.
 *
 * PERF-A4 (t_2fb1bde4 outcome task): recordCode() used to run its
 * INSERT OR IGNORE synchronously on the calling (agent/edit) thread — one
 * more NativeDB.step in the frame path family. Now:
 *   - The never-twice verdict is answered from an in-memory hash set seeded
 *     from code_seen at start() — O(hash) per call, zero driver I/O.
 *   - New snippets are INSERTed via the shared single-writer thread
 *     (SqliteWriter, WAL + batched transactions).
 */
public class MemoryManager {
    private final Path dataDir;
    private Connection db;            // legacy reader connection (read-only use)

    // Theta curve: soft cap. Growth slows as size approaches this.
    private static final long THETA_BYTES = 50_000_000; // 50MB soft cap for logs

    // PERF-A4: async writer + in-memory never-twice mirror ------------
    private SqliteWriter writer;
    /** Content hashes of every snippet (memory set + durable ledger). */
    private final Set<String> seenHashes = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public MemoryManager(String dataDir) {
        this.dataDir = Path.of(dataDir);
    }

    public void start() {
        try {
            Files.createDirectories(dataDir);
            Path file = dataDir.resolve("memory.db");
            db = DriverManager.getConnection("jdbc:sqlite:" + file);
            initSchema();
            // PERF-A4: single background writer owns every write; WAL keeps
            // this reader connection usable while rows land asynchronously.
            try (Statement st = db.createStatement()) {
                st.execute("PRAGMA busy_timeout=5000");
            }
            seedHashes();
            writer = new SqliteWriter("memory", file, null);
            System.out.println("[Memory] Self-managing memory DB ready at " + dataDir);
        } catch (Exception e) {
            System.err.println("[Memory] DB init failed: " + e.getMessage());
            db = null;
            writer = null;
        }
    }

    private void initSchema() throws SQLException {
        try (Statement st = db.createStatement()) {
            // Never-make-code-twice: content-hash -> code
            st.execute("CREATE TABLE IF NOT EXISTS code_seen ("
                + "hash TEXT PRIMARY KEY, snippet TEXT, lang TEXT, created_at INTEGER)");
            // Never-make-mistakes-twice: error signature -> fix
            st.execute("CREATE TABLE IF NOT EXISTS mistakes ("
                + "signature TEXT PRIMARY KEY, fix TEXT, count INTEGER, last_seen INTEGER)");
        }
    }

    /** Load existing content-hashes once (never-twice survives restarts). */
    private void seedHashes() {
        if (db == null) return;
        try (Statement st = db.createStatement();
             ResultSet rs = st.executeQuery("SELECT hash FROM code_seen")) {
            while (rs.next()) seenHashes.add(rs.getString(1));
        } catch (SQLException ignored) {}
    }

    /**
     * Record a code snippet. Returns true if it's NEW (never seen before).
     *
     * PERF-A4: verdict from the in-memory set (synchronous, O(1)); the INSERT
     * rides the async single-writer thread.
     */
    public boolean recordCode(String snippet, String lang) {
        String hash = sha1(snippet);
        if (!seenHashes.add(hash)) return false; // already seen (memory verdict)
        if (writer == null) return true;         // degraded mode = always new
        boolean ok = writer.submit(
            "INSERT OR IGNORE INTO code_seen(hash, snippet, lang, created_at) VALUES(?,?,?,?)",
            new Object[]{hash,
                snippet.length() > 2000 ? snippet.substring(0, 2000) : snippet,
                lang, System.currentTimeMillis()});
        if (!ok) seenHashes.remove(hash); // shed: allow retry later
        return true;
    }

    /**
     * Record an error + its fix. Returns true if this error is NEW. Asynchronous
     * via the single-writer thread; the synchronous boolean is legacy semantics
     * (callers never branch on it in-tree).
     */
    public boolean recordMistake(String signature, String fix) {
        if (writer == null) return true;
        return writer.submit(
            "INSERT INTO mistakes(signature, fix, count, last_seen) VALUES(?,?,1,?) "
            + "ON CONFLICT(signature) DO UPDATE SET count=count+1, last_seen=excluded.last_seen",
            new Object[]{signature, fix, System.currentTimeMillis()});
    }

    /** Look up a known fix for an error signature (never make mistakes twice). */
    public String lookupFix(String signature) {
        if (db == null) return null;
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT fix FROM mistakes WHERE signature=?")) {
            ps.setString(1, signature);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            return null;
        }
    }

    /** Prune logs to the theta curve — keep newest, drop oldest beyond soft cap. */
    public void pruneLogs(Path logDir) {
        if (!Files.exists(logDir)) return;
        try {
            List<Path> logs = new ArrayList<>();
            try (var stream = Files.list(logDir)) {
                stream.filter(Files::isRegularFile).forEach(logs::add);
            }
            logs.sort(Comparator.comparingLong(this::lastModified).reversed());

            long total = 0;
            for (Path log : logs) {
                long size = size(log);
                total += size;
                if (total > THETA_BYTES) {
                    // Beyond theta curve — delete oldest
                    try { Files.deleteIfExists(log); } catch (IOException ignored) {}
                }
            }
        } catch (IOException ignored) {}
    }

    private long lastModified(Path p) {
        try { return Files.getLastModifiedTime(p).toMillis(); } catch (IOException e) { return 0; }
    }
    private long size(Path p) {
        try { return Files.size(p); } catch (IOException e) { return 0; }
    }

    private String sha1(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return Integer.toHexString(s.hashCode()); }
    }

    /** Stop the async writer (drains queued rows, bounded), close readers. */
    public void stop() {
        if (writer != null) writer.close();
        if (db != null) { try { db.close(); } catch (SQLException ignored) {} }
    }
}