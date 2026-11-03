package com.mindpalace.github;

import com.google.gson.*;
import com.mindpalace.world.Book;
import com.mindpalace.world.Room;
import okhttp3.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * GitHub REST API client.
 * Uses PAT from Windows Credential Manager or settings.
 */
public class GitHubClient {
    private static final String API_BASE = "https://api.github.com";
    private OkHttpClient http;
    private String token;
    private String username = "chrisalunlloyd2-sudo";
    private boolean authenticated = false;

    public record FileSnapshot(String content, String sha) {}

    public GitHubClient() {
        http = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();
    }

    /**
     * Try to get token from Windows Credential Manager.
     * Spawns `git credential-manager get` directly (no shell) and writes the
     * credential request to stdin — avoids WSL/bash resolution issues.
     */
    public boolean loadTokenFromCredentialManager() {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "credential-manager", "get");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.getOutputStream().write("protocol=https\nhost=github.com\n\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            p.getOutputStream().flush();
            p.getOutputStream().close();
            // t_b2e9bc3f gate fix: GCM intermittently wedges (observed twice
            // this session — jstack showed the engine hung in readAllBytes on
            // GCM's stdout). waitFor()/readAllBytes block forever, freezing
            // startup. Bound the whole handshake to 12s and destroy the child
            // on timeout — the engine already has a fallback path.
            boolean finished = p.waitFor(12, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                System.err.println("[GitHub] Credential Manager timed out after 12s — using fallback auth");
            }
            String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);

