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

    private void detectRepoMeta(Room room, File repoDir) {
        // Try to read git remote
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "-C", repoDir.getAbsolutePath(), "remote", "get-url", "origin");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String url = new String(p.getInputStream().readAllBytes()).trim();
            if (!url.isEmpty() && p.waitFor() == 0) {
                room.setRemoteUrl(url);
            }
        } catch (Exception e) {
            // No remote — local only
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
