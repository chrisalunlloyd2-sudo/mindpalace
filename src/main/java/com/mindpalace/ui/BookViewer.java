package com.mindpalace.ui;

import com.mindpalace.world.Book;
import com.mindpalace.world.Room;
import com.mindpalace.github.GitHubClient;

/**
 * Book viewer overlay — opens when player clicks a book.
 * Shows file contents with syntax highlighting, edit/delete/new options.
 * 
 * In the 3D world, this renders as a large floating panel in front of the player.
 * For now, it outputs to console and will get a proper ImGui-style overlay in Phase 2.
 */
public class BookViewer {
    private Book currentBook;
    private Room currentRoom;
    private GitHubClient github;
    private boolean isOpen = false;
    private boolean isEditing = false;
    private String editBuffer;

    public BookViewer(GitHubClient github) {
        this.github = github;
    }

    public void open(Book book, Room room) {
        this.currentBook = book;
        this.currentRoom = room;
        this.isOpen = true;
        this.isEditing = false;

        // Load content if not cached
        if (book.getContent() == null && book.getFilePath() != null) {
            loadContent();
        }

        System.out.println("\n========================================");
        System.out.println("  BOOK: " + book.getFilename());
        System.out.println("  Repo: " + (room != null ? room.getRepoName() : "unknown"));
        System.out.println("  Lang: " + book.getLanguage());
        System.out.println("  Size: " + formatSize(book.getSizeBytes()));
        System.out.println("  Path: " + book.getFilePath());
        System.out.println("========================================");
        if (book.getContent() != null) {
            System.out.println(truncate(book.getContent(), 2000));
        }
        System.out.println("========================================");
        System.out.println("[E] Edit  [D] Delete  [N] New Book  [ESC] Close");
    }

    public void close() {
        this.isOpen = false;
        this.currentBook = null;
        this.isEditing = false;
        this.editBuffer = null;
    }

    public void startEdit() {
        if (currentBook == null) return;
        this.isEditing = true;
        this.editBuffer = currentBook.getContent() != null ? currentBook.getContent() : "";
        System.out.println("\n[EDIT MODE] Editing: " + currentBook.getFilename());
        System.out.println("(Changes will be saved to local file and pushed to GitHub)");
    }

    public void saveEdit(String newContent) {
        if (currentBook == null || currentRoom == null) return;

        // Save locally
        if (currentRoom.getLocalPath() != null) {
            try {
                java.nio.file.Path filePath = java.nio.file.Path.of(
                    currentRoom.getLocalPath(), currentBook.getFilePath());
                java.nio.file.Files.writeString(filePath, newContent);
                System.out.println("[BookViewer] Saved locally: " + filePath);
            } catch (Exception e) {
                System.err.println("[BookViewer] Local save failed: " + e.getMessage());
            }
        }

        // Push to GitHub
        if (github != null && github.isAuthenticated() && currentRoom.getRemoteUrl() != null) {
            try {
                String repoName = currentRoom.getRepoName();
                String commitMsg = "MindPalace: Update " + currentBook.getFilePath();
                boolean ok = github.upsertFile(repoName, currentBook.getFilePath(),
                    newContent, commitMsg, currentBook.getSha());
                if (ok) {
                    System.out.println("[BookViewer] Pushed to GitHub: " + repoName);
                }
            } catch (Exception e) {
                System.err.println("[BookViewer] GitHub push failed: " + e.getMessage());
            }
        }

        currentBook.setContent(newContent);
        this.isEditing = false;
    }

    public void deleteBook() {
        if (currentBook == null || currentRoom == null) return;

        // Delete locally
        if (currentRoom.getLocalPath() != null) {
            try {
                java.nio.file.Path filePath = java.nio.file.Path.of(
                    currentRoom.getLocalPath(), currentBook.getFilePath());
                java.nio.file.Files.deleteIfExists(filePath);
                System.out.println("[BookViewer] Deleted locally: " + filePath);
            } catch (Exception e) {
                System.err.println("[BookViewer] Local delete failed: " + e.getMessage());
            }
        }

        // Delete from GitHub
        if (github != null && github.isAuthenticated() && currentRoom.getRemoteUrl() != null) {
            try {
                String repoName = currentRoom.getRepoName();
                String commitMsg = "MindPalace: Delete " + currentBook.getFilePath();
                boolean ok = github.deleteFile(repoName, currentBook.getFilePath(),
                    currentBook.getSha(), commitMsg);
                if (ok) {
                    System.out.println("[BookViewer] Deleted from GitHub: " + repoName);
                }
            } catch (Exception e) {
                System.err.println("[BookViewer] GitHub delete failed: " + e.getMessage());
            }
        }

        // Remove from room
        currentRoom.getBooks().remove(currentBook);
        close();
    }

    public void createNewBook(String filename, String content) {
        if (currentRoom == null) return;

        Book newBook = new Book(filename, filename);
        newBook.setContent(content);
        newBook.setLanguage(Book.detectLanguage(filename));
        newBook.setSizeBytes(content.length());

        // Save locally
        if (currentRoom.getLocalPath() != null) {
            try {
                java.nio.file.Path filePath = java.nio.file.Path.of(
                    currentRoom.getLocalPath(), filename);
                java.nio.file.Files.writeString(filePath, content);
                System.out.println("[BookViewer] Created locally: " + filePath);
            } catch (Exception e) {
                System.err.println("[BookViewer] Local create failed: " + e.getMessage());
            }
        }

        // Push to GitHub
        if (github != null && github.isAuthenticated() && currentRoom.getRemoteUrl() != null) {
            try {
                String repoName = currentRoom.getRepoName();
                String commitMsg = "MindPalace: Create " + filename;
                boolean ok = github.upsertFile(repoName, filename, content, commitMsg, null);
                if (ok) {
                    System.out.println("[BookViewer] Created on GitHub: " + repoName);
                }
            } catch (Exception e) {
                System.err.println("[BookViewer] GitHub create failed: " + e.getMessage());
            }
        }

        currentRoom.addBook(newBook);
        System.out.println("[BookViewer] New book created: " + filename);
    }

    private void loadContent() {
        // Try local first
        if (currentRoom.getLocalPath() != null) {
            try {
                java.nio.file.Path filePath = java.nio.file.Path.of(
                    currentRoom.getLocalPath(), currentBook.getFilePath());
                String content = java.nio.file.Files.readString(filePath);
                currentBook.setContent(content);
                return;
            } catch (Exception ignored) {}
        }

        // Try GitHub
        if (github != null && github.isAuthenticated() && currentRoom.getRemoteUrl() != null) {
            try {
                String content = github.fetchFileContent(
                    currentRoom.getRepoName(), currentBook.getFilePath());
                if (content != null) {
                    currentBook.setContent(content);
                }
            } catch (Exception e) {
                System.err.println("[BookViewer] GitHub fetch failed: " + e.getMessage());
            }
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private String truncate(String s, int maxLen) {
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "\n... (truncated, " + (s.length() - maxLen) + " more chars)";
    }

    public boolean isOpen() { return isOpen; }
    public boolean isEditing() { return isEditing; }
    public Book getCurrentBook() { return currentBook; }
    public String getEditBuffer() { return editBuffer; }
}
