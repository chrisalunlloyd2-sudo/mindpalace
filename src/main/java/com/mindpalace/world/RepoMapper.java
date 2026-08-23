package com.mindpalace.world;

import java.io.File;
import java.util.List;

/**
 * Scans local repos and GitHub API to build the room list.
 * Maps each repo to a Room with metadata.
 */
public class RepoMapper {
    private static final String AIGEN_SYS = "C:/Users/viper/AIGEN_SYS/repos";
    private static final String VIPER_NOTES = "C:/Users/viper/OneDrive/ViperAI_Notes";

    public void scanRepos(List<Room> rooms) {
        // Scan local repos dir — override with MIND_PALACE_REPOS_DIR env var
        // or -Dmindpalace.repos=... (falls back to the historical AIGEN_SYS path)
        String reposDir = System.getenv("MIND_PALACE_REPOS_DIR");
        if (reposDir == null || reposDir.isEmpty()) reposDir = System.getProperty("mindpalace.repos", AIGEN_SYS);
        File aigenDir = new File(reposDir);
        if (aigenDir.exists() && aigenDir.isDirectory()) {
            File[] repos = aigenDir.listFiles(File::isDirectory);
            if (repos != null) {
                for (File repoDir : repos) {
                    File gitDir = new File(repoDir, ".git");
                    if (gitDir.exists()) {
                        Room room = new Room(repoDir.getName());
                        room.setLocalPath(repoDir.getAbsolutePath());
                        detectRepoMeta(room, repoDir);
                        rooms.add(room);
                    }
                }
            }
        }

        // Add ViperAI_Notes as a special room (override with MIND_PALACE_NOTES_DIR)
        String notesPath = System.getenv("MIND_PALACE_NOTES_DIR");
        if (notesPath == null || notesPath.isEmpty()) notesPath = VIPER_NOTES;
        File notesDir = new File(notesPath);
        if (notesDir.exists()) {
            Room notesRoom = new Room("ViperAI_Notes");
            notesRoom.setLocalPath(notesPath);
            notesRoom.setLanguage("Markdown");
            notesRoom.setPrivate(true);
            notesRoom.setRepoDescription("Personal AI notes and knowledge base");
            rooms.add(notesRoom);
        }

        System.out.println("[RepoMapper] Found " + rooms.size() + " repos locally");
    }

    /**
     * Extract the canonical repo name from a git remote URL.
     * Handles: https://github.com/user/repo.git, git@github.com:user/repo.git,
     * ssh://git@github.com/user/repo.git, and bare paths.
     */
    private String extractRepoName(String url) {
        if (url == null) return null;
        String u = url.trim();
        // Strip trailing .git
        if (u.endsWith(".git")) u = u.substring(0, u.length() - 4);
        // Strip trailing slash
        if (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        // Take the last path segment
        int slash = u.lastIndexOf('/');
        if (slash >= 0) u = u.substring(slash + 1);
        // Handle scp-like git@host:user/repo
        int colon = u.lastIndexOf(':');
        if (colon >= 0) u = u.substring(colon + 1);
        return u;
    }

    private void detectRepoMeta(Room room, File repoDir) {
        // Try to read git remote
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "-C", repoDir.getAbsolutePath(), "remote", "get-url", "origin");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String url = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
            if (!url.isEmpty() && p.waitFor() == 0) {
                room.setRemoteUrl(url);
                // Extract the REAL GitHub repo name from the remote URL — this is
                // the canonical name (fixes local-folder case/hyphen/underscore
                // variants like sims1337backend vs SIMS1337-BACKEND).
                String realName = extractRepoName(url);
                if (realName != null && !realName.isEmpty()) {
                    room.setRepoName(realName);
                }
            }
        } catch (Exception e) {
            // No remote — local only
        }

        // Get last commit
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "-C", repoDir.getAbsolutePath(),
                "log", "-1", "--format=%s (%ar)");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String msg = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
            if (!msg.isEmpty() && p.waitFor() == 0) {
                room.setLastCommit(msg);
            }
        } catch (Exception e) {
            // No commits yet
        }

        // Detect primary language by file extensions (recursive, bounded depth + file
        // cap). A top-level-only scan mislabels most repos as "Markdown" — README.md
        // is often the only recognized file at the root, while the real source lives
        // in src/main/java, src/main/python, etc.
        int[] c = new int[6];  // py, java, js, html, md, totalScanned
        countExtensions(repoDir, 5, c);
        int py = c[0], java = c[1], js = c[2], html = c[3], md = c[4];
        if (java > py && java > js) room.setLanguage("Java");
        else if (py > js) room.setLanguage("Python");
        else if (js > 0) room.setLanguage("JavaScript");
        else if (html > 0) room.setLanguage("HTML");
        else if (md > 0) room.setLanguage("Markdown");
    }

    /** Recursively count file extensions (bounded depth + file cap), skipping VCS/build dirs. */
    private void countExtensions(File dir, int depth, int[] c) {
        if (depth < 0 || c[5] > 500) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                String n = f.getName();
                if (n.equals(".git") || n.equals("node_modules") || n.equals("target") || n.equals("__pycache__")) continue;
                countExtensions(f, depth - 1, c);
                continue;
            }
            c[5]++;
            String name = f.getName().toLowerCase();
            if (name.endsWith(".py")) c[0]++;
            else if (name.endsWith(".java")) c[1]++;
            else if (name.endsWith(".js")) c[2]++;
            else if (name.endsWith(".html")) c[3]++;
            else if (name.endsWith(".md")) c[4]++;
        }
    }
}
