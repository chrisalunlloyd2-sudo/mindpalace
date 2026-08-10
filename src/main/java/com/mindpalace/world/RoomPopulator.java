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

        try (Stream<Path> stream = Files.walk(dir.toPath(), 3)) {
            stream
                .filter(Files::isRegularFile)
                .filter(p -> !p.toString().contains(".git" + File.separator))
                .filter(p -> !p.toString().contains("node_modules"))
                .filter(p -> !p.toString().contains("__pycache__"))
                .filter(p -> !p.toString().contains(".hermes"))
                .limit(MAX_BOOKS)
                .forEach(p -> {
                    String relPath = dir.toPath().relativize(p).toString();
                    Book book = new Book(p.getFileName().toString(), relPath);
                    book.setLanguage(Book.detectLanguage(relPath));

                    try {
                        book.setSizeBytes(Files.size(p));
                    } catch (IOException ignored) {}

                    room.addBook(book);
                });
        } catch (IOException e) {
            System.err.println("[RoomPopulator] Error scanning " + path + ": " + e.getMessage());
        }

        System.out.println("[RoomPopulator] " + room.getRepoName() + ": " + room.getBooks().size() + " books");
    }
}
