package com.mindpalace.engine;

import java.nio.file.*;
import java.util.*;

/**
 * ToolExecutor — the REAL file tool the agents use to read/edit/create/delete
 * files in the current repo's local checkout.
 *
 * The autonomous agent originally wrote this class as a stub that returned
 * fake strings ("Content of X") without touching the filesystem. This is the
 * real implementation: every operation does actual I/O against a base path,
 * and is governed by the same three invariants as the rest of the swarm:
 *
 *   1. NEVER-TWICE  — a create/edit records the content hash; identical code
 *                     is never written twice (via MemoryManager).
 *   2. TELEMETRY     — every operation is recorded on the append-only ledger.
 *   3. DETERMINISTIC — no randomness; the same call always does the same thing.
 *
 * KISS: one class, one base path, four verbs. No GitHub here — that stays in
 * AgentManager; this is the local-checkout executor.
 */
public final class ToolExecutor {

    public static final class ToolResult {
        public final boolean success;
        public final String output;
        public ToolResult(boolean s, String o) { this.success = s; this.output = o; }
    }

    private final Path base;
    private final com.mindpalace.backup.MemoryManager memory;   // never-twice
    private final com.mindpalace.backup.Telemetry telemetry;   // append-only ledger

    public ToolExecutor(Path base, com.mindpalace.backup.MemoryManager memory,
                        com.mindpalace.backup.Telemetry telemetry) {
        this.base = base;
        this.memory = memory;
        this.telemetry = telemetry;
    }

    /** Resolve a filename against the base path, refusing to escape it. */
    private Path resolve(String filename) {
        Path p = base.resolve(filename).normalize();
        if (!p.startsWith(base)) throw new SecurityException("path escapes repo: " + filename);
        return p;
    }

    public ToolResult execute(String toolName, String[] args) {
        try {
            switch (toolName) {
                case "read":   return read(args);
                case "edit":   return write(args, false);
                case "create": return write(args, true);
                case "delete": return delete(args);
                default:       return new ToolResult(false, "Unknown tool: " + toolName);
            }
        } catch (Exception e) {
            return new ToolResult(false, e.getMessage());
        }
    }

    private ToolResult read(String[] a) throws Exception {
        Path p = resolve(a[0]);
        if (!Files.exists(p)) return new ToolResult(false, "no such file: " + a[0]);
        String c = Files.readString(p);
        return new ToolResult(true, "read " + a[0] + " (" + c.length() + " chars)");
    }

    private ToolResult write(String[] a, boolean mustBeNew) throws Exception {
        String filename = a[0];
        String content = a.length > 1 ? a[1] : "";
        Path p = resolve(filename);
        if (mustBeNew && Files.exists(p)) return new ToolResult(false, "already exists: " + filename);

        // Never-twice: refuse to write identical content twice.
        if (memory != null && !memory.recordCode(content, langOf(filename))) {
            return new ToolResult(false, "never-twice: identical code already written");
        }

        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
        if (telemetry != null) telemetry.record(com.mindpalace.backup.Telemetry.CODE,
            mustBeNew ? "create" : "edit", filename);
        return new ToolResult(true, (mustBeNew ? "created " : "edited ") + filename);
    }

    private ToolResult delete(String[] a) throws Exception {
        Path p = resolve(a[0]);
        boolean ok = Files.deleteIfExists(p);
        if (telemetry != null && ok) telemetry.record(com.mindpalace.backup.Telemetry.CODE,
            "delete", a[0]);
        return new ToolResult(ok, ok ? "deleted " + a[0] : "no such file: " + a[0]);
    }

    private static String langOf(String filename) {
        int i = filename.lastIndexOf('.');
        return i < 0 ? "txt" : filename.substring(i + 1);
    }
}
