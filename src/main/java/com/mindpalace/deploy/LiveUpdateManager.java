package com.mindpalace.deploy;

import com.mindpalace.world.Book;
import com.mindpalace.world.Room;
import com.mindpalace.world.WorldBuilder;
import com.mindpalace.github.GitHubClient;
import org.joml.Vector3f;

import java.util.*;
import java.util.concurrent.*;

/**
 * Live update manager — watches for new repos/files and "builds" them into the
 * world without a restart (GTA Vice City loading style).
 *
 * Polls GitHub + local filesystem for changes. When a new repo appears, it
 * validates it (has a name, is a git repo / has files), then triggers a
 * construction animation that assembles the new room block-by-block.
 *
 * Modular: each update is a self-contained "module" that is validated before
 * being added to the live world.
 */
public class LiveUpdateManager {
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final GitHubClient github;
    private final WorldBuilder world;
    private final Set<String> knownRepos = ConcurrentHashMap.newKeySet();
    private final Set<String> knownBooks = ConcurrentHashMap.newKeySet();
    private volatile boolean running;

    /** Callback fired when a new module is ready to be animated into view. */
    public interface UpdateCallback {
        void onNewRoom(Room room);
        void onNewBook(Room room, String filename);
    }
    private UpdateCallback callback;

    public LiveUpdateManager(GitHubClient github, WorldBuilder world) {
        this.github = github;
        this.world = world;
    }

    public void setCallback(UpdateCallback cb) { this.callback = cb; }

    /** Snapshot the current world so we can detect deltas. */
    public void snapshot() {
        for (Room room : world.getRooms()) {
            knownRepos.add(room.getRepoName().toLowerCase());
            for (var book : room.getBooks()) {
                knownBooks.add(room.getRepoName().toLowerCase() + "/" + book.getFilePath());
            }
        }
    }

    public void start() {
        running = true;
        // Poll every 60 seconds for new repos/files (was 15s — a rate-limit magnet)
        scheduler.scheduleAtFixedRate(this::poll, 15, 60, TimeUnit.SECONDS);
        System.out.println("[LiveUpdate] Watching for new repos/files (60s poll)");
    }

    public void stop() {
        running = false;
        scheduler.shutdown();
    }

    private void poll() {
        if (!running) return;
        try {
            checkNewRepos();
        } catch (Exception e) {
            System.err.println("[LiveUpdate] Poll error: " + e.getMessage());
        }
    }

    /** Check GitHub for repos we haven't seen yet. */
    private void checkNewRepos() {
        if (github == null || !github.isAuthenticated()) return;
        try {
            List<Room> remote = github.fetchAllRepos();
            for (Room r : remote) {
                String key = r.getRepoName().toLowerCase();
                if (!knownRepos.contains(key)) {
                    knownRepos.add(key);
                    // Validate: must have a name and be non-empty
                    if (validateRepo(r)) {
                        System.out.println("[LiveUpdate] New repo detected: " + r.getRepoName());
                        r.setFogged(false); // newly created repos are visible immediately
                        // Populate books from GitHub so the new room's shelves aren't
                        // empty until restart (addRoom->populateRoom only handles local paths).
                        try {
                            List<Book> books = github.fetchRepoContents(r.getRepoName());
                            for (Book b : books) {
                                r.addBook(b);
                                if (callback != null) callback.onNewBook(r, b.getFilename());
                            }
                            System.out.println("[LiveUpdate] Populated " + books.size() + " books for " + r.getRepoName());
                        } catch (Exception e) {
                            System.err.println("[LiveUpdate] Book populate failed for " + r.getRepoName() + ": " + e.getMessage());
                        }
                        world.addRoom(r);
                        if (callback != null) callback.onNewRoom(r);
                    }
                }
            }
        } catch (Exception e) {
            // Never swallow silently — a JSON/auth/runtime error here would otherwise
            // make new repos stop appearing with zero signal.
            System.err.println("[LiveUpdate] checkNewRepos failed: " + e);
        }
    }

    /** Validate a repo before adding it to the live world. */
    private boolean validateRepo(Room room) {
        if (room.getRepoName() == null || room.getRepoName().isEmpty()) return false;
        if (room.getRepoName().length() > 64) return false;
        return true;
    }

    public boolean isRunning() { return running; }
}
