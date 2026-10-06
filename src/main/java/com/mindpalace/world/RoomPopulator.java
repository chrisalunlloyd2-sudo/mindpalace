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
                    return idx[0] >= MAX_BOOKS ? java.nio.file.FileVisitResult.TERMINATE : java.nio.file.FileVisitResult.CONTINUE;
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

        // First image file -> poster image (rendered on the room poster)
        if (room.getPosterImagePath() == null && isImageFile(p.getFileName().toString())) {
            room.setPosterImagePath(p.toAbsolutePath().toString());
        }
    }

    private static boolean isImageFile(String name) {
        String n = name.toLowerCase();
        return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg")
            || n.endsWith(".gif") || n.endsWith(".bmp");
    }
}
