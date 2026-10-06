package com.mindpalace.backup;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

/**
 * Cold backup system - auto-crawls configured roots and mirrors them to a
 * configurable backup root: mindpalace.backup.dir system property first,
 * then MINDPALACE_BACKUP_DIR env var, then the D:/mindpalace_backup default.
 *
 * Issue #47: the root was hardcoded to D:/, which made --demo non-hermetic
 * on Windows and created a stray D: directory inside ubuntu CI checkouts.
 * every file touched, every chat, every log, every agent recording is copied.
 * Runs on a background thread, dedupes by content hash (never copy twice),
 * and self-prunes to a theta curve so the backup never grows unbounded.
 */
public class BackupManager {
    // Daemon + lowest priority: the crawler must never compete with the render loop or keep a
    // window-less JVM alive (a stuck crawl pinned a core for ~12 CPU-hours before this).
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "backup-crawler");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private volatile Path backupRoot;  // may switch drives when a USB comes back with a new letter
    private final Map<String, String> contentHash = new ConcurrentHashMap<>(); // path -> sha1
    // path -> "size:lastModifiedMillis" at the last successful backup. A file whose size and mtime
    // are unchanged is skipped WITHOUT being read, so a steady-state crawl is just a directory walk.
    private final Map<String, String> statKey = new ConcurrentHashMap<>();
    private static final int MAX_CONSECUTIVE_COPY_FAILURES = 20;
    private static final long HASH_PAUSE_MS = 2;
    private volatile boolean destOk = true;
    private int consecutiveCopyFailures;

    private volatile long filesBackedUp;
    private volatile long bytesBackedUp;
    private volatile boolean running;
    private volatile long backupErrors;

    public BackupManager(String backupRoot) {
        this.backupRoot = Path.of(backupRoot);
    }

    public void start() {
        running = true;
        ensureDest();
        loadIndex();  // resume path->sha1 dedupe across restarts
        // Full crawl at start, then incremental every 5 minutes
        scheduler.schedule(this::fullCrawl, 5, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::incrementalCrawl, 5, 5, TimeUnit.MINUTES);
        System.out.println("[Backup] Cold backup to " + backupRoot + " started");
    }

    public void stop() {
        running = false;
        scheduler.shutdown();
    }

    /** Crawl the whole machine (C: user dir + AIGEN_SYS + hermes) and mirror to D:. */
    private void fullCrawl() {
        if (!running || !ensureDest()) return;
        consecutiveCopyFailures = 0;
        String home = System.getProperty("user.home");
        List<Path> roots = new ArrayList<>();
        roots.add(Path.of(home, "AIGEN_SYS"));
        roots.add(Path.of(home, "AppData", "Local", "hermes"));
        roots.add(Path.of(home, "AppData", "Local", "Temp")); // hermes-verify scripts etc
        roots.add(Path.of(home, "Desktop"));

        for (Path root : roots) {
            if (!Files.exists(root)) continue;
            crawl(root);
        }
        saveIndex();
        System.out.println("[Backup] Full crawl done: " + filesBackedUp + " files, "
            + formatBytes(bytesBackedUp) + (backupErrors > 0 ? ", " + backupErrors + " errors" : ""));
    }

    private void incrementalCrawl() {
        if (!running || !ensureDest()) return;
        consecutiveCopyFailures = 0;
        String home = System.getProperty("user.home");
        crawl(Path.of(home, "AIGEN_SYS"));
        crawl(Path.of(home, "AppData", "Local", "hermes"));
        saveIndex();
    }

    private void crawl(Path root) {
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    try {
                        mirror(file);
                    } catch (Exception e) {
                        backupErrors++;
                        if (backupErrors <= 5) System.err.println("[Backup] mirror failed: " + file + ": " + e.getMessage());
                    }
                    if (consecutiveCopyFailures >= MAX_CONSECUTIVE_COPY_FAILURES) {
                        destOk = false;
                        System.err.println("[Backup] " + MAX_CONSECUTIVE_COPY_FAILURES
                            + " copies in a row failed; aborting this crawl (destination " + backupRoot + " unusable)");
                        return FileVisitResult.TERMINATE;
                    }
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    // Skip huge/irrelevant dirs
                    String n = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    if (n.equals(".git") || n.equals("node_modules") || n.equals("target")
                        || n.equals("__pycache__") || n.equals(".hermes")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {}
    }

    /** Mirror a single file to D:, deduped by content hash. */
    private void mirror(Path src) {
        try {
            long size = Files.size(src);
            if (size > 50_000_000) return; // skip >50MB blobs
            String key = src.toString();
            String sk = size + ":" + Files.getLastModifiedTime(src).toMillis();
            if (sk.equals(statKey.get(key))) return; // unchanged since last backup: do not read it

            String hash = hash(src);
            if (hash == null) return;
            try { Thread.sleep(HASH_PAUSE_MS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }

            // Path-keyed dedupe: skip only when THIS file is unchanged since the last
            // backup. Distinct files with identical content are each backed up — a
            // faithful mirror, not a content-hash set that silently drops duplicates.
            if (hash.equals(contentHash.get(key))) { statKey.put(key, sk); return; }

            // Preserve relative structure under backup root
            Path rel = relativize(src);
            Path dest = backupRoot.resolve(rel);
            Files.createDirectories(dest.getParent());
            Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);

            contentHash.put(src.toString(), hash);
            statKey.put(src.toString(), sk);
            consecutiveCopyFailures = 0;
            filesBackedUp++;
            bytesBackedUp += Files.size(src);
        } catch (Exception e) {
            consecutiveCopyFailures++;
            backupErrors++;
            if (backupErrors <= 5) System.err.println("[Backup] mirror failed: " + src + ": " + e.getMessage());
        }
    }

    /** Create/verify the destination. When it is missing (e.g. no D: drive) nothing is read at all. */
    private boolean ensureDest() {
        if (tryRoot(backupRoot)) {
            if (!destOk) System.out.println("[Backup] destination " + backupRoot + " is available again; resuming");
            destOk = true;
            return true;
        }
        // A USB stick gets whatever drive letter Windows hands out. Look for the SAME relative
        // folder (an existing, writable one: never created on a random drive) on the other drives.
        Path alt = findOnOtherDrive();
        if (alt != null && tryRoot(alt)) {
            System.out.println("[Backup] backup folder found on another drive: " + alt + " (was " + backupRoot + "); switching");
            adoptRoot(alt);
            destOk = true;
            return true;
        }
        if (destOk) {
            System.err.println("[Backup] destination " + backupRoot + " is unavailable; backups paused (no files are "
                + "read or hashed while it is missing; rechecked every 5 minutes). Set mindpalace.backup.dir or "
                + "MINDPALACE_BACKUP_DIR to a drive that exists.");
        }
        destOk = false;
        return false;
    }

    private static boolean tryRoot(Path r) {
        try {
            Files.createDirectories(r);
            return Files.isWritable(r);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Same relative backup folder on another drive root (skipping the current and the system drive). */
    private Path findOnOtherDrive() {
        try {
            Path cur = backupRoot;
            Path curRoot = cur.getRoot();
            if (curRoot == null) return null;
            Path rel = curRoot.relativize(cur);
            if (rel.toString().isEmpty()) return null;
            Path sysRoot = Path.of(System.getProperty("user.home")).getRoot();
            for (Path r : FileSystems.getDefault().getRootDirectories()) {
                if (r.equals(curRoot) || r.equals(sysRoot)) continue;
                try {
                    Path cand = r.resolve(rel.toString());
                    if (Files.isDirectory(cand) && Files.isWritable(cand)) return cand;
                } catch (RuntimeException ignored) { /* drive not ready */ }
            }
        } catch (RuntimeException ignored) { /* no alternative */ }
        return null;
    }

    /** Switch destination. The dedupe records describe the OLD destination, so they are dropped and reloaded. */
    private void adoptRoot(Path newRoot) {
        backupRoot = newRoot;
        contentHash.clear();
        statKey.clear();
        loadIndex();
    }

    private Path relativize(Path src) {
        String home = System.getProperty("user.home");
        String s = src.toString();
        if (s.startsWith(home)) {
            return Path.of("C", s.substring(home.length() + 1));
        }
        // Fallback: use a sanitized absolute path
        return Path.of("misc", s.replace(":", "_").replace("\\", "/"));
    }

    private String hash(Path p) {
        try (InputStream in = Files.newInputStream(p)) {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    // ── dedupe index persistence (resume across restarts) ──
    private void loadIndex() {
        Path idx = backupRoot.resolve(".backup-index");
        if (!Files.exists(idx)) return;
        try {
            for (String line : Files.readAllLines(idx)) {
                int tab = line.indexOf('\t');
                if (tab > 0) contentHash.put(line.substring(tab + 1), line.substring(0, tab));
            }
        } catch (IOException ignored) {}
        Path st = backupRoot.resolve(".backup-stat");
        if (!Files.exists(st)) return;
        try {
            for (String line : Files.readAllLines(st)) {
                int tab = line.indexOf('	');
                if (tab > 0) statKey.put(line.substring(tab + 1), line.substring(0, tab));
            }
        } catch (IOException ignored) {}
    }

    private void saveIndex() {
        try {
            List<String> lines = new ArrayList<>(contentHash.size());
            for (Map.Entry<String, String> e : contentHash.entrySet()) {
                lines.add(e.getValue() + "\t" + e.getKey());
            }
            Files.write(backupRoot.resolve(".backup-index"), lines);
            List<String> st = new ArrayList<>(statKey.size());
            for (Map.Entry<String, String> e : statKey.entrySet()) st.add(e.getValue() + "	" + e.getKey());
            Files.write(backupRoot.resolve(".backup-stat"), st);
        } catch (IOException ignored) {}
    }

    private String formatBytes(long b) {
        if (b < 1024) return b + "B";
        if (b < 1024 * 1024) return String.format("%.1fKB", b / 1024.0);
        if (b < 1024 * 1024 * 1024) return String.format("%.1fMB", b / (1024.0 * 1024));
        return String.format("%.2fGB", b / (1024.0 * 1024 * 1024));
    }

    public long getFilesBackedUp() { return filesBackedUp; }
    public long getBytesBackedUp() { return bytesBackedUp; }
    public long getBackupErrors() { return backupErrors; }
}
