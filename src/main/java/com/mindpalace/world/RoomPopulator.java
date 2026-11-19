package com.mindpalace.world;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Populates a room with books (files from the repo).
 * Scans the repo directory and creates Book entities.
 */
public class RoomPopulator {
    private static final int MAX_BOOKS = 200; // max files to show per room
    /** P1 t_3d7b6095: how deep the poster hunt runs (mirror of the walk depth). */
    private static final int MAX_BOOKS_IMAGES = 2000; // walk cap for the image scoring pass

    public void populateRoom(Room room) {
        String path = room.getLocalPath();
        if (path == null) return;

        File dir = new File(path);
        if (!dir.exists()) return;

        // Startup cost: Files.walk listed every .git object / node_modules entry (depth 3) BEFORE the
        // filters rejected them, for each of ~70 repos. walkFileTree skips those subtrees outright,
        // visits files in the same order, and stops once MAX_BOOKS files were accepted.
        try {
            final int[] idx = {0};
            Files.walkFileTree(dir.toPath(), java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class), 3,
                new java.nio.file.SimpleFileVisitor<Path>() {
                @Override
                public java.nio.file.FileVisitResult preVisitDirectory(Path d, java.nio.file.attribute.BasicFileAttributes a) {
                    return excluded(d.toString() + File.separator)
                        ? java.nio.file.FileVisitResult.SKIP_SUBTREE : java.nio.file.FileVisitResult.CONTINUE;
                }
                @Override
                public java.nio.file.FileVisitResult visitFileFailed(Path f, IOException e) {
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
                @Override
                public java.nio.file.FileVisitResult visitFile(Path p, java.nio.file.attribute.BasicFileAttributes a) {
                    if (!a.isRegularFile() || excluded(p.toString())) return java.nio.file.FileVisitResult.CONTINUE;
                    addBook(room, dir, p, idx);
                    // P1 t_3d7b6095: score every image seen, not just the first —
                    // the walk still stops at MAX_BOOKS accepted files, but images
                    // are tracked past that so a repo whose screenshots live
                    // beyond the first 200 files still gets one.
                    notePosterCandidate(room, p, idx);
                    return idx[0] >= MAX_BOOKS_IMAGES ? java.nio.file.FileVisitResult.TERMINATE
                                                      : java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            System.err.println("[RoomPopulator] Error scanning " + path + ": " + e.getMessage());
        }

        System.out.println("[RoomPopulator] " + room.getRepoName() + ": " + room.getBooks().size() + " books");
    }

    /** Same exclusions the old stream filters applied to every path. */
    private static boolean excluded(String s) {
        return s.contains(".git" + File.separator) || s.contains("node_modules")
            || s.contains("__pycache__") || s.contains(".hermes");
    }

    private void addBook(Room room, File dir, Path p, int[] idx) {
        String relPath = dir.toPath().relativize(p).toString();
        Book book = new Book(p.getFileName().toString(), relPath);
        book.setLanguage(Book.detectLanguage(relPath));
        // Round-robin across the 3 bookcase walls so each book is placed exactly once
        // (back/left/right) and its clickable position matches where it's drawn.
        book.setWallIndex(idx[0]++ % 3);

        try {
            book.setSizeBytes(Files.size(p));
        } catch (IOException ignored) {}

        room.addBook(book);

        // Test files -> lab devices (schema: Test -> Lab device)
        if (LabDevice.isTestFile(p.getFileName().toString())) {
            LabDevice dev = new LabDevice(
                p.getFileName().toString(),
                room.getRepoName(),
                LabDevice.guessStatus(p.getFileName().toString()));
            room.addLabDevice(dev);
        }
    }

    // ── P1 t_3d7b6095: best-looking poster candidate ──
    // The old rule took the FIRST image in walk order, which could be a 16px
    // test-fixture png buried in a test folder while a proper hero screenshot
    // sat further down. Track the highest-scoring image instead; scores come
    // from the filename (hero/screenshot/logo win), the parent folder
    // (docs/ images/ assets/) and the format (raster beats sprite/anim).
    private java.nio.file.Path bestPoster;
    private int bestPosterScore = Integer.MIN_VALUE;
    private long bestPosterBytes;

    /** Record one walked file as a poster candidate (or ignore it). */
    void notePosterCandidate(Room room, Path p, int[] idx) {
        String name = p.getFileName().toString();
        if (!isImageFile(name)) return;
        long size;
        try { size = Files.size(p); } catch (IOException ignored) { size = 0; }
        int score = scorePosterPath(dirPathString(p));
        if (score > bestPosterScore
            || (score == bestPosterScore && size > bestPosterBytes)) {
            bestPoster = p;
            bestPosterScore = score;
            bestPosterBytes = size;
        }
    }

    private String dirPathString(Path p) { return p.toString(); }

    /** Higher is a better poster; pure, so the selftest can pin it. */
    public static int scorePosterPath(String absPath) {
        int s = 0;
        String n = absPath.toLowerCase(java.util.Locale.ROOT).replace('\\', '/');
        String file = n.substring(n.lastIndexOf('/') + 1);
        String parent = n.substring(0, Math.max(0, n.lastIndexOf('/')));
        for (String k : new String[]{"hero", "screenshot", "preview", "logo", "banner", "showcase", "demo"}) {
            if (file.contains(k)) s += 4;
        }
        for (String k : new String[]{"docs", "images", "image", "assets", "screenshots", "media", "art"}) {
            if (parent.contains(k)) s += 3;
        }
        if (file.endsWith(".png") || file.endsWith(".jpg") || file.endsWith(".jpeg")) s += 2;
        // Test fixtures, favicons and pixel sprites read poorly at poster size.
        if (file.contains("test") || file.contains("fixture") || file.contains("favicon")) s -= 4;
        if (file.startsWith("icon") || file.contains("pixel") || file.contains("sprite")) s -= 2;
        return s;
    }

    /** Apply the winning candidate after the walk (keeps setPosterImagePath single-assignment). */
    public void finishPopulate(Room room) {
        if (room.getPosterImagePath() != null) return;   // remote/cache poster already set — local never overrides
        if (bestPoster != null) {
            room.setPosterImagePath(bestPoster.toAbsolutePath().toString());
        }
    }

    private static boolean isImageFile(String name) {
        String n = name.toLowerCase();
        return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg")
            || n.endsWith(".gif") || n.endsWith(".bmp");
    }
}
