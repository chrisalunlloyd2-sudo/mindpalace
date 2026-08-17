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
        // Scan local AIGEN_SYS repos
        File aigenDir = new File(AIGEN_SYS);
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

        // Add ViperAI_Notes as a special room
        File notesDir = new File(VIPER_NOTES);
        if (notesDir.exists()) {
            Room notesRoom = new Room("ViperAI_Notes");
            notesRoom.setLocalPath(VIPER_NOTES);
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
            String url = new String(p.getInputStream().readAllBytes()).trim();
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
            String msg = new String(p.getInputStream().readAllBytes()).trim();
            if (!msg.isEmpty() && p.waitFor() == 0) {
                room.setLastCommit(msg);
            }
        } catch (Exception e) {
            // No commits yet
        }

        // Detect primary language by file extensions
        File[] files = repoDir.listFiles();
        if (files != null) {
            int py = 0, java = 0, js = 0, html = 0, md = 0;
            for (File f : files) {
                String name = f.getName().toLowerCase();
                if (name.endsWith(".py")) py++;
                else if (name.endsWith(".java")) java++;
                else if (name.endsWith(".js")) js++;
                else if (name.endsWith(".html")) html++;
                else if (name.endsWith(".md")) md++;
            }
            if (java > py && java > js) room.setLanguage("Java");
            else if (py > js) room.setLanguage("Python");
            else if (js > 0) room.setLanguage("JavaScript");
            else if (html > 0) room.setLanguage("HTML");
            else if (md > 0) room.setLanguage("Markdown");
        }
    }
}
