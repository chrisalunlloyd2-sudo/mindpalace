package com.mindpalace.github;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Fleet "gist wall" data source — surfaces Chris's shared fleet gist
 * (fleet_status.jsonl + workflow_logits.jsonl) and the word-library success
 * paths (BDI_FSM_DAGs word_library/success.jsonl) as short readable lines for an
 * in-game wall/panel.
 *
 * Deterministic + ADD-only + paced: the fetch runs on a background daemon thread
 * with a 60s cooldown, so it never blocks the render loop and "nothing runs for
 * free". If the network/token is unavailable the wall shows an offline line and
 * the game keeps running.
 */
public class GistWall {
    private static final String API_BASE = "https://api.github.com";
    private static final String FLEET_GIST_ID = "04debe0724a26fdee12a9e6d82c4eb56";
    private static final String WORDLIB_PATH = "word_library/success.jsonl";
    private static final long COOLDOWN_MS = 60_000L;
    private static final int MAX_STATUS = 4;
    private static final int MAX_LOGIT = 3;
    private static final int MAX_WORDLIB = 3;

    private final OkHttpClient http;
    private String token;
    private List<String> lines = new ArrayList<>();
    private long lastFetchMs = 0L;
    private boolean fetching = false;
    private boolean fetched = false;
    private String lastError = null;

    public GistWall() {
        http = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build();
    }

    public void setToken(String t) { this.token = t; }
    public boolean isFetched() { return fetched; }
    public String getLastError() { return lastError; }

    /** Snapshot of the current wall lines (header lines start with "=="). */
    public synchronized List<String> getLines() { return new ArrayList<>(lines); }

    /**
     * Kick a fetch if the cooldown has elapsed. Safe to call every frame — the
     * actual network work happens on a daemon thread and only fires every 60s.
     */
    public synchronized void refresh() {
        if (token == null || token.length() < 20) {
            // No token — show an offline hint instead of a blank wall (matches javadoc).
            if (lines.isEmpty()) lines = java.util.Collections.singletonList("[gist wall: no token]");
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastFetchMs < COOLDOWN_MS || fetching) return;
        lastFetchMs = now;
        fetching = true;
        Thread t = new Thread(this::doFetch, "gist-wall-fetch");
        t.setDaemon(true);
        t.start();
    }

    private void doFetch() {
        try {
            List<String> next = new ArrayList<>();
            next.add("== FLEET ==");
            fetchGistLines("fleet_status.jsonl", next, MAX_STATUS, true);
            next.add("== LOGITS ==");
            fetchGistLines("workflow_logits.jsonl", next, MAX_LOGIT, false);
            next.add("== WORD LIB ==");
            fetchWordLibrary(next, MAX_WORDLIB);
            synchronized (this) {
                lines = next;
                fetched = true;
                lastError = null;
            }
        } catch (Exception e) {
            synchronized (this) {
                lastError = e.getMessage();
                if (lines.isEmpty()) lines = java.util.Collections.singletonList("[gist wall offline]");
            }
        } finally {
            synchronized (this) { fetching = false; }
        }
    }

    /** Fetch the last {@code max} lines of a named file in the fleet gist. */
    private void fetchGistLines(String filename, List<String> out, int max, boolean isStatus) throws IOException {
        String rawUrl = null;
        Request meta = new Request.Builder()
            .url(API_BASE + "/gists/" + FLEET_GIST_ID)
            .header("Authorization", "token " + token)
            .header("Accept", "application/vnd.github.v3+json")
            .build();
        try (Response resp = http.newCall(meta).execute()) {
            if (!resp.isSuccessful()) { out.add("[gist " + resp.code() + "]"); return; }
            JsonObject root = JsonParser.parseString(resp.body().string()).getAsJsonObject();
            JsonObject files = root.getAsJsonObject("files");
            if (files != null && files.has(filename)) {
                JsonObject f = files.getAsJsonObject(filename);
                if (f.has("raw_url") && !f.get("raw_url").isJsonNull()) {
                    rawUrl = f.get("raw_url").getAsString();
                }
            }
        }
        if (rawUrl == null) { out.add("[no " + filename + "]"); return; }

        Request raw = new Request.Builder()
            .url(rawUrl)
            .header("Authorization", "token " + token)
            .build();
        try (Response resp = http.newCall(raw).execute()) {
            if (!resp.isSuccessful()) { out.add("[raw " + resp.code() + "]"); return; }
            appendTail(resp.body().string(), out, max, isStatus);
        }
    }

    /** Fetch the last {@code max} lines of the word-library success paths. */
    private void fetchWordLibrary(List<String> out, int max) throws IOException {
        String url = API_BASE + "/repos/chrisalunlloyd2-sudo/BDI_FSM_DAGs/contents/" + WORDLIB_PATH;
        Request req = new Request.Builder()
            .url(url)
            .header("Authorization", "token " + token)
            .header("Accept", "application/vnd.github.v3.raw")
            .build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) { out.add("[wordlib " + resp.code() + "]"); return; }
            appendTail(resp.body().string(), out, max, false);
        }
    }

    /** Split content, keep the last {@code max} non-empty lines, format each. */
    private static void appendTail(String content, List<String> out, int max, boolean isStatus) {
        String[] all = content.split("\\n");
        int start = Math.max(0, all.length - max);
        for (int i = start; i < all.length; i++) {
            String line = all[i].trim();
            if (line.isEmpty()) continue;
            out.add(shorten(formatLine(line, isStatus), 64));
        }
    }

    /** Extract a short human string from a JSONL line; fall back to the raw line. */
    private static String formatLine(String line, boolean isStatus) {
        try {
            JsonObject o = JsonParser.parseString(line).getAsJsonObject();
            if (isStatus) {
                String agent = o.has("agent") ? o.get("agent").getAsString() : "?";
                String status = o.has("status") ? o.get("status").getAsString() : "";
                return agent + ": " + status;
            }
            if (o.has("path")) return o.get("path").getAsString();
            if (o.has("workflow")) return o.get("workflow").getAsString();
            if (o.has("agent")) return o.get("agent").getAsString();
        } catch (Exception ignored) { }
        return line;
    }

    private static String shorten(String s, int max) {
        return s.length() > max ? s.substring(0, max - 1) + "\u2026" : s;
    }
}
