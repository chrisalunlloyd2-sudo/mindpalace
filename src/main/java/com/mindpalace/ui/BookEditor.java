package com.mindpalace.ui;

import com.mindpalace.render.Renderer;
import com.mindpalace.world.Book;
import com.mindpalace.world.Room;
import com.mindpalace.github.GitHubClient;
import org.joml.Vector3f;
import java.nio.file.*;
import java.util.*;

/**
 * Retro terminal book editor — green-on-black CRT aesthetic.
 * Renders as a floating panel in 3D + console terminal for text I/O.
 * 
 * Modes: VIEW, EDIT, CREATE, DELETE
 * Hotkeys: V=view, E=edit, N=new, D=delete, S=suggest, ESC=close
 */
public class BookEditor {
    public enum Mode { VIEW, EDIT, CREATE, DELETE, SUGGEST }

    private Book currentBook;
    private Room currentRoom;
    private GitHubClient github;
    private boolean isOpen;
    private Mode mode = Mode.VIEW;
    private String editBuffer;
    private String createFilename;
    private StringBuilder terminal = new StringBuilder();
    private boolean dirty;

    // Panel position in 3D
    private Vector3f panelPos = new Vector3f();
    private static final float PANEL_WIDTH = 3.0f;
    private static final float PANEL_HEIGHT = 2.0f;
    private static final float PANEL_DISTANCE = 2.5f;

    public BookEditor(GitHubClient github) {
        this.github = github;
    }

    public void open(Book book, Room room, Vector3f playerPos, Vector3f lookDir) {
        this.currentBook = book;
        this.currentRoom = room;
        this.isOpen = true;
        this.mode = Mode.VIEW;
        this.dirty = false;

        // Position panel in front of player
        panelPos.set(playerPos).add(
            lookDir.x * PANEL_DISTANCE,
            lookDir.y * PANEL_DISTANCE,
            lookDir.z * PANEL_DISTANCE);

        // Load content
        if (book.getContent() == null && book.getFilePath() != null) {
            loadContent();
        }

        printHeader();
        printContent();
        printHelp();
    }

    public void close() {
        isOpen = false;
        currentBook = null;
        mode = Mode.VIEW;
        editBuffer = null;
        createFilename = null;
        terminal.setLength(0);
    }

    // ── Actions ──

    public void actionView() {
        mode = Mode.VIEW;
        terminal.setLength(0);
        printHeader();
        printContent();
        printHelp();
    }

    public void actionEdit() {
        if (currentBook == null) return;
        mode = Mode.EDIT;
        editBuffer = currentBook.getContent() != null ? currentBook.getContent() : "";
        terminal.setLength(0);
        println("╔══════════════════════════════════════════╗");
        println("║  EDIT MODE — " + padRight(currentBook.getFilename(), 24) + " ║");
        println("╠══════════════════════════════════════════╣");
        println("║  Type your changes below.               ║");
        println("║  :w = save    :q = discard    :h = help ║");
        println("╚══════════════════════════════════════════╝");
        println("");
        println("─── FILE CONTENT ───");
        println(editBuffer);
        println("─── END ───");
    }

    public void actionCreate() {
        mode = Mode.CREATE;
        createFilename = "new_file.txt";
        editBuffer = "";
        terminal.setLength(0);
        println("╔══════════════════════════════════════════╗");
        println("║  CREATE NEW BOOK                        ║");
        println("╠══════════════════════════════════════════╣");
        println("║  :n <name> = set filename               ║");
        println("║  :w = create & save    :q = cancel      ║");
        println("╚══════════════════════════════════════════╝");
        println("");
        println("Filename: " + createFilename);
        println("Content: (empty — start typing or use :w)");
    }

    public void actionDelete() {
        if (currentBook == null || currentRoom == null) return;
        mode = Mode.DELETE;
        terminal.setLength(0);
        println("╔══════════════════════════════════════════╗");
        println("║  CONFIRM DELETE                         ║");
        println("╠══════════════════════════════════════════╣");
        println("║  File: " + padRight(currentBook.getFilePath(), 34) + " ║");
        println("║  Repo: " + padRight(currentRoom.getRepoName(), 34) + " ║");
        println("║                                          ║");
        println("║  :y = confirm    :n = cancel            ║");
        println("╚══════════════════════════════════════════╝");
    }

