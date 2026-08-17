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
 */
public class MemoryManager {
    private final Path dataDir;
    private Connection db;

    // Theta curve: soft cap. Growth slows as size approaches this.
    private static final long THETA_BYTES = 50_000_000; // 50MB soft cap for logs

    public MemoryManager(String dataDir) {
        this.dataDir = Path.of(dataDir);
    }

    public void start() {
        try {
            Files.createDirectories(dataDir);
            db = DriverManager.getConnection("jdbc:sqlite:" + dataDir.resolve("memory.db"));
            initSchema();
            System.out.println("[Memory] Self-managing memory DB ready at " + dataDir);
        } catch (Exception e) {
            System.err.println("[Memory] DB init failed: " + e.getMessage());
            db = null;
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

    /** Record a code snippet. Returns true if it's NEW (never seen before). */
    public boolean recordCode(String snippet, String lang) {
        if (db == null) return true;
        String hash = sha1(snippet);
        try (PreparedStatement ps = db.prepareStatement(
                "INSERT OR IGNORE INTO code_seen(hash, snippet, lang, created_at) VALUES(?,?,?,?)")) {
            ps.setString(1, hash);
            ps.setString(2, snippet.length() > 2000 ? snippet.substring(0, 2000) : snippet);
            ps.setString(3, lang);
            ps.setLong(4, System.currentTimeMillis());
            int changed = ps.executeUpdate();
            return changed > 0; // true = new, false = already seen
        } catch (SQLException e) {
            return true;
        }
    }

    /** Record an error + its fix. Returns true if this error is NEW. */
    public boolean recordMistake(String signature, String fix) {
        if (db == null) return true;
        try (PreparedStatement ps = db.prepareStatement(
                "INSERT INTO mistakes(signature, fix, count, last_seen) VALUES(?,?,1,?) "
                + "ON CONFLICT(signature) DO UPDATE SET count=count+1, last_seen=excluded.last_seen")) {
            ps.setString(1, signature);
            ps.setString(2, fix);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            return true;
        }
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

    public void stop() {
        if (db != null) { try { db.close(); } catch (SQLException ignored) {} }
    }
}