            for (String line : out.split("\n")) {
                if (line.toLowerCase().startsWith("password=")) {
                    String tok = line.substring("password=".length()).trim();
                    tok = tok.replaceAll("[^\\x20-\\x7E]", "");
                    if (tok.length() >= 20) {
                        this.token = tok;
                        this.authenticated = true;
                        System.out.println("[GitHub] Authenticated via Windows Credential Manager");
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[GitHub] Failed to load token: " + e.getMessage());
        }
        return false;
    }

    public void setToken(String token) {
        this.token = token;
        this.authenticated = (token != null && token.length() >= 40);
    }

    public boolean isAuthenticated() { return authenticated; }
    public String getToken() { return token; }

    // ── TTL cache for the full repo list (same pattern as contentsCache/
    // fileCache below). The Evolve background loop calls fetchAllRepos()
    // every 2-3 generations with no gate, re-pulling all pages every time —
    // this was the only fetch method in the class without a cache.
    private static final long REPO_LIST_CACHE_TTL_MS = 10 * 60 * 1000L;
    private volatile List<Room> cachedRepoList;
    private volatile int lastFetchLogCount = -1;   // log the fetch only when the count changes (or hourly)
    private volatile long lastFetchLogAt;
    private volatile long cachedRepoListAt;

    /**
     * Fetch all repos for the authenticated user. Cached for 10 minutes —
     * call clearRepoListCache() (or clearCache()) to force a fresh pull.
     */
    public List<Room> fetchAllRepos() throws IOException {
        if (cachedRepoList != null && System.currentTimeMillis() - cachedRepoListAt < REPO_LIST_CACHE_TTL_MS) {
            return cachedRepoList;
        }

        List<Room> rooms = new ArrayList<>();
        int page = 1;

        while (true) {
            Request req = new Request.Builder()
                .url(API_BASE + "/user/repos?per_page=100&page=" + page + "&sort=updated")
                .header("Authorization", "token " + token)
                .header("Accept", "application/vnd.github.v3+json")
                .build();

            try (Response resp = http.newCall(req).execute()) {
                if (!resp.isSuccessful()) {
                    System.err.println("[GitHub] API error: " + resp.code());
                    break;
                }

                String body = resp.body().string();
                JsonArray arr = JsonParser.parseString(body).getAsJsonArray();
                if (arr.size() == 0) break;

                for (JsonElement el : arr) {
                    JsonObject obj = el.getAsJsonObject();
                    Room room = new Room(obj.get("name").getAsString());
                    room.setPrivate(obj.get("private").getAsBoolean());
                    room.setRemoteUrl(obj.get("clone_url").getAsString());

                    if (obj.has("description") && !obj.get("description").isJsonNull()) {
                        room.setRepoDescription(obj.get("description").getAsString());
                    }
                    if (obj.has("language") && !obj.get("language").isJsonNull()) {
                        room.setLanguage(obj.get("language").getAsString());
                    }
                    if (obj.has("stargazers_count")) {
                        room.setStarCount(obj.get("stargazers_count").getAsInt());
                    }

                    rooms.add(room);
                }
                page++;
            }
        }

        long nowLog = System.currentTimeMillis();
        if (rooms.size() != lastFetchLogCount || nowLog - lastFetchLogAt > 3_600_000L) {
            System.out.println("[GitHub] Fetched " + rooms.size() + " repos from GitHub");
            lastFetchLogCount = rooms.size();
            lastFetchLogAt = nowLog;
        }
        cachedRepoList = rooms;
        cachedRepoListAt = System.currentTimeMillis();
        return rooms;
    }

    /** Force the next fetchAllRepos() call to hit the network. */
    public void clearRepoListCache() { cachedRepoList = null; }

    /**
     * Fetch file contents for a repo (top-level only for speed).
     */
    // ── TTL cache (M2, step 116): GitHub API responses cached 10 minutes ──
    // The agents' tool loop can hit contents/ dozens of times per cycle;
    // rate limits are 5000/hr authenticated. Cache is per-user session
    // (in-memory, not persisted — a stale read is one refresh away).
    private static final long CACHE_TTL_MS = 10 * 60 * 1000L;
    private final class CacheEntry<T> { final T value; final long at; CacheEntry(T v) { value = v; at = System.currentTimeMillis(); } boolean fresh() { return System.currentTimeMillis() - at < CACHE_TTL_MS; } }
    private final java.util.Map<String, CacheEntry<List<Book>>> contentsCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, CacheEntry<String>> fileCache = new java.util.concurrent.ConcurrentHashMap<>();

    /** Clear all API caches (forces fresh fetches; used by the editor after writes). */
    public void clearCache() { contentsCache.clear(); fileCache.clear(); commitCache.clear(); clearRepoListCache(); }

    public List<Book> fetchRepoContents(String repoName) throws IOException {
        List<Book> books = new ArrayList<>();
        CacheEntry<List<Book>> hit = contentsCache.get(repoName);
        if (hit != null && hit.fresh()) return hit.value;

        Request req = new Request.Builder()
            .url(API_BASE + "/repos/" + username + "/" + repoName + "/contents/")
            .header("Authorization", "token " + token)
            .header("Accept", "application/vnd.github.v3+json")
            .build();

        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) return books;

            String body = resp.body().string();
            JsonArray arr = JsonParser.parseString(body).getAsJsonArray();

            for (JsonElement el : arr) {
                JsonObject obj = el.getAsJsonObject();
                String type = obj.get("type").getAsString();
                String name = obj.get("name").getAsString();
                String path = obj.get("path").getAsString();

                if (type.equals("file")) {
                    Book book = new Book(name, path);
                    book.setLanguage(Book.detectLanguage(name));
                    if (obj.has("size")) book.setSizeBytes(obj.get("size").getAsLong());
                    if (obj.has("sha")) book.setSha(obj.get("sha").getAsString());
                    books.add(book);
                }
            }
        }

