package com.mindpalace.github;

import com.google.gson.*;
import okhttp3.*;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * GitHubIssueStream — the SLM agents' write channel to GitHub.
 *
 * ADD-ONLY by construction: the only operation exposed is {@link #raise}, which
 * POSTs a new issue. There is no close, no delete, no edit, no comment-delete.
 * Agents can only ever ADD issues — never remove or mutate anything. This is
 * the "raising issues only, add do not delete" rule enforced in code, not by
 * convention.
 *
 * The stream is paced: a minimum interval between raises (cellular pacing) so
 * the agents can't flood a repo, and a per-repo cap so a runaway agent can't
 * spam. Every raise is logged to the telemetry ledger.
 *
 * READ HALF (#118, step 91): {@link #refreshCrystals} is the mirrors-side of
 * the add-only discipline — a paced pure GET (rides {@link #listOpen}) that
 * surfaces open issues as TodoCrystals. No close/edit/delete exists in code.
 */
public final class GitHubIssueStream {

    private static final String API_BASE = "https://api.github.com";
    private static final long READ_COOLDOWN_MS = 60_000L;  // GistWall pacing: 1 drip/60s
    private static final int READ_PER_REPO_CAP = 3;        // issues per repo per pull

    private final OkHttpClient http;
    private final String token;
    private final String username;
    private final long minIntervalMs;   // cellular pacing: min gap between raises
    private long lastRaiseMs = 0L;

    // Read-side pacing state: one drip (repo read) per cooldown, round-robin
    // queue so a busy repo never starves the others (FLEET doctrine).
    private long lastReadMs = 0L;
    private boolean fetching = false;   // GistWall guard: one read thread at a time
    private final java.util.Deque<String> readQueue = new java.util.ArrayDeque<>();
    private final java.util.List<String> knownRepos = new java.util.ArrayList<>();
    private final java.util.Set<String> knownRepoSet = new java.util.HashSet<>();
    // Add-only dedup: each (repo, issue#) is emitted AT MOST ONCE — ever. Round
    // -robin refetches can't re-emit an already-standing crystal (nothing is
    // deleted; closed issues simply leave their crystal standing).
    private final java.util.Set<String> emittedKeys = new java.util.HashSet<>();
    private final Object emittedLock = new Object();

    public GitHubIssueStream(String token, String username, long minIntervalMs) {
        this.token = token;
        this.username = username;
        this.minIntervalMs = minIntervalMs;
        this.http = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();
    }

    public GitHubIssueStream(String token, String username) {
        this(token, username, 30_000L); // default: one issue per 30s
    }

    /**
     * Raise a new issue (ADD-ONLY). Returns the issue number, or -1 on failure.
     * Enforces cellular pacing: if called too soon after the last raise, it
     * returns -2 (paced) without hitting the API.
     */
    public synchronized int raise(String repo, String title, String body, String... labels) {
        long now = System.currentTimeMillis();
        if (now - lastRaiseMs < minIntervalMs) {
            return -2; // paced — too soon
        }
        lastRaiseMs = now; // pace every attempt, success or failure (no flooding)

        JsonObject payload = new JsonObject();
        payload.addProperty("title", title);
        payload.addProperty("body", body);
        if (labels.length > 0) {
            JsonArray arr = new JsonArray();
            for (String l : labels) arr.add(l);
            payload.add("labels", arr);
        }

        RequestBody reqBody = RequestBody.create(payload.toString(), MediaType.parse("application/json"));
        Request req = new Request.Builder()
            .url(API_BASE + "/repos/" + username + "/" + repo + "/issues")
            .header("Authorization", "token " + token)
            .header("Accept", "application/vnd.github.v3+json")
            .post(reqBody)
            .build();

        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                System.err.println("[IssueStream] raise failed: " + resp.code() + " " + resp.message());
                return -1;
            }
            String bodyStr = resp.body().string();
            JsonObject obj = JsonParser.parseString(bodyStr).getAsJsonObject();
            int number = obj.get("number").getAsInt();
            System.out.println("[IssueStream] raised #" + number + " on " + repo + ": " + title);
            return number;
        } catch (IOException e) {
            System.err.println("[IssueStream] raise error: " + e.getMessage());
            return -1;
        }
    }

    /** List open issues (read-only — agents may read, never mutate). */
    public JsonArray listOpen(String repo) throws IOException {
        Request req = new Request.Builder()
            .url(API_BASE + "/repos/" + username + "/" + repo + "/issues?state=open")
            .header("Authorization", "token " + token)
            .header("Accept", "application/vnd.github.v3+json")
            .build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) return new JsonArray();
            return JsonParser.parseString(resp.body().string()).getAsJsonArray();
        }
    }

    /**
     * (#118, step 91) Register a repo whose open issues should surface
     * in-world. Called once per mapped room at boot — no queries here.
     */
    public synchronized void watchRepo(String repo) {
        if (repo != null && !knownRepoSet.contains(repo)) {
            knownRepoSet.add(repo);
            knownRepos.add(repo);
        }
    }

    public synchronized int watchedRepoCount() { return knownRepos.size(); }

    /** Repos still queued for their next read (drain diagnostics). */
    public synchronized int pendingReads() { return readQueue.size(); }

    /**
     * Pure label builder (#118, step 91) — deterministic so the selftest can
     * drive it with a synthetic issue JSON, no network. Returns {@code null}
     * for entries this feed must skip (pull requests ride the issues
     * endpoint). Format: {@code #NN ISSUE <title> — <body-prefix>}.
     */
    public static String crystalTextFor(JsonObject o) {
        if (o == null || o.has("pull_request")) return null;
        if (!o.has("number") || o.get("number").isJsonNull()) return null;
        int num = o.get("number").getAsInt();
        String title = o.has("title") && !o.get("title").isJsonNull()
            ? o.get("title").getAsString() : "?";
        String body = o.has("body") && !o.get("body").isJsonNull()
            ? o.get("body").getAsString() : "";
        return "#" + num + " ISSUE " + title
            + (body.length() > 40 ? " — " + body.substring(0, 40) : "");
    }

    /**
     * Paced drip fetch (#118, GistWall doctrine): kick at most one repo read
     * per 60s cooldown; the actual HTTP runs on a daemon thread so the render
     * loop NEVER blocks, and emitted crystals hand to the callback on that
     * thread (same contract as {@code AgentManager.setIssuesCallback}). Each
     * issue number is emitted once per repo — round-robin refetches can't
     * flood the world with duplicates (add-only: closed issues leave their
     * crystal standing; nothing is deleted). Call every frame — self-pacing.
     */
    public synchronized void refreshCrystals(java.util.function.Consumer<com.mindpalace.world.TodoCrystal> emit) {
        if (token == null || token.length() < 20) return; // offline → silent
        if (fetching) return;                             // one read thread at a time
        long now = System.currentTimeMillis();
        if (now - lastReadMs < READ_COOLDOWN_MS) return;  // paced: 1 drip/60s
        if (readQueue.isEmpty()) {
            if (knownRepos.isEmpty()) return;
            readQueue.addAll(knownRepos);
        }
        lastReadMs = now;
        final String repo = readQueue.pollFirst(); // round-robin: no repo starves
        fetching = true;
        Thread t = new Thread(() -> {
            try {
                JsonArray arr = listOpen(repo);
                int added = 0;
                for (JsonElement el : arr) {
                    if (added >= READ_PER_REPO_CAP) break;
                    JsonObject o = el.getAsJsonObject();
                    String text = crystalTextFor(o);
                    if (text == null) continue;
                    int num = o.get("number").getAsInt();
                    synchronized (emittedLock) {
                        if (!emittedKeys.add(repo + "#" + num)) continue; // add-only dedup
                    }
                    emit.accept(new com.mindpalace.world.TodoCrystal(text, repo, "github-issue"));
                    added++;
                }
            } catch (Exception e) {
                System.err.println("[IssueStream] read error " + repo + ": " + e.getMessage());
            } finally {
                fetching = false;
            }
        }, "issue-stream-read");
        t.setDaemon(true);
        t.start();
    }
}