    public void actionSuggest() {
        mode = Mode.SUGGEST;
        terminal.setLength(0);
        println("╔══════════════════════════════════════════╗");
        println("║  AI SUGGESTIONS — " + padRight(currentBook != null ? currentBook.getFilename() : "?", 22) + " ║");
        println("╠══════════════════════════════════════════╣");
        println("║  Based on file analysis:                ║");
        println("║                                          ║");

        if (currentBook != null) {
            String lang = currentBook.getLanguage();
            String name = currentBook.getFilename();
            println("║  • Add docstring to " + padRight(trunc(name, 20), 20) + " ║");
            println("║  • Extract magic numbers to constants   ║");
            println("║  • Add error handling for edge cases    ║");
            if ("Python".equals(lang)) {
                println("║  • Add type hints (PEP 484)             ║");
                println("║  • Convert to async where applicable    ║");
            } else if ("Java".equals(lang)) {
                println("║  • Add null-safety annotations           ║");
                println("║  • Extract interface from class         ║");
            } else if ("JavaScript".equals(lang)) {
                println("║  • Convert to ES6 arrow functions       ║");
                println("║  • Add JSDoc type annotations           ║");
            }
            println("║  • Add unit tests                       ║");
            println("║  • Improve variable naming              ║");
        }
        println("║                                          ║");
        println("║  :a = apply suggestion #    :q = back   ║");
        println("╚══════════════════════════════════════════╝");
    }

    public void handleCommand(String cmd) {
        if (!isOpen) return;

        switch (mode) {
            case VIEW:
                handleViewCommand(cmd);
                break;
            case EDIT:
                handleEditCommand(cmd);
                break;
            case CREATE:
                handleCreateCommand(cmd);
                break;
            case DELETE:
                handleDeleteCommand(cmd);
                break;
            case SUGGEST:
                if (cmd.equals(":q")) actionView();
                break;
        }
    }

    private void handleViewCommand(String cmd) {
        switch (cmd) {
            case ":e": actionEdit(); break;
            case ":n": actionCreate(); break;
            case ":d": actionDelete(); break;
            case ":s": actionSuggest(); break;
            case ":q": close(); break;
        }
    }

    private void handleEditCommand(String cmd) {
        if (cmd.equals(":w")) {
            saveEdit();
        } else if (cmd.equals(":q")) {
            actionView();
        } else if (cmd.equals(":h")) {
            println("Commands: :w=save :q=discard");
        } else if (cmd.startsWith(":")) {
            println("Unknown: " + cmd);
        } else {
            // Append to edit buffer
            if (editBuffer == null) editBuffer = "";
            editBuffer += cmd + "\n";
            dirty = true;
            println("+ " + cmd);
        }
    }

    private void handleCreateCommand(String cmd) {
        if (cmd.startsWith(":n ")) {
            createFilename = cmd.substring(3).trim();
            println("Filename set to: " + createFilename);
        } else if (cmd.equals(":w")) {
            createBook();
        } else if (cmd.equals(":q")) {
            actionView();
        } else if (!cmd.startsWith(":")) {
            if (editBuffer == null) editBuffer = "";
            editBuffer += cmd + "\n";
            dirty = true;
            println("+ " + cmd);
        }
    }

    private void handleDeleteCommand(String cmd) {
        if (cmd.equals(":y")) {
            deleteBook();
        } else if (cmd.equals(":n")) {
            actionView();
        }
    }

    // ── File operations ──

    private void saveEdit() {
        if (currentBook == null || currentRoom == null || editBuffer == null) return;

        // Save locally
        if (currentRoom.getLocalPath() != null) {
            try {
                Path fp = Path.of(currentRoom.getLocalPath(), currentBook.getFilePath());
                Files.writeString(fp, editBuffer);
                println("[OK] Saved locally: " + fp);
            } catch (Exception e) {
                println("[ERR] Local save: " + e.getMessage());
            }
        }

        // Push to GitHub
        if (github != null && github.isAuthenticated()) {
            try {
                boolean ok = github.upsertFile(currentRoom.getRepoName(),
                    currentBook.getFilePath(), editBuffer,
                    "MindPalace: Update " + currentBook.getFilePath(),
                    currentBook.getSha());
                if (ok) println("[OK] Pushed to GitHub");
                else println("[ERR] GitHub push failed");
            } catch (Exception e) {
                println("[ERR] GitHub: " + e.getMessage());
            }
        }

        currentBook.setContent(editBuffer);
        dirty = false;
        actionView();
    }

    private void createBook() {
        if (currentRoom == null || createFilename == null) return;
        String content = editBuffer != null ? editBuffer : "";

        Book newBook = new Book(createFilename, createFilename);
        newBook.setContent(content);
        newBook.setLanguage(Book.detectLanguage(createFilename));
        newBook.setSizeBytes(content.length());

        if (currentRoom.getLocalPath() != null) {
            try {
                Path fp = Path.of(currentRoom.getLocalPath(), createFilename);
                Files.writeString(fp, content);
                println("[OK] Created: " + fp);
            } catch (Exception e) {
                println("[ERR] Local create: " + e.getMessage());
            }
        }

        if (github != null && github.isAuthenticated()) {
            try {
                github.upsertFile(currentRoom.getRepoName(), createFilename, content,
                    "MindPalace: Create " + createFilename, null);
                println("[OK] Created on GitHub");
            } catch (Exception e) {
                println("[ERR] GitHub: " + e.getMessage());
            }
        }

        currentRoom.addBook(newBook);
        currentBook = newBook;
        actionView();
    }

