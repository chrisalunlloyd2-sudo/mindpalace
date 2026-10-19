package com.mindpalace.github;

import com.mindpalace.agent.KnowledgeGraph;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * H36a (#128): nightly repo-sweep ingest — reads scripts/repo_sweep.py's
 * latest.json (mindpalace_memory/repo_sweep/) and surfaces two per-repo
 * KnowledgeGraph attributes next to the #125 health rollup:
 *
 *   swept_issues   — open issue count at last sweep
 *   swept_push_days — days since last push (-1 = never pushed)
 *
 * The Python sweep itself is quota-free (one /user/repos walk, ~2 requests
 * for the whole fleet; zero LLM calls). This class is pure stdlib JSON
 * parsing + ingest — no network. Additive only: RepoScanner.mergeRemoteRepos
 * is untouched, and attributes live OUTSIDE the KG build() swap so they
 * survive LiveUpdate rebuilds (same pattern as the health attributes).
 */
public final class RepoSweepData {

    private RepoSweepData() { }

    /** One repo's swept facts (subset of the Python record). */
    public static final class SweptRepo {
        public final String repo;
        public final int openIssues;
        public final int pushDays; // -1 = never pushed

        SweptRepo(String repo, int openIssues, int pushDays) {
            this.repo = repo;
            this.openIssues = openIssues;
            this.pushDays = pushDays;
        }
    }

    /** Parse latest.json body into {@link SweptRepo} records. Null-safe. */
    public static List<SweptRepo> parse(String json) {
        if (json == null || json.isBlank()) return java.util.List.of();
        try {
            com.google.gson.JsonArray items = com.google.gson.JsonParser
                .parseString(json).getAsJsonObject()
                .getAsJsonArray("items");
            java.util.List<SweptRepo> out = new java.util.ArrayList<>();
            for (com.google.gson.JsonElement el : items) {
                com.google.gson.JsonObject o = el.getAsJsonObject();
                if (!o.has("repo") || o.get("repo").isJsonNull()) continue;
                int issues = o.has("open_issues") && !o.get("open_issues").isJsonNull()
                    ? o.get("open_issues").getAsInt() : 0;
                int days = o.has("push_days") && !o.get("push_days").isJsonNull()
                    ? o.get("push_days").getAsInt() : -1;
                out.add(new SweptRepo(o.get("repo").getAsString(), issues, days));
            }
            return out;
        } catch (Exception e) {
            System.err.println("[RepoSweepData] parse failed: " + e.getMessage());
            return java.util.List.of();
        }
    }

    /**
     * Ingest sweep records into the KG. Overwrites sweep attributes only —
     * health and all other attributes untouched. Returns repos ingested.
     */
    public static int ingest(KnowledgeGraph kg, List<SweptRepo> recs) {
        if (kg == null || recs == null) return 0;
        for (SweptRepo r : recs) {
            kg.setAttribute(r.repo, "swept_issues", String.valueOf(r.openIssues));
            kg.setAttribute(r.repo, "swept_push_days", String.valueOf(r.pushDays));
        }
        return recs.size();
    }

    /** Load + ingest latest.json from the sweep data dir. Returns repos, 0 when absent/failed. */
    public static int ingest(KnowledgeGraph kg, Path sweepDir) {
        if (kg == null || sweepDir == null) return 0;
        Path latest = sweepDir.resolve("latest.json");
        if (!Files.isRegularFile(latest)) return 0;
        try {
            return ingest(kg, parse(Files.readString(latest)));
        } catch (IOException e) {
            System.err.println("[RepoSweepData] read failed: " + e.getMessage());
            return 0;
        }
    }
}