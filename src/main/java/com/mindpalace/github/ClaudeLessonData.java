package com.mindpalace.github;

import com.mindpalace.agent.KnowledgeGraph;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * H36b: Claude-lesson ingest — reads scripts/claude_commit_watch.py's
 * latest.json (mindpalace_memory/claude_watch/) and surfaces what the bots
 * learned from Claude's code as per-repo KnowledgeGraph attributes.
 *
 * <p>WHY THIS EXISTS. Claude's code in this estate does NOT land on the
 * default branch: a live audit of chrisalunlloyd2-sudo/mindpalace showed
 * 0 Claude-authored commits on {@code main} while 11 {@code claude/*}
 * branches and several PRs carried the work. Any watcher that only reads
 * the default branch therefore learns nothing. The Python watcher walks PRs
 * and branch heads as well, and this class is the game-side ingest of the
 * result.
 *
 * <p>Attributes written per repo key:
 * <ul>
 *   <li>{@code claude_lessons} — number of Claude lessons harvested</li>
 *   <li>{@code claude_recent_subject} — the newest lesson's subject (the
 *       rationale behind the change, which is what a bot can imitate)</li>
 *   <li>{@code claude_pr_lessons} / {@code claude_branch_lessons} — how the
 *       lessons were reached (PR vs branch), so a bot can weight the
 *       provenance</li>
 *   <li>{@code claude_test_touched} — how many lessons touched a test</li>
 * </ul>
 *
 * <p>PRIVACY. Lessons from private repos carry a pseudonymised repo key
 * (e.g. {@code repo-ff4b9d8d909a}) and a scrubbed subject — the real repo
 * name never reaches this class. A public repo keeps its real name.
 *
 * <p>Additive only: RepoSweepData and every other ingest are untouched, and
 * the attributes live OUTSIDE the KG build() swap so they survive
 * LiveUpdate rebuilds (same pattern as the sweep and health attributes).
 */
public final class ClaudeLessonData {

    private ClaudeLessonData() { }

    /** One harvested lesson (subset of the Python record). */
    public static final class Lesson {
        public final String repoKey;      // pseudonym for private repos
        public final String subject;      // scrubbed rationale
        public final String role;         // "author" | "co_author"
        public final String origin;       // "default_branch" | "pull_request" | "branch"
        public final String originRef;    // branch name or "#<pr>"
        public final boolean isPrivate;
        public final boolean touchedTest;
        public final int filesChanged;

        Lesson(String repoKey, String subject, String role, String origin,
               String originRef, boolean isPrivate, boolean touchedTest,
               int filesChanged) {
            this.repoKey = repoKey;
            this.subject = subject;
            this.role = role;
            this.origin = origin;
            this.originRef = originRef;
            this.isPrivate = isPrivate;
            this.touchedTest = touchedTest;
            this.filesChanged = filesChanged;
        }
    }

    /** Parse a latest.json body into {@link Lesson} records. Null-safe. */
    public static List<Lesson> parse(String json) {
        if (json == null || json.isBlank()) return java.util.List.of();
        try {
            com.google.gson.JsonArray items = com.google.gson.JsonParser
                .parseString(json).getAsJsonObject()
                .getAsJsonArray("items");
            java.util.List<Lesson> out = new java.util.ArrayList<>();
            for (com.google.gson.JsonElement el : items) {
                com.google.gson.JsonObject o = el.getAsJsonObject();
                String repoKey = str(o, "repo_key");
                if (repoKey == null || repoKey.isBlank()) continue;
                out.add(new Lesson(
                    repoKey,
                    str(o, "subject"),
                    str(o, "role"),
                    str(o, "origin"),
                    str(o, "origin_ref"),
                    bool(o, "private"),
                    bool(o, "touched_test"),
                    num(o, "files_changed")));
            }
            return out;
        } catch (Exception e) {
            System.err.println("[ClaudeLessonData] parse failed: " + e.getMessage());
            return java.util.List.of();
        }
    }

    /**
     * Ingest lesson records into the KG. Overwrites only the claude_*
     * attributes — every other attribute is untouched. Returns repos ingested.
     */
    public static int ingest(KnowledgeGraph kg, List<Lesson> lessons) {
        if (kg == null || lessons == null) return 0;
        // repo key -> [count, prCount, branchCount, testCount, newest subject]
        java.util.Map<String, int[]> counts = new java.util.LinkedHashMap<>();
        java.util.Map<String, String> newest = new java.util.LinkedHashMap<>();

        for (Lesson l : lessons) {
            if (l.repoKey == null || l.repoKey.isBlank()) continue;
            int[] c = counts.computeIfAbsent(l.repoKey, k -> new int[4]);
            c[0]++;
            if ("pull_request".equals(l.origin)) c[1]++;
            if ("branch".equals(l.origin)) c[2]++;
            if (l.touchedTest) c[3]++;
            if (l.subject != null && !l.subject.isBlank()) {
                // The ledger is newest-first; keep the first subject seen.
                newest.putIfAbsent(l.repoKey, l.subject);
            }
        }

        for (java.util.Map.Entry<String, int[]> e : counts.entrySet()) {
            int[] c = e.getValue();
            String key = e.getKey();
            kg.setAttribute(key, "claude_lessons", String.valueOf(c[0]));
            kg.setAttribute(key, "claude_pr_lessons", String.valueOf(c[1]));
            kg.setAttribute(key, "claude_branch_lessons", String.valueOf(c[2]));
            kg.setAttribute(key, "claude_test_touched", String.valueOf(c[3]));
            String subject = newest.get(key);
            if (subject != null) kg.setAttribute(key, "claude_recent_subject", subject);
        }
        return counts.size();
    }

    /** Load + ingest latest.json from the claude_watch data dir. */
    public static int ingest(KnowledgeGraph kg, Path watchDir) {
        if (kg == null || watchDir == null) return 0;
        Path latest = watchDir.resolve("latest.json");
        if (!Files.isRegularFile(latest)) return 0;
        try {
            return ingest(kg, parse(Files.readString(latest)));
        } catch (IOException e) {
            System.err.println("[ClaudeLessonData] read failed: " + e.getMessage());
            return 0;
        }
    }

    // ── tiny JSON helpers (no external deps beyond gson) ────────────────
    private static String str(com.google.gson.JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return null;
        try { return o.get(key).getAsString(); } catch (Exception e) { return null; }
    }

    private static boolean bool(com.google.gson.JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return false;
        try { return o.get(key).getAsBoolean(); } catch (Exception e) { return false; }
    }

    private static int num(com.google.gson.JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return 0;
        try { return o.get(key).getAsInt(); } catch (Exception e) { return 0; }
    }
}
