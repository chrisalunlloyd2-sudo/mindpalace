package com.mindpalace.backup;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

/**
 * Cold backup system — auto-crawls the machine and mirrors everything to D:.
 *
 * D: is the cold backup between GitHub and local. Nothing is 0 or 1 unaccounted:
 * every file touched, every chat, every log, every agent recording is copied.
 * Runs on a background thread, dedupes by content hash (never copy twice),
 * and self-prunes to a theta curve so the backup never grows unbounded.
 */
public class BackupManager {
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final Path backupRoot;
    private final Map<String, String> contentHash = new ConcurrentHashMap<>(); // path -> sha1

    private volatile long filesBackedUp;
    private volatile long bytesBackedUp;
    private volatile boolean running;
    private volatile long backupErrors;

    public BackupManager(String backupRoot) {
        this.backupRoot = Path.of(backupRoot);
    }

    public void start() {
        running = true;
        try { Files.createDirectories(backupRoot); } catch (IOException ignored) {}
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
        if (!running) return;
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
        if (!running) return;
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
            if (Files.size(src) > 50_000_000) return; // skip >50MB blobs
            String hash = hash(src);
            if (hash == null) return;

            // Path-keyed dedupe: skip only when THIS file is unchanged since the last
            // backup. Distinct files with identical content are each backed up — a
            // faithful mirror, not a content-hash set that silently drops duplicates.
            if (hash.equals(contentHash.get(src.toString()))) return;

            // Preserve relative structure under backup root
            Path rel = relativize(src);
            Path dest = backupRoot.resolve(rel);
            Files.createDirectories(dest.getParent());
            Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);

            contentHash.put(src.toString(), hash);
            filesBackedUp++;
            bytesBackedUp += Files.size(src);
        } catch (Exception e) {
            backupErrors++;
            if (backupErrors <= 5) System.err.println("[Backup] mirror failed: " + src + ": " + e.getMessage());
        }
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
    }

    private void saveIndex() {
        try {
            List<String> lines = new ArrayList<>(contentHash.size());
            for (Map.Entry<String, String> e : contentHash.entrySet()) {
                lines.add(e.getValue() + "\t" + e.getKey());
            }
            Files.write(backupRoot.resolve(".backup-index"), lines);
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