    private void deleteBook() {
        if (currentBook == null || currentRoom == null) return;

        if (currentRoom.getLocalPath() != null) {
            try {
                Files.deleteIfExists(Path.of(currentRoom.getLocalPath(), currentBook.getFilePath()));
                println("[OK] Deleted locally");
            } catch (Exception e) {
                println("[ERR] Local delete: " + e.getMessage());
            }
        }

        if (github != null && github.isAuthenticated()) {
            try {
                github.deleteFile(currentRoom.getRepoName(), currentBook.getFilePath(),
                    currentBook.getSha(), "MindPalace: Delete " + currentBook.getFilePath());
                println("[OK] Deleted from GitHub");
            } catch (Exception e) {
                println("[ERR] GitHub: " + e.getMessage());
            }
        }

        currentRoom.getBooks().remove(currentBook);
        println("[OK] Book removed from shelf");
        close();
    }

    private void loadContent() {
        if (currentRoom.getLocalPath() != null) {
            try {
                Path fp = Path.of(currentRoom.getLocalPath(), currentBook.getFilePath());
                currentBook.setContent(Files.readString(fp));
                return;
            } catch (Exception ignored) {}
        }
        if (github != null && github.isAuthenticated()) {
            try {
                String content = github.fetchFileContent(currentRoom.getRepoName(), currentBook.getFilePath());
                if (content != null) currentBook.setContent(content);
            } catch (Exception ignored) {}
        }
    }

    // ── Terminal output ──

    private void printHeader() {
        println("╔══════════════════════════════════════════╗");
        println("║  MIND PALACE BOOK VIEWER                ║");
        println("╠══════════════════════════════════════════╣");
        if (currentBook != null) {
            println("║  File: " + padRight(trunc(currentBook.getFilePath(), 32), 32) + " ║");
            println("║  Repo: " + padRight(currentRoom != null ? trunc(currentRoom.getRepoName(), 32) : "?", 32) + " ║");
            println("║  Lang: " + padRight(currentBook.getLanguage(), 32) + " ║");
            println("║  Size: " + padRight(formatSize(currentBook.getSizeBytes()), 32) + " ║");
        }
        println("╚══════════════════════════════════════════╝");
    }

    private void printContent() {
        if (currentBook == null) return;
        String content = currentBook.getContent();
        if (content == null || content.isEmpty()) {
            println("\n  (empty file)\n");
            return;
        }
        println("");
        println("─── FILE CONTENT ───");
        String[] lines = content.split("\n");
        int max = Math.min(lines.length, 40);
        for (int i = 0; i < max; i++) {
            println(String.format("%4d │ %s", i + 1, trunc(lines[i], 70)));
        }
        if (lines.length > max) {
            println("  ... (" + (lines.length - max) + " more lines)");
        }
        println("─── END ───");
    }

    private void printHelp() {
        println("");
        println("╔══════════════════════════════════════════╗");
        println("║  :e=Edit  :n=New  :d=Delete  :s=Suggest║");
        println("║  :q=Close                               ║");
        println("╚══════════════════════════════════════════╝");
    }

    private void println(String s) {
        terminal.append(s).append("\n");
        System.out.println(s);
    }

    // ── Render ──

    public void render(Renderer r) {
        if (!isOpen) return;
        // Dark semi-transparent panel floating in front of player
        r.drawCube(panelPos, new Vector3f(PANEL_WIDTH, PANEL_HEIGHT, 0.05f), Renderer.TEX_BOOK_GREY);
        // Border frame
        float bt = 0.04f;
        float hw = PANEL_WIDTH / 2f, hh = PANEL_HEIGHT / 2f;
        r.drawCube(new Vector3f(panelPos.x - hw, panelPos.y, panelPos.z), new Vector3f(bt, PANEL_HEIGHT, bt), Renderer.TEX_DOOR);
        r.drawCube(new Vector3f(panelPos.x + hw, panelPos.y, panelPos.z), new Vector3f(bt, PANEL_HEIGHT, bt), Renderer.TEX_DOOR);
        r.drawCube(new Vector3f(panelPos.x, panelPos.y - hh, panelPos.z), new Vector3f(PANEL_WIDTH, bt, bt), Renderer.TEX_DOOR);
        r.drawCube(new Vector3f(panelPos.x, panelPos.y + hh, panelPos.z), new Vector3f(PANEL_WIDTH, bt, bt), Renderer.TEX_DOOR);
    }

    // ── Helpers ──

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private String trunc(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    private String padRight(String s, int n) {
        if (s.length() >= n) return s.substring(0, n);
        return s + " ".repeat(n - s.length());
    }

    // ── Getters ──

    public boolean isOpen() { return isOpen; }
    public Mode getMode() { return mode; }
    public Book getCurrentBook() { return currentBook; }
    public String getEditBuffer() { return editBuffer; }
    public String getTerminal() { return terminal.toString(); }
    public boolean isDirty() { return dirty; }
    public void clearDirty() { dirty = false; }
}
