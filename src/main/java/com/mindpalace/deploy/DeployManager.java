package com.mindpalace.deploy;

import com.mindpalace.world.Room;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Live deployment orchestrator.
 * On save: git add → commit → push → optional build script.
 * Runs async, reports status via callbacks.
 */
public class DeployManager {
    public enum Status { IDLE, STAGING, COMMITTING, PUSHING, BUILDING, DEPLOYED, FAILED }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile Status status = Status.IDLE;
    private volatile String statusMessage = "";
    private volatile String currentRepo = "";
    private volatile long deployStartTime;
    private DeployCallback callback;

    public interface DeployCallback {
        void onStatusChanged(Status status, String message);
    }

    public void setCallback(DeployCallback cb) { this.callback = cb; }

    public Status getStatus() { return status; }
    public String getStatusMessage() { return statusMessage; }
    public String getCurrentRepo() { return currentRepo; }
    public long getDeployStartTime() { return deployStartTime; }

    /** Trigger a deploy for a room's repo after a file save. */
    public void deploy(Room room, String commitMessage) {
        if (status != Status.IDLE && status != Status.DEPLOYED && status != Status.FAILED) return;

        executor.submit(() -> {
            try {
                String repoPath = findRepoPath(room);
                if (repoPath == null) {
                    setStatus(Status.FAILED, "Repo path not found for " + room.getRepoName());
                    return;
                }
                currentRepo = room.getRepoName();
                deployStartTime = System.currentTimeMillis();

                // Stage
                setStatus(Status.STAGING, "Staging changes...");
                runGit(repoPath, "add", "-A");

                // Commit
                setStatus(Status.COMMITTING, "Committing...");
                runGit(repoPath, "commit", "-m", commitMessage);

                // Push
                setStatus(Status.PUSHING, "Pushing to GitHub...");
                runGit(repoPath, "push");

                // Build (if build script exists)
                if (hasBuildScript(repoPath)) {
                    setStatus(Status.BUILDING, "Running build...");
                    runBuild(repoPath);
                }

                setStatus(Status.DEPLOYED, "Deployed " + room.getRepoName());
            } catch (Exception e) {
                setStatus(Status.FAILED, e.getMessage());
            }
        });
    }

    private String findRepoPath(Room room) {
        String[] roots = {
            System.getProperty("user.home") + "/AIGEN_SYS/repos/" + room.getRepoName(),
            System.getProperty("user.home") + "/AIGEN_SYS/repos/" + room.getRepoName().toLowerCase(),
        };
        for (String p : roots) {
            if (new File(p).exists()) return p;
        }
        return null;
    }

    private void runGit(String repoPath, String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(Arrays.asList(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(new File(repoPath));
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        int code = p.waitFor();
        if (code != 0 && !out.contains("nothing to commit") && !out.contains("Everything up-to-date")) {
            throw new RuntimeException("git " + args[0] + " failed: " + out.trim());
        }
    }

    private boolean hasBuildScript(String repoPath) {
        return new File(repoPath, "build.sh").exists()
            || new File(repoPath, "Makefile").exists()
            || new File(repoPath, "package.json").exists()
            || new File(repoPath, "pom.xml").exists();
    }

    private void runBuild(String repoPath) throws Exception {
        File f = new File(repoPath);
        if (new File(f, "build.sh").exists()) {
            Process p = new ProcessBuilder("bash", "build.sh").directory(f).redirectErrorStream(true).start();
            p.waitFor(30, TimeUnit.SECONDS);
        } else if (new File(f, "pom.xml").exists()) {
            Process p = new ProcessBuilder("mvn", "compile", "-q").directory(f).redirectErrorStream(true).start();
            p.waitFor(60, TimeUnit.SECONDS);
        } else if (new File(f, "package.json").exists()) {
            Process p = new ProcessBuilder("npm", "run", "build").directory(f).redirectErrorStream(true).start();
            p.waitFor(60, TimeUnit.SECONDS);
        }
    }

    private void setStatus(Status s, String msg) {
        status = s;
        statusMessage = msg;
        if (callback != null) callback.onStatusChanged(s, msg);
    }

    public void shutdown() { executor.shutdown(); }
}
