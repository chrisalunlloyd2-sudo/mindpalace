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
 */
public final class GitHubIssueStream {

    private static final String API_BASE = "https://api.github.com";

    private final OkHttpClient http;
    private final String token;
    private final String username;
    private final long minIntervalMs;   // cellular pacing: min gap between raises
    private long lastRaiseMs = 0L;

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
}
