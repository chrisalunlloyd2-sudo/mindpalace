package com.mindpalace.backup;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SqliteWriter — ONE background thread owns ALL SQLite write statements for
 * the ledger DBs (telemetry.db / memory.db), so no caller thread (render or
 * agent) ever blocks inside sqlite-jdbc's NativeDB.step — the x621-sample
 * leaf of run004's 109 stalls (sum update_ms ~198 641, frames 700-1000).
 *
 * Design (single writer thread, WAL, batched transactions):
 *   - journal_mode=WAL + synchronous=NORMAL + 5s busy_timeout at open. WAL
 *     means legacy reader connections never block behind a write mid-frame.
 *   - Callers enqueue immutable rows ({@link PendingWrite}); the writer
 *     drains and commits up to {@link #MAX_BATCH} rows per transaction, so
 *     a burst costs one fsync instead of N.
 *   - Optional {@code extraSql} (e.g. a theta-prune DELETE) rides the same
 *     transaction — callers attach it only on cadenced flushes, never per row.
 *   - Queue is bounded: on overflow the row is DROPPED (shed load — ledger
 *     writes are best-effort by contract), a caller is never blocked.
 *   - {@link #close()} drains remaining rows bounded, then joins the thread.
 */
final class SqliteWriter {

    /** Maximum rows committed per transaction (cadence: 1 trickle, 64 burst). */
    static final int MAX_BATCH = 64;
    /** Bounded queue capacity; beyond this rows are shed, never block callers. */
    private static final int QUEUE_CAP = 8_192;
    /** Max wall-clock close() waits for the drain before giving up. */
    private static final long AWAIT_IDLE_MS = 5_000L;

    /** One pending write — immutable after enqueue (cross-thread safe). */
    static final class PendingWrite {
        final String sql;
        final Object[] args;
        /** Optional extra SQL executed in the SAME transaction (e.g. prune). */
        final String extraSql;

        PendingWrite(String sql, Object[] args, String extraSql) {
            this.sql = sql;
            this.args = args;
            this.extraSql = extraSql;
        }
    }

    private final BlockingQueue<PendingWrite> queue = new LinkedBlockingQueue<>(QUEUE_CAP);
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong dropped = new AtomicLong();
    private final String name;
    private final Path dbFile;
    private final Thread worker;
    /** Owned by the writer thread only (created there, closed there). */
    private Connection db;
    /** Set by the writer on any failed batch; cleared on a good commit. */
    private volatile boolean lastWriteFailed = false;

    SqliteWriter(String name, Path dbFile, String schemaSql) {
        this.name = name;
        this.dbFile = dbFile;
        this.worker = new Thread(this::run, "sqlite-writer-" + name);
        this.worker.setDaemon(true);
        this.worker.start();
    }

    private Connection openConnection() throws SQLException {
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
        try (Statement st = c.createStatement()) {
            // WAL: readers (recent(64) panel, code_seen seeding) never block.
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("PRAGMA busy_timeout=5000");
        }
        return c;
    }

    /** Enqueue one row; returns immediately (never touches the driver). */
    boolean submit(String sql, Object[] args) {
        return submit(sql, args, null);
    }

    /** Enqueue one row with an optional same-transaction extra statement. */
    boolean submit(String sql, Object[] args, String extraSql) {
        if (!running.get()) return false;
        if (!queue.offer(new PendingWrite(sql, args, extraSql))) {
            dropped.incrementAndGet(); // shed load — never block the caller
            return false;
        }
        return true;
    }

    /** Wait (bounded) until every submitted row has been handed to SQLite. */
    void awaitIdle() {
        long deadline = System.currentTimeMillis() + AWAIT_IDLE_MS;
        while (!queue.isEmpty() && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(10); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /** True when the most recent commit succeeded (or nothing failed yet). */
    boolean isHealthy() {
        return !lastWriteFailed;
    }

    /** Rows shed because the queue was full (best-effort ledger contract). */
    long droppedCount() {
        return dropped.get();
    }

    /** Rows still waiting to be written (diagnostics). */
    int pending() {
        return queue.size();
    }

    private void run() {
        try {
            db = openConnection();
            while (running.get()) {
                PendingWrite first = queue.poll(200, TimeUnit.MILLISECONDS);
                if (first == null) continue; // idle poll; keeps close() snappy
                List<PendingWrite> batch = new ArrayList<>(MAX_BATCH);
                batch.add(first);
                queue.drainTo(batch, MAX_BATCH - 1);
                writeBatch(batch);
            }
            // shutdown: drain the remainder unconditionally, then close.
            List<PendingWrite> rest = new ArrayList<>(queue.size());
            queue.drainTo(rest);
            while (!rest.isEmpty()) {
                List<PendingWrite> chunk = rest.size() > MAX_BATCH
                    ? rest.subList(0, MAX_BATCH) : rest;
                writeBatch(new ArrayList<>(chunk));
                if (rest.size() > MAX_BATCH) rest = rest.subList(MAX_BATCH, rest.size());
                else break;
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (SQLException e) {
            System.err.println("[" + name + "] writer thread failed: " + e.getMessage());
        } finally {
            if (db != null) {
                try { db.close(); } catch (SQLException ignored) {}
                db = null;
            }
        }
    }

    private void writeBatch(List<PendingWrite> batch) {
        try {
            db.setAutoCommit(false);
            try (PreparedStatement ps = db.prepareStatement(batch.get(0).sql)) {
                for (PendingWrite w : batch) {
                    bind(ps, w.args);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            // extraSql (prune) rides the same transaction when attached.
            for (PendingWrite w : batch) {
                if (w.extraSql != null) {
                    try (Statement st = db.createStatement()) {
                        st.execute(w.extraSql);
                    }
                    break; // at most one caller attaches an extra per flush
                }
            }
            db.commit();
            lastWriteFailed = false;
        } catch (SQLException e) {
            lastWriteFailed = true;
            try { db.rollback(); } catch (SQLException ignored) {}
            System.err.println("[" + name + "] batch write failed: " + e.getMessage());
        } finally {
            try { db.setAutoCommit(true); } catch (SQLException ignored) {}
        }
    }

    private void bind(PreparedStatement ps, Object[] args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            if (a == null) ps.setNull(i + 1, java.sql.Types.NULL);
            else if (a instanceof Long) ps.setLong(i + 1, (Long) a);
            else if (a instanceof Integer) ps.setInt(i + 1, (Integer) a);
            else ps.setString(i + 1, a.toString());
        }
    }

    /**
     * Close: stop accepting rows, wait for the queue to drain (bounded), then
     * join the writer. No interrupt — the 200 ms idle poll guarantees the
     * loop observes {@code running=false} promptly and still drains the
     * remainder; an interrupt could abort that final drain mid-batch.
     */
    void close() {
        running.set(false);
        awaitIdle();
        try { worker.join(AWAIT_IDLE_MS); } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}