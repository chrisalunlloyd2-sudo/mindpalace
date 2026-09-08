package com.mindpalace.world;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * TimeMachine (M3, step 121) — commit time slider backend.
 *
 * A room's books normally show the repo's CURRENT file state. The slider
 * lets the player walk back: pick a position N commits ago, and every book
 * in that room re-reads its file content as of that commit
 * (`git show <rev>:<path>`). Read-only history — the editor stays live for
 * the present state; leaving slider position 0 restores it.
 *
 * KISS: no index rebuilds, no checkout — content is fetched per-book on
 * demand and cached per (repo, rev) so scrubbing is instant after first hit.
 */
public final class TimeMachine {

    /** One entry in a repo's history (newest first). */
    public static final class Commit {
        public final String sha;      // short sha
        public final String subject;  // first line of message
        public final String when;     // relative date, e.g. "3 days ago"
        public final int epoch;       // commit time (unix seconds)
        Commit(String sha, String subject, String when, int epoch) {
            this.sha = sha; this.subject = subject; this.when = when; this.epoch = epoch;
        }
    }

    private final RepoMapper.GitRunner git;   // bounded-timeout git runner
    private final List<Commit> history = new ArrayList<>();
    private final List<String> cacheKeys = new ArrayList<>();
    private final List<String> cacheVals = new ArrayList<>();
    private String repoPath;
    private int position = 0; // 0 = present

    public TimeMachine(RepoMapper.GitRunner git) { this.git = git; }

    /**
     * Load the commit history for a repo (max 50). Returns false for
     * non-git dirs (demo fixtures without .git, missing dirs).
     */
    public boolean load(String repoPath) {
        this.repoPath = repoPath;
        history.clear();
        cacheKeys.clear();
        cacheVals.clear();
        position = 0;
        String out = git.run(new File(repoPath),
            "log", "-50", "--format=%h|%s|%ar|%ct");
        if (out == null || out.isEmpty()) return false;
        for (String line : out.split("\n")) {
            String[] p = line.split("\\|", 4);
            if (p.length == 4) {
                try {
                    history.add(new Commit(p[0], p[1], p[2], Integer.parseInt(p[3].trim())));
                } catch (NumberFormatException ignored) {}
            }
        }
        return !history.isEmpty();
    }

    public List<Commit> getHistory() { return history; }
    public int getPosition() { return position; }
    public int size() { return history.size(); }
    public boolean isActive() { return position > 0 && !history.isEmpty(); }

    /** Move the slider. 0 = present; k = the k-th commit back. Clamped. */
    public void setPosition(int pos) {
        position = Math.max(0, Math.min(pos, Math.max(0, history.size() - 1)));
    }

    /** Commit the slider currently points at (null when at present). */
    public Commit currentCommit() {
        return (position > 0 && position <= history.size()) ? history.get(position - 1) : null;
    }

    /**
     * File content as of the slider position. At position 0 the caller
     * should read from disk (present state, editable). Deeper: `git show`.
     */
    public String fileContentAt(String relPath) {
        if (!isActive()) return null;
        String rev = history.get(position - 1).sha;
        String key = rev + ":" + relPath;
        int i = cacheKeys.indexOf(key);
        if (i >= 0) return cacheVals.get(i);
        String out = git.run(new File(repoPath), "show", rev + ":" + relPath);
        if (out != null) {
            if (cacheKeys.size() > 200) { cacheKeys.remove(0); cacheVals.remove(0); }
            cacheKeys.add(key);
            cacheVals.add(out);
        }
        return out;
    }
}