        return books;
    }

    /**
     * Fetch a single file's content.
     */
    public String fetchFileContent(String repoName, String filePath) throws IOException {
        String key = repoName + ":" + filePath;
        CacheEntry<String> hit = fileCache.get(key);
        if (hit != null && hit.fresh()) return hit.value;
        Request req = new Request.Builder()
            .url(API_BASE + "/repos/" + username + "/" + repoName + "/contents/" + filePath)
            .header("Authorization", "token " + token)
            .header("Accept", "application/vnd.github.v3.raw")
            .build();

        try (Response resp = http.newCall(req).execute()) {
            if (resp.isSuccessful()) {
                String body = resp.body().string();
                fileCache.put(key, new CacheEntry<>(body));
                return body;
            }
        }
        return null;
    }

    /**
     * Create or update a file in a repo.
     */
    // ── Issue reads (#118, step 91): GitHubIssueStream is the ADD-ONLY write
    // half (agents raise); this is the read half — pure GET, never wired to
    // close/edit/delete. TTL-cached like the fetchers above so room visits
    // and round-robin drains don't hammer the API.
    private final java.util.Map<String, CacheEntry<List<OpenIssue>>> issueCache = new java.util.concurrent.ConcurrentHashMap<>();

    /** Minimal open-issue shape for in-world display + step-92 verdicts. */
    public record OpenIssue(int number, String title, String body) {}

    /**
     * List open issues for a repo (newest first, cap 20). PRs (which ride
     * this endpoint) are filtered out; an API error returns an empty list —
     * the read path never blows up the caller.
     */
    public List<OpenIssue> listOpenIssues(String repoName) throws IOException {
        CacheEntry<List<OpenIssue>> hit = issueCache.get(repoName);
        if (hit != null && hit.fresh()) return hit.value;

        Request req = new Request.Builder()
            .url(API_BASE + "/repos/" + username + "/" + repoName
                + "/issues?state=open&per_page=20&sort=created&direction=desc")
            .header("Authorization", "token " + token)
            .header("Accept", "application/vnd.github.v3+json")
            .build();

        List<OpenIssue> issues = new ArrayList<>();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) return issues;
            issues = parseOpenIssues(resp.body().string());
        }
        issueCache.put(repoName, new CacheEntry<>(issues));
        return issues;
    }

    /** Pure parse of a GET /issues JSON array → OpenIssues (PR rows dropped). */
    public static List<OpenIssue> parseOpenIssues(String body) {
        List<OpenIssue> issues = new ArrayList<>();
        JsonArray arr = JsonParser.parseString(body).getAsJsonArray();
        for (JsonElement el : arr) {
            JsonObject obj = el.getAsJsonObject();
            if (obj.has("pull_request")) continue; // PRs ride the issues endpoint
            String text = obj.has("body") && !obj.get("body").isJsonNull()
                ? obj.get("body").getAsString() : "";
            issues.add(new OpenIssue(
                obj.get("number").getAsInt(),
                obj.get("title").getAsString(),
                text));
        }
        return issues;
    }

    // ── H37 (#122, step 95): commit engravings for remote-only rooms ──
    // Door plaques engrave the last 10 commits. Local rooms read real
    // `git log -10` in RepoMapper; remote-only rooms (no local clone) need
    // this /commits read — same 10-minute TTL cache pattern as the other
    // read paths, empty list on API error (read path never blows up the caller).
    private final java.util.Map<String, CacheEntry<List<String>>> commitCache =
        new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Last 10 commit one-liners for a repo ("sha subject", newest first).
     * Cached 10 minutes; an API error returns an empty list. Pure-parse
     * companion below mirrors parseOpenIssues for selftest coverage.
     */
    public List<String> listRecentCommits(String repoName) throws IOException {
        CacheEntry<List<String>> hit = commitCache.get(repoName);
        if (hit != null && hit.fresh()) return hit.value;

        Request req = new Request.Builder()
            .url(API_BASE + "/repos/" + username + "/" + repoName + "/commits?per_page=10")
            .header("Authorization", "token " + token)
            .header("Accept", "application/vnd.github.v3+json")
            .build();

        List<String> commits = new ArrayList<>();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) return commits;
            commits = parseRecentCommits(resp.body().string());
        }
        commitCache.put(repoName, new CacheEntry<>(commits));
        return commits;
    }

    /** Pure parse of a GET /commits JSON array → "sha subject" one-liners (last 10). */
    public static List<String> parseRecentCommits(String body) {
        List<String> out = new ArrayList<>();
        JsonArray arr = JsonParser.parseString(body).getAsJsonArray();
        for (JsonElement el : arr) {
            if (out.size() >= 10) break;
            JsonObject obj = el.getAsJsonObject();
            String sha = obj.get("sha").getAsString();
            if (sha.length() > 7) sha = sha.substring(0, 7);
            JsonObject ci = obj.getAsJsonObject("commit");
            String msg = ci.get("message").getAsString();
            int nl = msg.indexOf('\n');
            String subject = nl >= 0 ? msg.substring(0, nl) : msg;
            out.add(sha + " " + subject);
        }
        return out;
    }

    public boolean upsertFile(String repoName, String filePath, String content, String commitMessage, String sha) throws IOException {
        return upsertFile(repoName, filePath, content, commitMessage, sha, null);
    }

    /**
     * #287: write to a named branch. branch == null keeps the old behaviour (the repo's default branch) for the
     * player's own edits in BookEditor/BookViewer; autonomous agent writes always pass an agent/ branch.
     */
    public boolean upsertFile(String repoName, String filePath, String content, String commitMessage, String sha,
                              String branch) throws IOException {
        JsonObject body = contentsBody(commitMessage, content, sha, branch);

        RequestBody reqBody = RequestBody.create(body.toString(), MediaType.parse("application/json"));
        Request req = new Request.Builder()
            .url(API_BASE + "/repos/" + username + "/" + repoName + "/contents/" + filePath)
            .header("Authorization", "token " + token)
            .put(reqBody)
            .build();

        try (Response resp = http.newCall(req).execute()) {
            boolean ok = resp.isSuccessful();
            if (ok) clearCache(); // writes invalidate the whole session cache (KISS)
            return ok;
        }
    }

    /**
     * Delete a file from a repo.
     */
    public boolean deleteFile(String repoName, String filePath, String sha, String commitMessage) throws IOException {
        return deleteFile(repoName, filePath, sha, commitMessage, null);
    }

    /** #287: delete on a named branch (null = default branch, the player's own UI path). */
    public boolean deleteFile(String repoName, String filePath, String sha, String commitMessage, String branch) throws IOException {
        JsonObject body = contentsBody(commitMessage, null, sha, branch);

        RequestBody reqBody = RequestBody.create(body.toString(), MediaType.parse("application/json"));
        Request req = new Request.Builder()
            .url(API_BASE + "/repos/" + username + "/" + repoName + "/contents/" + filePath)
            .header("Authorization", "token " + token)
            .method("DELETE", reqBody)
            .build();

        try (Response resp = http.newCall(req).execute()) {
            boolean ok = resp.isSuccessful();
            if (ok) clearCache();
            return ok;
        }
    }

    // ── #287: the branch gate (NEXT_100_STEPS step 97's rule, now LAW because an in-game push path exists):
    // autonomous writes go to agent/<yyyyMMdd>, never to the default branch; a person merges.

    /** Pure: the agent branch for a day, e.g. agent/20261031. One branch per day keeps a day's agent work reviewable as one diff. */
    public static String agentBranchName(java.time.LocalDate day) {
        return "agent/" + day.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
    }

    public static boolean isSafeAgentBranch(String branch, String defaultBranch) {
        return branch != null && !branch.isBlank() && defaultBranch != null && !defaultBranch.isBlank()
            && !branch.equals(defaultBranch);
    }

    /** Pure: the PUT/DELETE /contents body. content == null for a delete; sha/branch omitted when null. */
    public static JsonObject contentsBody(String message, String content, String sha, String branch) {
        JsonObject body = new JsonObject();
        body.addProperty("message", message);
        if (content != null) {
            body.addProperty("content", java.util.Base64.getEncoder().encodeToString(
                content.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        if (sha != null) body.addProperty("sha", sha);
        if (branch != null) body.addProperty("branch", branch);
        return body;
    }

    /** The repo's default branch name, or null. */
    public String defaultBranch(String repoName) throws IOException {
        Request req = new Request.Builder()
            .url(API_BASE + "/repos/" + username + "/" + repoName)
            .header("Authorization", "token " + token)
            .header("Accept", "application/vnd.github.v3+json")
            .build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) return null;
            JsonObject o = JsonParser.parseString(resp.body().string()).getAsJsonObject();
            return o.has("default_branch") ? o.get("default_branch").getAsString() : null;
        }
    }

    /** Head commit sha of a branch, or null when the branch does not exist. */
    public String branchSha(String repoName, String branch) throws IOException {
        Request req = new Request.Builder()
            .url(API_BASE + "/repos/" + username + "/" + repoName + "/git/ref/heads/" + branch)
            .header("Authorization", "token " + token)
            .header("Accept", "application/vnd.github.v3+json")
            .build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) return null;
            JsonObject o = JsonParser.parseString(resp.body().string()).getAsJsonObject();
            return o.getAsJsonObject("object").get("sha").getAsString();
        }
    }

    /**
     * Make sure `branch` exists, branching from the default branch's head if not. true when it exists afterwards.
     * Never touches the default branch itself; a 422 (someone created it first) counts as existing.
     */
    public boolean ensureBranch(String repoName, String branch) throws IOException {
        String def = defaultBranch(repoName);
        if (!isSafeAgentBranch(branch, def)) return false;
        if (branchSha(repoName, branch) != null) return true;
        String base = branchSha(repoName, def);
        if (base == null) return false;
        JsonObject body = new JsonObject();
        body.addProperty("ref", "refs/heads/" + branch);
        body.addProperty("sha", base);
        Request req = new Request.Builder()
            .url(API_BASE + "/repos/" + username + "/" + repoName + "/git/refs")
            .header("Authorization", "token " + token)
            .post(RequestBody.create(body.toString(), MediaType.parse("application/json")))
            .build();
        try (Response resp = http.newCall(req).execute()) {
            return resp.isSuccessful() || resp.code() == 422;
        }
    }

    /** Blob sha of a file on a branch (needed to update or delete it), or null when the file is not there. */
    public String fileSha(String repoName, String filePath, String branch) throws IOException {
        Request req = new Request.Builder()
            .url(API_BASE + "/repos/" + username + "/" + repoName + "/contents/" + filePath
                + (branch == null ? "" : "?ref=" + branch))
            .header("Authorization", "token " + token)
            .header("Accept", "application/vnd.github.v3+json")
            .build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) return null;
            JsonElement e = JsonParser.parseString(resp.body().string());
            return e.isJsonObject() && e.getAsJsonObject().has("sha") ? e.getAsJsonObject().get("sha").getAsString() : null;
        }
    }

    /** File content and blob SHA from the same branch revision, or null when unavailable. */
    public FileSnapshot fetchFileSnapshot(String repoName, String filePath, String branch) throws IOException {
        if (branch == null) return null;
        Request req = new Request.Builder()
            .url(API_BASE + "/repos/" + username + "/" + repoName + "/contents/" + filePath + "?ref=" + branch)
            .header("Authorization", "token " + token)
            .header("Accept", "application/vnd.github.v3+json")
            .build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) return null;
            JsonElement e = JsonParser.parseString(resp.body().string());
            if (!e.isJsonObject()) return null;
            JsonObject file = e.getAsJsonObject();
            if (!file.has("content") || !file.has("sha")) return null;
            byte[] content = java.util.Base64.getMimeDecoder().decode(file.get("content").getAsString());
            return new FileSnapshot(new String(content, java.nio.charset.StandardCharsets.UTF_8),
                file.get("sha").getAsString());
        }
    }

    /** Raw file content on a branch (uncached: agent edits stack on the branch, not on the default branch), or null. */
    public String fetchFileContent(String repoName, String filePath, String branch) throws IOException {
        if (branch == null) return fetchFileContent(repoName, filePath);
        Request req = new Request.Builder()
            .url(API_BASE + "/repos/" + username + "/" + repoName + "/contents/" + filePath + "?ref=" + branch)
            .header("Authorization", "token " + token)
            .header("Accept", "application/vnd.github.v3.raw")
            .build();
        try (Response resp = http.newCall(req).execute()) {
            return resp.isSuccessful() ? resp.body().string() : null;
        }
    }

    /**
     * #127 (H35): post a comment on an issue — ADD-ONLY. Never closes or edits
     * the issue (the roadmap's own note); this endpoint (POST /issues/{n}/comments)
     * is incapable of either. Used by the step-92 verdict pipeline: each open
     * issue is quorum-voted and the verdict lands as a plain comment.
     */
    public boolean addIssueComment(String repoName, int issueNumber, String body) throws IOException {
        JsonObject payload = new JsonObject();
        payload.addProperty("body", body);
        RequestBody reqBody = RequestBody.create(payload.toString(), MediaType.parse("application/json"));
        Request req = new Request.Builder()
            .url(API_BASE + "/repos/" + username + "/" + repoName + "/issues/" + issueNumber + "/comments")
            .header("Authorization", "token " + token)
            .post(reqBody)
            .build();
        try (Response resp = http.newCall(req).execute()) {
            return resp.isSuccessful();
        }
    }
}
